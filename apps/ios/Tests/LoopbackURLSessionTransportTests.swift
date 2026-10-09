import DLNet
import DLSecurity
import Foundation
import Network
import Security
import Testing

/// C11 §15.1：**回环桥 + 钉扎 URLSession** 的传输实现（`RemoteHostTransport`）。
///
/// 与 `RemoteLoopbackAdmissionSpikeTests` 的分工：
/// - spike 回答「这条路**能不能**走」；
/// - 这里回答「按它写出来的**运输组件**对不对」——
///   每个请求各自建桥、状态码/响应体正确、`Host` 头还原、SSE 逐行不攒内存、
///   证书不符必须**硬停止**（`.certificateChanged`）、拒绝码**不删凭据**。
///
/// 用真隧道字节（假隧道把字节直接转发到本机 TLS 端口）与真 PKCS#12 fixture，
/// 不 mock 密码学。
@Suite(.serialized) struct LoopbackURLSessionTransportTests {
    // MARK: - 假隧道：字节直连本机 TLS 端口（跳过 DLP/1 加密层）

    private final class DirectTunnel: RemoteTunnel, @unchecked Sendable {
        private let connection: NWConnection
        private let continuation: AsyncThrowingStream<Data, any Error>.Continuation
        let incoming: AsyncThrowingStream<Data, any Error>

        init(port: UInt16) {
            let (stream, cont) = AsyncThrowingStream<Data, any Error>.makeStream(
                bufferingPolicy: .unbounded)
            incoming = stream
            continuation = cont
            connection = NWConnection(
                host: NWEndpoint.Host("127.0.0.1"), port: NWEndpoint.Port(rawValue: port)!,
                using: .tcp)
            connection.start(queue: .global())
            readLoop()
        }

        private func readLoop() {
            connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) {
                [weak self] data, _, isComplete, error in
                guard let self else { return }
                if let data, !data.isEmpty { self.continuation.yield(data) }
                if let error { self.continuation.finish(throwing: error) }
                if isComplete { self.continuation.finish() } else { self.readLoop() }
            }
        }

        func write(_ bytes: Data) async throws {
            try await withCheckedThrowingContinuation {
                (cont: CheckedContinuation<Void, any Error>) in
                connection.send(
                    content: bytes,
                    completion: .contentProcessed { error in
                        if let error { cont.resume(throwing: error) } else { cont.resume() }
                    })
            }
        }

