import DLSecurity
import Foundation

/// 回环桥的 **URLSession 出口**：暴露桥端口，让 `URLSession` 在桥**之上**做内层 TLS。
///
/// 方案 §15.1 第 2 条的指定实现是「外层 WSS/DLP；内层由 URLSession 做 TLS 和固定证书校验」。
/// 本类型提供那一步所需要的东西：一个只绑 `127.0.0.1` 的随机端口，以及维持隧道泵转的句柄。
///
/// 为什么不直接用 `InnerTLSChannel`：那个通道把「内层 TLS」也一起做掉了，
/// 于是上层必须自己写 HTTP/1.1 编解码。而 §15.1 第 5 条要求覆盖双向背压、分块、
/// EOF/半关闭、取消、连接复用、重连等情形 —— URLSession 已经全部处理，
/// 且与局域网路径共用同一个 `PinnedSessionDelegate`（§15.2 第 2 条：两条路钉扎同一插件证书）。
///
/// 顺序要求（与 `InnerTLSChannel.open` 相同，写错会互等）：
/// **先 `listen()` 拿到端口，再让 URLSession 发起连接，泵在后台等桥接受。**
public struct LoopbackEndpoint: @unchecked Sendable {
    /// 桥监听的端口（固定绑定 `127.0.0.1`）。
    public let port: UInt16
    /// 真实主机名，用于 `Host` 头与诊断（URL 用的是回环地址）。
    public let host: String
    private let bridge: LoopbackTunnelBridge
    private let pump: Task<Void, any Error>

    init(bridge: LoopbackTunnelBridge, port: UInt16, host: String, pump: Task<Void, any Error>) {
        self.bridge = bridge
        self.port = port
        self.host = host
        self.pump = pump
    }

    /// 把请求路径映射到回环端口上的 URL。
    ///
    /// 路径与查询原样保留 —— 插件按路径分发（`/dsh-link/...`），改路径会 404。
    public func url(path: String, query: String?) -> URL? {
        Self.url(port: port, path: path, query: query)
    }

    /// 纯函数版本（便于单测，不需要建桥）。
    public static func url(port: UInt16, path: String, query: String?) -> URL? {
        var components = URLComponents()
        components.scheme = "https"
        components.host = "127.0.0.1"
        components.port = Int(port)
        components.path = path.isEmpty ? "/" : path
        components.query = query
        return components.url
    }

    /// 关闭桥与泵（幂等）。
    public func close() {
        pump.cancel()
        bridge.close()
    }
}

/// 远程（DLP/1）传输：**回环桥 + 钉扎 URLSession**。
///
/// 每个请求 acquire 一条隧道 flow，在它上面叠一条回环桥，让 `URLSession` 穿过桥
/// 完成内层 TLS 与 HTTP。数据连接每流一条（RFC §5.4.3 / §15.1 第 1 条：不把多路复用写进 v1）。
public struct LoopbackURLSessionTransport: HostTransport {
    /// 按 host 取会合参数；返回 nil 表示该主机不可远程。
    private let routeProvider: @Sendable (String) -> RemoteTunnelRoute?
    /// 按 host 取配对时记录的内层证书指纹。
    private let fingerprintProvider: @Sendable (String) -> String?
    private let pool: RemoteTunnelPool
    /// 会话工厂（可注入以便测试打桩）。参数是期望指纹。
    private let makeSession: @Sendable (String?) -> (URLSession, PinnedSessionDelegate)

    public init(
        pool: RemoteTunnelPool,
        routeProvider: @escaping @Sendable (String) -> RemoteTunnelRoute?,
        fingerprintProvider: @escaping @Sendable (String) -> String?
    ) {
        self.init(
            pool: pool, routeProvider: routeProvider, fingerprintProvider: fingerprintProvider,
            makeSession: { fingerprint in
                let delegate = PinnedSessionDelegate(expectedFingerprint: fingerprint)
                // 每条请求一个短命 session：桥是「一次一条连接」的设计（LoopbackTunnelBridge
                // 文档），复用 session 会让 URLSession 复用到已关闭的桥。
                let configuration = URLSessionConfiguration.ephemeral
                configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
                configuration.httpShouldSetCookies = false
                let session = URLSession(
                    configuration: configuration, delegate: delegate, delegateQueue: nil)
                return (session, delegate)
            })
    }

    public init(
        pool: RemoteTunnelPool,
        routeProvider: @escaping @Sendable (String) -> RemoteTunnelRoute?,
        fingerprintProvider: @escaping @Sendable (String) -> String?,
        makeSession: @escaping @Sendable (String?) -> (URLSession, PinnedSessionDelegate)
    ) {
        self.pool = pool
        self.routeProvider = routeProvider
        self.fingerprintProvider = fingerprintProvider
        self.makeSession = makeSession
    }

    public func send(_ request: URLRequest) async throws -> TransportResponse {
        let (endpoint, lease) = try await open(request, kind: .short)
        let (session, delegate) = makeSession(fingerprintProvider(endpoint.host))
        defer {
            session.invalidateAndCancel()
            endpoint.close()
        }
        do {
            let scoped = try scoped(request, endpoint: endpoint)
            let (data, response) = try await session.data(for: scoped)
            await lease.release()
            guard let http = response as? HTTPURLResponse else {
                throw HostClientError.transport(URLError(.badServerResponse))
            }
            return TransportResponse(
                statusCode: http.statusCode, body: data, headers: Self.headers(http))
        } catch {
            await lease.release()
            throw Self.map(error, delegate: delegate)
        }
    }

