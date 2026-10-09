import DLNet
import DLRemote
import DLSecurity
import Foundation

/// 远程（DLP/1）传输实现：`InnerTLSChannel` 之上直接写 HTTP/1.1 字节。
///
/// # ⚠️ 状态：**未接线**，作为**替代方案**保留
///
/// **主实现是 **（DLNet）：回环桥 + 钉扎 URLSession，
/// 即方案 §15.1 第 2 条指定的路线。本文件是它的备选。
///
/// 方案 §15.1 第 2 条指定的内层实现是 **URLSession**。本文档开头那段
/// 「URLSession 不可行」的理由**已被实验推翻** —— 第一版 spike 用的是**未钉扎**的
/// `URLSession(configuration: .ephemeral)`，它拒绝自签证书是必然的，但那**只能**
/// 说明「默认 session 不接受自签证书」，不能说明钉扎方案不行。
///
/// 换上真实的 `PinnedSessionDelegate` 重测（`RemoteLoopbackAdmissionSpikeTests`）：
/// 状态 200、钉扎失败 0 次、插件确实收到请求，且**可重复**。
///
/// 因此**路线选择是 URLSession**（见 `docs/cetus/C11-REMOTE.md` 第 2、4 节），
/// 本文件不参与生产选路。保留它的理由：若 URLSession 路线在真机（尤其运营商网络）
/// 上出现 §15.1 第 5 条列的问题（背压、半关闭、取消、Host 头），这里有一条
/// **已编译验证**的回退实现，无需从零写。
///
/// ## 为什么当初会想绕过 URLSession（保留作为背景）
///
/// 1. `LoopbackTunnelBridge` 是**裸 TCP 泵**，自身不带证书；它的字节要经 DLP/1 隧道
///    才到达插件。
/// 2. `NWRemoteTunnelTransport.openInnerTLS(tunnel:host:expectedFingerprint:)`
///    在**内部**把 `bridge.listen()` → 自建 `NWConnection`（`NWProtocolTLS` + `installPin`）
///    → `bridge.acceptAndPump()` 三者编排成一个整体，外部**拿不到**可交给 URLSession 的裸端口。
/// 3. 桥**只接受一条连接**。外部再插一条 URLSession 连接会与内部那条争抢同一个一次性 accept。
///
/// 而 iOS 没有 Android `SSLSocketFactory.createSocket(raw, host, port, false)` 那样
/// 「把 TLS 叠到一段外来字节流上」的公开 API —— 这正是 `LoopbackTunnelBridge` 文档开篇
/// 解释的问题。因此远程路径复用**已被 7 例测试覆盖**的 `InnerTLSChannel`，不再自建 TLS。
///
/// 详见 `docs/cetus/C11-REMOTE.md`。
///
/// ## 语义
///
/// - **每请求一条隧道 flow**（RFC §5.4.3：数据连接每流一条），上限由 `RemoteTunnelPool` 控制。
/// - 一次性请求：写完 → 读完整 body → 释放 lease。不 keep-alive（`Connection: close`）。
/// - SSE：`Connection: keep-alive`，边读边切行，**不把 body 攒进内存**。
/// - 证书钉扎：`openInnerTLS` 内用 `installPin` 对**插件证书**做，与局域网路径同源（§4.3）。
///   拿不到合法指纹时它 fail-closed，不回落系统 PKI。
public struct ChannelRemoteHostTransport: HostTransport {
    /// 按 host 取会合参数；nil 表示该主机不可远程。
    private let routeProvider: @Sendable (String) -> RemoteTunnelRoute?
    /// 按 host 取内层证书指纹（配对时记录）。
    private let fingerprintProvider: @Sendable (String) -> String?
    private let pool: RemoteTunnelPool
    /// 响应头等待超时（SSE 用；服务端应立刻回头）。
    private let headTimeout: TimeInterval

    public init(
        pool: RemoteTunnelPool,
        routeProvider: @escaping @Sendable (String) -> RemoteTunnelRoute?,
        fingerprintProvider: @escaping @Sendable (String) -> String?,
        headTimeout: TimeInterval = 20
    ) {
        self.pool = pool
        self.routeProvider = routeProvider
        self.fingerprintProvider = fingerprintProvider
        self.headTimeout = headTimeout
    }

