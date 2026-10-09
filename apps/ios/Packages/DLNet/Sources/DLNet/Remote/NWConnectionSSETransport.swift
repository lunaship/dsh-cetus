import DLSecurity
import Foundation
import os

/// 走 DLP/1 隧道 + 内层 TLS 的 SSE 传输（G4.1 第二阶段）。
///
/// 复用已就位的一切，不重造：
/// - 隧道与内层 TLS：`NWRemoteTunnelTransport.open(route)` + `openInnerTLS(...)`
///   （回环桥 + 系统 TLS + `PinEvaluation` 钉扎）
/// - 切行：`SSELineSplitter`（纯字节状态机，保留空行——SSE 靠空行分帧）
/// - HTTP/1.1：`HTTP1Wire.requestData(for:mode:)` + `HTTP1StreamParser`
/// - 限额：`RemoteTunnelPool` 的 `.sse` 档（每主机 ≤ 2，**不占短请求名额**，RFC §5.8）
///
/// **与一次性请求的关键差别**：SSE 是长连接，必须发 `Connection: keep-alive`
/// 且用流式解析器，不能在内存里攒 body。
///
/// **断线续传不在这里做**：`SSEClient` 重连时已经把 `Last-Event-ID`（已提交游标）
/// 放进请求头，本传输原样发出即可，游标语义由上游保证。
public struct NWConnectionSSETransport: SSETransport {
    /// 等响应头的超时。SSE 服务端会立刻回头，不该等太久；
    /// 超时按「没有状态码」处理，交由 `SSEClient` 走重试。
    public static let headTimeout: TimeInterval = 20

    private let pool: RemoteTunnelPool
    private let routeProvider: @Sendable (String) -> RemoteTunnelRoute?
    private let fingerprintProvider: @Sendable (String) -> String?

    /// - Parameters:
    ///   - pool: 限额与隧道来源。SSE 走 `.sse` 档。
    ///   - routeProvider: 按请求 host 给出会合参数；nil 表示该主机不可远程。
    ///   - fingerprintProvider: 按请求 host 给出内层证书指纹（配对时记录）。
    ///     返回 nil / 非法一律 fail-closed（§4.3），不回退系统 PKI。
    public init(
        pool: RemoteTunnelPool,
        routeProvider: @escaping @Sendable (String) -> RemoteTunnelRoute?,
        fingerprintProvider: @escaping @Sendable (String) -> String?
    ) {
        self.pool = pool
        self.routeProvider = routeProvider
        self.fingerprintProvider = fingerprintProvider
    }

    public func open(_ request: URLRequest) async throws -> SSEConnection {
        guard let host = request.url?.host else {
            throw RemoteTunnelError.protocolViolation
        }
        guard let route = routeProvider(host) else {
            throw RemoteTunnelError.relayUnreachable
        }
        let fingerprint = fingerprintProvider(host)

        // SSE 计入「每主机 ≤ 2」，不占短请求名额（RFC §5.8）。
        let lease = try await pool.acquire(host: host, kind: .sse, route: route)

        do {
            let channel = try await NWRemoteTunnelTransport.openInnerTLS(
                over: lease.tunnel, host: host, expectedFingerprint: fingerprint)

            // 响应头信号：行泵解析出头后回调（通道读循环只能有一个消费者）。
            let headSignal = HeadSignal()
            channel.onHead { head in headSignal.set(head) }

            let (stream, continuation) = AsyncThrowingStream<String, any Error>.makeStream()
            let pump = Task {
                await Self.pump(channel: channel, continuation: continuation)
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

            // keepAlive：SSE 是长连接，不得发 Connection: close（RFC §4.2）。
            try await channel.write(HTTP1Wire.requestData(for: request, mode: .keepAlive))

            // 状态码必须先于行流拿到：SSEClient 用它决定 401 终止 / 非 2xx 重试。
            let status = await headSignal.wait(timeout: Self.headTimeout)
            return SSEConnection(statusCode: status?.status, lines: stream, onCancel: teardown)
        } catch {
            await lease.release()
            throw error
        }
    }

    // MARK: - 行泵

    /// 读内层 TLS 明文 → HTTP 流式解析 → `SSELineSplitter` 切行 → `SSEClient`。
    ///
    /// 全程**不攒 body**：SSE 可能几小时不断推事件，攒起来会无限吃内存。
    private static func pump(
        channel: any StreamingTunnelByteChannel,
        continuation: AsyncThrowingStream<String, any Error>.Continuation
    ) async {
        var parser = HTTP1StreamParser()
        var splitter = SSELineSplitter()
        do {
            for try await chunk in channel.incoming {
                var output = try parser.push(chunk)
                if case .head(let head) = output {
                    channel.publishHead(head)
                    // 头与部分 body 同批到达：立刻把暂存 body 取出来，别让它躺着。
                    output = try parser.push(Data())
                }
                if case .body(let body) = output {
                    for byte in body {
                        if let line = splitter.push(byte) {
                            continuation.yield(line)
                        }
                    }
                }
            }
            if let rest = splitter.finish() {
                continuation.yield(rest)
            }
            continuation.finish()
        } catch {
            continuation.finish(throwing: error)
        }
    }
}

/// 响应头信号：行泵置位，`open(_:)` 等待。
///
/// 不用 `AsyncStream` 是因为等待方要带超时轮询，而 `AsyncStream` 是消费型的。
public final class HeadSignal: @unchecked Sendable {
    private struct State {
        var head: HTTP1StreamParser.Head?
        var closed = false
    }
    private let lock = OSAllocatedUnfairLock<State>(initialState: State())

    public init() {}

    public func set(_ head: HTTP1StreamParser.Head) { lock.withLock { $0.head = head } }
    public func markClosed() { lock.withLock { $0.closed = true } }

    /// 等到响应头或超时；超时返回 nil（交由上游按「无状态码」重试）。
    public func wait(timeout: TimeInterval) async -> HTTP1StreamParser.Head? {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            let state = lock.withLock { $0 }
            if let head = state.head { return head }
            if state.closed { return nil }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        return nil
    }
}