        func close() async { connection.cancel() }
    }

    private struct FixedTunnelTransport: RemoteTunnelTransport {
        let port: UInt16
        func open(_ route: RemoteTunnelRoute) async throws -> any RemoteTunnel {
            DirectTunnel(port: port)
        }
    }

    // MARK: - 本机 TLS 服务（模拟插件 18640）

    private final class FakePlugin: @unchecked Sendable {
        private var listener: NWListener?
        private let lock = NSLock()
        private var requests: [String] = []
        /// 设为 true 时返回 JSON 数组（给 SSE 用）。
        var sseBody = ""

        var seenRequests: [String] { lock.withLock { requests } }

        func start(identity: sec_identity_t) async throws -> UInt16 {
            let tls = NWProtocolTLS.Options()
            sec_protocol_options_set_local_identity(tls.securityProtocolOptions, identity)
            sec_protocol_options_set_peer_authentication_required(
                tls.securityProtocolOptions, false)
            let listener = try NWListener(using: NWParameters(tls: tls, tcp: .init()), on: .any)
            self.listener = listener
            listener.newConnectionHandler = { [weak self] connection in
                connection.start(queue: .global())
                Task { await self?.serve(connection) }
            }
            return try await withCheckedThrowingContinuation { continuation in
                listener.stateUpdateHandler = { (state: NWListener.State) in
                    switch state {
                    case .ready: continuation.resume(returning: listener.port?.rawValue ?? 0)
                    case .failed(let error): continuation.resume(throwing: error)
                    default: break
                    }
                }
                listener.start(queue: .global())
            }
        }

        func stop() { listener?.cancel() }

        private func serve(_ connection: NWConnection) async {
            // 读到请求头结束（\r\n\r\n）为止，顺带把完整请求记下来供断言。
            var buffer = Data()
            let terminator = Data("\r\n\r\n".utf8)
            while buffer.range(of: terminator) == nil {
                let chunk = await withCheckedContinuation {
                    (continuation: CheckedContinuation<Data, Never>) in
                    connection.receive(minimumIncompleteLength: 1, maximumLength: 16384) {
                        data, _, _, _ in continuation.resume(returning: data ?? Data())
                    }
                }
                if chunk.isEmpty { break }
                buffer.append(chunk)
            }
            let request = String(decoding: buffer, as: UTF8.self)
            // TLS 握手失败时连接也会被接受，但请求头永远到不齐 —— 记为「未完成」，
            // 不塞进 seenRequests（否则「插件没收到请求」这类断言会被空串污染）。
            guard !request.isEmpty else {
                connection.cancel()
                return
            }
            lock.withLock { requests.append(request) }

            let body = request.contains("/events") ? sseBody : #"{"via":"transport","ok":true}"#
            let isSSE = request.contains("/events")
            let response =
                "HTTP/1.1 200 OK\r\nContent-Type: \(isSSE ? "text/event-stream" : "application/json")\r\n"
                + "Content-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n\(body)"
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                connection.send(
                    content: Data(response.utf8), completion: .contentProcessed { _ in continuation.resume() })
            }
            connection.cancel()
        }
    }

    // MARK: - fixture

    private final class BundleAnchor {}

    private static func loadIdentity() throws -> (identity: sec_identity_t, der: Data) {
        let bundle = Bundle(for: BundleAnchor.self)
        let url =
            bundle.url(forResource: "identity", withExtension: "p12")
            ?? bundle.url(forResource: "identity", withExtension: "p12", subdirectory: "Fixtures/inner-tls")
            ?? bundle.url(forResource: "identity", withExtension: "p12", subdirectory: "inner-tls")
        let resolved = try #require(url, "缺少 identity.p12 fixture")
        let p12 = try Data(contentsOf: resolved)
        var items: CFArray?
        let options = [kSecImportExportPassphrase as String: "dlptest"] as CFDictionary
        let status = SecPKCS12Import(p12 as CFData, options, &items)
        guard status == errSecSuccess, let array = items as? [[String: Any]], let first = array.first else {
            throw RemoteTunnelError.transport("SecPKCS12Import failed: \(status)")
        }
        let secIdentity = first[kSecImportItemIdentity as String] as! SecIdentity
        guard let identity = sec_identity_create(secIdentity) else {
            throw RemoteTunnelError.transport("sec_identity_create failed")
        }
        let leafURL =
            bundle.url(forResource: "leaf", withExtension: "der", subdirectory: "Fixtures/inner-tls")
            ?? bundle.url(forResource: "leaf", withExtension: "der", subdirectory: "inner-tls")
            ?? bundle.url(forResource: "leaf", withExtension: "der")
        let der = try Data(contentsOf: try #require(leafURL, "缺少 leaf.der fixture"))
        return (identity, der)
    }

    private static func route() -> RemoteTunnelRoute {
        RemoteTunnelRoute(
            endpoint: "wss://relay.example/ws", routeId: Data(repeating: 7, count: 16),
            kind: .device, keyId: Data(repeating: 9, count: 16), key: Data(repeating: 3, count: 32))
    }

    private static func makeTransport(
        port: UInt16, fingerprint: String?
    ) -> LoopbackURLSessionTransport {
        let pool = RemoteTunnelPool(transport: FixedTunnelTransport(port: port))
        return LoopbackURLSessionTransport(
            pool: pool,
            routeProvider: { _ in route() },
            fingerprintProvider: { _ in fingerprint })
    }

    // MARK: - 一次性请求

    @Test func sendReturnsStatusBodyAndPreservesHostHeader() async throws {
        let fixture = try Self.loadIdentity()
        let plugin = FakePlugin()
        let port = try await plugin.start(identity: fixture.identity)
        defer { plugin.stop() }
        let fingerprint = CertificateFingerprint.sha256(der: fixture.der)

        let transport = Self.makeTransport(port: port, fingerprint: fingerprint)
        let request = URLRequest(
            url: URL(string: "https://192.0.2.10:18640/dsh-link/mobile/bootstrap")!)
        let response = try await transport.send(request)

        #expect(response.statusCode == 200)
        #expect(String(decoding: response.body, as: UTF8.self).contains("\"via\":\"transport\""))
        #expect(response.headers["Content-Type"] == "application/json")

        let seen = try #require(plugin.seenRequests.first)
        #expect(seen.hasPrefix("GET /dsh-link/mobile/bootstrap HTTP/1.1"), "路径必须原样保留")
        // §15.1 第 5 条：Host 头要还原成真实主机，插件侧日志/来源判定才看到原本的名字。
        #expect(seen.contains("Host: 192.0.2.10:18640"), "Host 头应还原为真实主机，实际：\n\(seen)")
    }

    /// 每个请求各自建桥（桥是「一次一条连接」）。
    @Test func eachRequestGetsItsOwnBridge() async throws {
        let fixture = try Self.loadIdentity()
        let plugin = FakePlugin()
        let port = try await plugin.start(identity: fixture.identity)
        defer { plugin.stop() }
        let fingerprint = CertificateFingerprint.sha256(der: fixture.der)
        let transport = Self.makeTransport(port: port, fingerprint: fingerprint)
        let request = URLRequest(url: URL(string: "https://192.0.2.10:18640/dsh-link/health")!)

        for _ in 0..<3 {
            let response = try await transport.send(request)
            #expect(response.statusCode == 200)
        }
        #expect(plugin.seenRequests.count == 3, "三次请求都应送到插件")
    }

    // MARK: - SSE

    @Test func streamYieldsSSELinesAndStatus() async throws {
        let fixture = try Self.loadIdentity()
        let plugin = FakePlugin()
        plugin.sseBody = "event: heartbeat\ndata: {\"seq\":1}\n\nevent: session/state\ndata: {\"seq\":2}\n\n"
        let port = try await plugin.start(identity: fixture.identity)
        defer { plugin.stop() }
        let fingerprint = CertificateFingerprint.sha256(der: fixture.der)

        let transport = Self.makeTransport(port: port, fingerprint: fingerprint)
        let request = URLRequest(url: URL(string: "https://192.0.2.10:18640/dsh-link/mobile/events")!)
        let stream = try await transport.stream(request)

        #expect(stream.statusCode == 200)

        var lines: [String] = []
        for try await line in stream.lines {
            lines.append(line)
            if lines.count >= 6 { break }
        }
        stream.onCancel()

        #expect(lines.contains("event: heartbeat"), "应切出事件行，实际 \(lines)")
        #expect(lines.contains("data: {\"seq\":1}"), "应切出数据行，实际 \(lines)")
        #expect(lines.contains("event: session/state"))
        #expect(lines.contains(""), "SSE 靠空行分帧，空行必须保留")
    }

    // MARK: - 安全：钉扎与拒绝码

    /// §7.2 第 8 条：证书不符是**硬停止**，不换路径、不自动重试。
    @Test func wrongFingerprintIsHardStop() async throws {
        let fixture = try Self.loadIdentity()
        let plugin = FakePlugin()
        let port = try await plugin.start(identity: fixture.identity)
        defer { plugin.stop() }

        // 用一张合法但**不匹配**的指纹。
        let wrong = String(repeating: "ab", count: 32)
        let transport = Self.makeTransport(port: port, fingerprint: wrong)
        let request = URLRequest(url: URL(string: "https://192.0.2.10:18640/dsh-link/health")!)

        var thrown: (any Error)?
        do { _ = try await transport.send(request) } catch { thrown = error }
        let error = try #require(thrown)

        #expect(
            error as? HostClientError == .certificateChanged,
            "钉扎不符必须映射为 .certificateChanged，实际 \(error)")
        // 硬停止：握手失败，插件不应收到任何**完整**请求。
        #expect(plugin.seenRequests.isEmpty, "钉扎失败时不应有完整请求到达插件：\(plugin.seenRequests)")
    }

    /// §4.3 fail-closed：没有合法指纹时**不建桥**，也不回退系统 PKI。
    @Test func missingFingerprintFailsClosed() async throws {
        let fixture = try Self.loadIdentity()
        let plugin = FakePlugin()
        let port = try await plugin.start(identity: fixture.identity)
        defer { plugin.stop() }

        for absent in [nil, "", "not-hex"] {
            let transport = Self.makeTransport(port: port, fingerprint: absent)
            let request = URLRequest(url: URL(string: "https://192.0.2.10:18640/dsh-link/health")!)
            var thrown: (any Error)?
            do { _ = try await transport.send(request) } catch { thrown = error }
            #expect(
                thrown as? HostClientError == .certificateChanged,
                "指纹缺失/非法（\(absent ?? "nil")）应 fail-closed")
        }
        #expect(plugin.seenRequests.isEmpty, "fail-closed 时不得发出请求")
    }

    /// 不可远程的主机（routeProvider 返回 nil）应立刻失败，不建桥。
    @Test func hostWithoutRouteFailsFast() async throws {
        let pool = RemoteTunnelPool(transport: FixedTunnelTransport(port: 1))
        let transport = LoopbackURLSessionTransport(
            pool: pool, routeProvider: { _ in nil }, fingerprintProvider: { _ in "ab" })
        let request = URLRequest(url: URL(string: "https://192.0.2.10:18640/dsh-link/health")!)
        var thrown: (any Error)?
        do { _ = try await transport.send(request) } catch { thrown = error }
        #expect(thrown != nil, "无远程路由时必须失败，不能静默成功")
    }

    /// 拒绝码只作诊断信息，不改错误分类，也**不删凭据**（§7.4）。
    @Test func rejectCodeIsDiagnosticOnly() {
        let rejected = RemoteTunnelError.rejected(code: "BAD_MAC", hostNow: nil)
        let mapped = LoopbackURLSessionTransport.map(rejected, delegate: nil)
        let hostError = try? #require(mapped as? HostClientError)
        guard case .transport(let urlError) = hostError else {
            Issue.record("应映射为 .transport，实际 \(String(describing: hostError))")
            return
        }
        #expect(urlError.userInfo[remoteRejectCodeKey] as? String == "BAD_MAC", "拒绝码应保留供诊断")
        // 分类仍是 transport（凭据问题，不是证书问题）—— 上层据此只提示，不删凭据。
        #expect(hostError != .certificateChanged, "拒绝码不得被当成证书变更")
    }

    /// 取消要保留取消语义，便于区分「用户离开」与「连接失败」。
    @Test func cancellationStaysCancellation() {
        #expect(LoopbackURLSessionTransport.map(RemoteTunnelError.cancelled, delegate: nil) is CancellationError)
        #expect(LoopbackURLSessionTransport.map(CancellationError(), delegate: nil) is CancellationError)
    }

    /// 回环端点 URL 保留路径与查询（插件按路径分发）。
    @Test func loopbackURLKeepsPathAndQuery() throws {
        let original = URL(string: "https://192.0.2.10:18640/dsh-link/mobile/preview/abc/?token=x")!
        let scoped = try #require(
            LoopbackEndpoint.url(port: 51234, path: original.path, query: original.query))
        #expect(scoped.path == original.path, "路径不得改动（原 \(original.path)，实际 \(scoped.path)）")
        #expect(scoped.query == original.query, "查询参数不得丢失（原 \(original.query ?? "nil")）")
        #expect(scoped.host == "127.0.0.1", "URL 必须指向回环")
        #expect(scoped.port == 51234)
        #expect(scoped.scheme == "https", "内层必须仍是 TLS")
    }

    /// 空路径要落到 `/`，不能生成没有路径的 URL（会被插件当 404）。
    @Test func emptyPathBecomesRoot() throws {
        let scoped = try #require(LoopbackEndpoint.url(port: 1, path: "", query: nil))
        #expect(scoped.path == "/")
    }
}