    public func send(_ request: URLRequest) async throws -> TransportResponse {
        guard let host = request.url?.host else { throw RemoteTunnelError.protocolViolation }
        guard let route = routeProvider(host) else { throw RemoteTunnelError.relayUnreachable }
        let fingerprint = fingerprintProvider(host)

        let lease = try await pool.acquire(host: host, kind: .short, route: route)
        do {
            let channel = try await NWRemoteTunnelTransport.openInnerTLS(
                over: lease.tunnel, host: host, expectedFingerprint: fingerprint)
            defer { Task { await channel.close() } }

            // 一次性请求用 close：数据连接每流一条，不复用（RFC §5.4.3）。
            try await channel.write(HTTP1Wire.requestData(for: request, mode: .close))

            var parser = HTTP1StreamParser()
            var head: HTTP1StreamParser.Head?
            var body = Data()
            for try await chunk in channel.incoming {
                let output = try parser.push(chunk)
                switch output {
                case .head(let value):
                    head = value
                    // 头与部分 body 可能同批到达：推进一次拿暂存 body。
                    if case .body(let pending) = try parser.push(Data()) { body.append(pending) }
                case .body(let value):
                    body.append(value)
                case .pending:
                    break
                }
            }
            await lease.release()
            guard let head else { throw RemoteTunnelError.transport("remote response had no head") }
            return TransportResponse(statusCode: head.status, body: body, headers: head.headers)
        } catch {
            await lease.release()
            throw Self.map(error)
        }
    }

    public func stream(_ request: URLRequest) async throws -> TransportStream {
        guard let host = request.url?.host else { throw RemoteTunnelError.protocolViolation }
        guard let route = routeProvider(host) else { throw RemoteTunnelError.relayUnreachable }
        let fingerprint = fingerprintProvider(host)

        let lease = try await pool.acquire(host: host, kind: .sse, route: route)
        do {
            let channel = try await NWRemoteTunnelTransport.openInnerTLS(
                over: lease.tunnel, host: host, expectedFingerprint: fingerprint)

            // 响应头信号：通道读循环只能有一个消费者，所以由行泵解析出头后回吐。
            let headSignal = HeadSignal()
            channel.onHead { headSignal.set($0) }

            let (lines, continuation) = AsyncThrowingStream<String, any Error>.makeStream()
            let pump = Task {
                await Self.pumpLines(channel: channel, continuation: continuation)
            }
            let teardown: @Sendable () -> Void = {
                headSignal.markClosed()
                pump.cancel()
                Task {
                    await channel.close()
                    await lease.release()
                }
            }
            continuation.onTermination = { _ in teardown() }

            // SSE 是长连接，不得发 `Connection: close`（RFC §4.2）。
            try await channel.write(HTTP1Wire.requestData(for: request, mode: .keepAlive))

            // 状态码必须先于行流拿到：SSEClient 靠它判定 401 终止 / 非 2xx 重试。
            let head = await headSignal.wait(timeout: headTimeout)
            return TransportStream(statusCode: head?.status, lines: lines, onCancel: teardown)
        } catch {
            await lease.release()
            throw Self.map(error)
        }
    }

    /// 读内层 TLS 明文 → HTTP 流式解析 → 按 SSE 规则切行。
    ///
    /// 全程**不攒 body**：SSE 可能数小时不断推事件，攒起来会无限吃内存。
    private static func pumpLines(
        channel: any StreamingTunnelByteChannel,
        continuation: AsyncThrowingStream<String, any Error>.Continuation
    ) async {
        var parser = HTTP1StreamParser()
        var splitter = SSELineSplitter()
        do {
            for try await chunk in channel.incoming {
                var output = try parser.push(chunk)
                if case .head(let value) = output {
                    channel.publishHead(value)
                    output = try parser.push(Data())
                }
                if case .body(let value) = output {
                    for byte in value {
                        if let line = splitter.push(byte) { continuation.yield(line) }
                    }
                }
            }
            if let rest = splitter.finish(), !rest.isEmpty { continuation.yield(rest) }
            continuation.finish()
        } catch {
            continuation.finish(throwing: map(error))
        }
    }

    /// 把隧道层错误映射成 `HostClientError`，与局域网路径保持同一套上层语义。
    ///
    /// - 钉扎失败 → `.certificateChanged`：§7.2 第 8 条的**硬停止**，不换路径、不自动重试。
    /// - 远程拒绝码（`BAD_MAC` / `UNKNOWN_KEY` 等）：只提示，**不删凭据**（§7.4）。
    ///   包成 `URLError` 是因为 `HostClientError.transport` 只收 `URLError`；
    ///   拒绝码原文放进 `userInfo`（键 `remoteRejectCodeKey`）便于诊断，不改变错误分类。
    /// - 取消 → `CancellationError`，保留调用方的取消语义。
    /// - 其余隧道失败 → `URLError(.cannotConnectToHost)`，由上层按「连不上中继/电脑」呈现。
    static func map(_ error: any Error) -> any Error {
        if let error = error as? HostClientError { return error }
        if error is CertificatePinError { return HostClientError.certificateChanged }
        if error is CancellationError { return error }
        if let error = error as? RemoteTunnelError {
            switch error {
            case .cancelled:
                return CancellationError()
            case .rejected(let code, _):
                return HostClientError.transport(
                    URLError(.badServerResponse, userInfo: [remoteRejectCodeKey: code]))
            default:
                return HostClientError.transport(URLError(.cannotConnectToHost))
            }
        }
        return HostClientError.transport(URLError(.unknown))
    }
}

/// 远程拒绝码在 `URLError.userInfo` 里的键（只用于诊断，不参与错误分类）。
let remoteRejectCodeKey = "cetus.remote.rejectCode"