    public func stream(_ request: URLRequest) async throws -> TransportStream {
        let (endpoint, lease) = try await open(request, kind: .sse)
        let (session, delegate) = makeSession(fingerprintProvider(endpoint.host))
        do {
            let scoped = try scoped(request, endpoint: endpoint)
            // `bytes(for:)` 不把 body 攒进内存 —— SSE 可能数小时不断推事件。
            let (bytes, response) = try await session.bytes(for: scoped)
            let status = (response as? HTTPURLResponse)?.statusCode

            let (lines, continuation) = AsyncThrowingStream<String, any Error>.makeStream()
            let consumer = Task {
                var splitter = SSELineSplitter()
                do {
                    for try await byte in bytes {
                        if let line = splitter.push(byte) { continuation.yield(line) }
                    }
                    if let rest = splitter.finish(), !rest.isEmpty { continuation.yield(rest) }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: Self.map(error, delegate: delegate))
                }
            }
            let teardown: @Sendable () -> Void = {
                consumer.cancel()
                session.invalidateAndCancel()
                endpoint.close()
                Task { await lease.release() }
            }
            continuation.onTermination = { _ in teardown() }
            return TransportStream(statusCode: status, lines: lines, onCancel: teardown)
        } catch {
            session.invalidateAndCancel()
            endpoint.close()
            await lease.release()
            throw Self.map(error, delegate: delegate)
        }
    }

    // MARK: - 内部

    private func open(
        _ request: URLRequest, kind: RemoteStreamKind
    ) async throws -> (LoopbackEndpoint, any RemoteTunnelLease) {
        guard let host = request.url?.host else { throw RemoteTunnelError.protocolViolation }
        guard let route = routeProvider(host) else { throw RemoteTunnelError.relayUnreachable }
        let lease = try await pool.acquire(host: host, kind: kind, route: route)
        do {
            let endpoint = try await NWRemoteTunnelTransport.openLoopback(
                over: lease.tunnel, host: host, expectedFingerprint: fingerprintProvider(host))
            return (endpoint, lease)
        } catch {
            await lease.release()
            throw Self.map(error, delegate: nil)
        }
    }

    /// 把请求改写到回环端点，同时把 `Host` 头还原成真实主机，
    /// 让插件侧的日志与来源判定仍看到原本的主机名。
    private func scoped(_ request: URLRequest, endpoint: LoopbackEndpoint) throws -> URLRequest {
        guard let url = request.url else { throw RemoteTunnelError.protocolViolation }
        guard let target = endpoint.url(path: url.path, query: url.query) else {
            throw RemoteTunnelError.protocolViolation
        }
        var scoped = URLRequest(url: target)
        scoped.httpMethod = request.httpMethod
        scoped.httpBody = request.httpBody
        scoped.allHTTPHeaderFields = request.allHTTPHeaderFields
        scoped.timeoutInterval = request.timeoutInterval
        if let host = url.host {
            let port = url.port.map { ":\($0)" } ?? ""
            scoped.setValue("\(host)\(port)", forHTTPHeaderField: "Host")
        }
        return scoped
    }

    private static func headers(_ response: HTTPURLResponse) -> [String: String] {
        var out: [String: String] = [:]
        for (key, value) in response.allHeaderFields {
            if let key = key as? String, let value = value as? String { out[key] = value }
        }
        return out
    }

    /// 传输层错误 → `HostClientError`，与局域网路径同一套上层语义。
    ///
    /// - **钉扎失败 → `.certificateChanged`**：§7.2 第 8 条的硬停止，不换路径、不自动重试。
    /// - **远程拒绝码只提示、不删凭据**（§7.4）：包成 `URLError` 并把 code 放进
    ///   `userInfo`，不改错误分类（`transport` 只收 `URLError`）。
    /// - 取消保留取消语义，便于调用方区分「用户离开」与「连接失败」。
    public static func map(_ error: any Error, delegate: PinnedSessionDelegate?) -> any Error {
        if let error = error as? HostClientError { return error }
        if error is CertificatePinError { return HostClientError.certificateChanged }
        if error is CancellationError { return error }
        if let error = error as? RemoteTunnelError {
            switch error {
            case .cancelled: return CancellationError()
            case .rejected(let code, _):
                return HostClientError.transport(
                    URLError(.badServerResponse, userInfo: [remoteRejectCodeKey: code]))
            default: return HostClientError.transport(URLError(.cannotConnectToHost))
            }
        }
        // URLSession 把钉扎拒绝报成 URLError(.serverCertificateUntrusted / .cancelled)，
        // 需要看 delegate 记录才能区分「证书不符」与「网络不通」。
        if let urlError = error as? URLError {
            if delegate?.pinFailureCount ?? 0 > 0 { return HostClientError.certificateChanged }
            if urlError.code == .cancelled { return CancellationError() }
            return HostClientError.transport(urlError)
        }
        return HostClientError.transport(URLError(.unknown))
    }
}

/// 远程拒绝码在 `URLError.userInfo` 里的键（只用于诊断，不参与错误分类）。
public let remoteRejectCodeKey = "cetus.remote.rejectCode"
