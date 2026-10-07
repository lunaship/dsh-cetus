import DLRemote
import Foundation
import Network
import os

/// 隧道与本机 TLS 栈之间的**回环桥**（iOS 上方案 C 的核心，等价 Android `TunnelSocket` 的网关）。
///
/// ## 为什么必须有它
///
/// RFC 0001 §4.2 第 6 步要求「手机在管道上做内层 TLS（钉扎插件证书）」。iOS 没有
/// `SSLSocketFactory.createSocket(raw, host, port, false)`（Android `HostHttp.kt:515`）
/// 那样的 API，也没有任何「把 TLS 叠到一段外来字节流上」的公开接口：
/// `NWConnection` 总是自己拥有一段 transport，`NWParameters` 没有「无 transport」的构造，
/// `NWProtocolFramer` 只能塑形真实 transport 的字节、不能当栈底。
///
/// 所以唯一的做法是：在本机 `127.0.0.1` 上暴露一个**真实的裸字节端点**，让系统 TLS 栈
/// 有东西可连，再把它的字节泵进 DLP/1 隧道。
///
/// ## 与 Android 的对应关系
///
/// Android 的 `openGateway()` 在回环上开 `ServerSocket`(`peer`) 与 `Socket`(`local`)，
/// 两端用一次性 32 字节 secret 认亲；`local` 交给 OkHttp 当「裸 socket」，
/// `peer` 负责与隧道对泵。
///
/// 本实现**只需要一半**：`NWListener` 接受的那条连接本身就是 TLS 栈要连的端点，
/// 直接与隧道对泵即可。Android 需要 secret 是因为它的 `local` 与 `peer` 是两个独立的
/// JVM socket、必须在中间确认「连上来的确实是我自己」；而这里监听只绑回环、
/// 且生命周期与隧道严格绑定，不存在需要甄别的第三方，因此**不做 secret 认亲**，
/// 少一次往返也少一处可出错的握手。
///
/// ## 它不是信任边界
///
/// 桥只绑 `127.0.0.1`、只接受一条连接、随隧道关闭（不留常驻监听端口）。
/// **身份保证完全由隧道之上的内层 TLS 证书钉扎承担**（§4.3），桥不做任何信任判断。
final class LoopbackTunnelBridge: @unchecked Sendable {
    /// 等 TLS 端连上来的超时。
    static let acceptTimeout: TimeInterval = 5

    /// 本机端点端口。`NWListener` 只在到达 `.ready` 之后才有有效端口，
    /// 因此 `start()` 返回前才可用。
    private(set) var port: UInt16 = 0

    private let listener: NWListener
    private let tunnel: any RemoteTunnel
    private let queue = DispatchQueue(label: "dev.deeplinks.remote.bridge")
    private let closed = OSAllocatedUnfairLock<Bool>(initialState: false)
    /// 内层 TLS 栈连过来的那条连接。
    private let peer = OSAllocatedUnfairLock<NWConnection?>(initialState: nil)
    /// `listen()` 建立的接受流；`acceptAndPump()` 消费它。
    private let acceptedStream = OSAllocatedUnfairLock<AsyncStream<NWConnection>?>(initialState: nil)

    /// - Parameters:
    ///   - tunnel: 已 `ready` 的 DLP/1 隧道。
    ///   - port: 监听端口；测试可指定，生产传 0 由系统分配。
    init(tunnel: any RemoteTunnel, port: UInt16 = 0) throws {
        self.tunnel = tunnel

        // 只绑回环：不接受任何外部连接。
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(
            host: .ipv4(.loopback), port: NWEndpoint.Port(rawValue: port) ?? .any)
        parameters.allowLocalEndpointReuse = false
        self.listener = try NWListener(using: parameters)
    }

    /// 启动监听并等它进入 `.ready`，返回真实端口。
    ///
    /// 与 `acceptAndPump()` 分开是**必要的**：调用方必须先拿到端口、发起 TLS 连接，
    /// 桥才可能接受到它。合成一个方法会造成「桥等连接、连接等桥」的互等。
    @discardableResult
    func listen() async throws -> UInt16 {
        let accepted = AsyncStream<NWConnection>.makeStream()
        acceptedStream.withLock { $0 = accepted.stream }

        // 顺序是硬要求：`stateUpdateHandler` 必须在 `start()` **之前**装好。
        // NWListener 不是「有订阅者才推状态」，而是状态一到就调当时的 handler，
        // 没有 handler 就丢掉。回环 TCP 监听的 `.ready` 常在几毫秒内到达，
        // 若等到 start() 之后再装，这个 `.ready` 会被永久丢弃，后面就永远等不到。
        // （`LocalGatewayAuthTests.readyPort` 也是先装 handler 再 start。）
        let states = AsyncStream<NWListener.State>.makeStream()
        listener.stateUpdateHandler = { states.continuation.yield($0) }
        listener.newConnectionHandler = { connection in
            connection.start(queue: self.queue)
            accepted.continuation.yield(connection)
        }

        listener.start(queue: queue)

        // 端口必须等 `.ready` 才有效——构造时读 `listener.port` 会拿到 nil。
        let boundPort = try await Self.waitReady(listener, states: states.stream, timeout: Self.acceptTimeout)
        self.port = boundPort
        return boundPort
    }

    /// 等内层 TLS 端连上来，然后开始双向对泵。
    func acceptAndPump() async throws {
        guard !isClosed else { throw RemoteTunnelError.cancelled }
        guard let stream = acceptedStream.withLock({ $0 }) else {
            throw RemoteTunnelError.transport("bridge listen() was not called")
        }
        guard let first = await Self.firstConnection(stream, timeout: Self.acceptTimeout) else {
            close()
            throw RemoteTunnelError.transport("bridge did not accept a connection")
        }
        guard !isClosed else {
            first.cancel()
            throw RemoteTunnelError.cancelled
        }
        peer.withLock { $0 = first }
        pumpDownstream(peer: first)
        pumpUpstream(peer: first)
    }

    /// 等监听进入 `.ready`，返回实际端口。
    ///
    /// - Parameter states: 由调用方在 `listener.start()` **之前**接好 handler 的状态流；
    ///   见 `listen()` 里的顺序说明。
    private static func waitReady(
        _ listener: NWListener, states: AsyncStream<NWListener.State>, timeout: TimeInterval
    ) async throws -> UInt16 {
        try await withThrowingTaskGroup(of: UInt16.self) { group in
            group.addTask {
                for await state in states {
                    switch state {
                    case .ready:
                        guard let port = listener.port?.rawValue else {
                            throw RemoteTunnelError.transport("bridge listener has no port after ready")
                        }
                        return port
                    case .failed(let error):
                        throw NWRemoteTunnel.mapNWError(error)
                    default:
                        continue
                    }
                }
                throw RemoteTunnelError.transport("bridge listener stopped before ready")
            }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                throw RemoteTunnelError.transport("bridge listener did not become ready")
            }
            defer { group.cancelAll() }
            guard let first = try await group.next() else {
                throw RemoteTunnelError.transport("bridge listener did not become ready")
            }
            return first
        }
    }

    /// 下行：隧道 → 桥对端（内层 TLS 栈）。
    private func pumpDownstream(peer: NWConnection) {
        Task { [weak self] in
            guard let self else { return }
            do {
                for try await bytes in self.tunnel.incoming {
                    if self.isClosed { return }
                    try await Self.send(bytes, to: peer)
                }
                self.close()
            } catch {
                self.close()
            }
        }
    }

    /// 上行：桥对端（内层 TLS 栈）→ 隧道。
    ///
    /// 背压：`tunnel.write` 写不出去时会挂住，此时不再 `receive`，背压沿回环连接
    /// 自然传回 TLS 栈（与 Android `pumpUpstream` 的 `queueSize() > 1 MiB` 等待同义）。
    private func pumpUpstream(peer: NWConnection) {
        Task { [weak self] in
            guard let self else { return }
            while !self.isClosed {
                do {
                    guard let chunk = try await Self.receiveChunk(from: peer), !chunk.isEmpty else {
                        self.close()
                        return
                    }
                    guard !self.isClosed else { return }
                    try await self.tunnel.write(chunk)
                } catch {
                    self.close()
                    return
                }
            }
        }
    }

    func close() {
        guard markClosed() else { return }
        listener.cancel()
        peer.withLock { connection in
            connection?.cancel()
            connection = nil
        }
    }

    var isClosed: Bool { closed.withLock { $0 } }

    private func markClosed() -> Bool {
        closed.withLock { current in
            if current { return false }
            current = true
            return true
        }
    }

    // MARK: - 静态工具

    /// 从流里取第一条连接；超时返回 nil。
    private static func firstConnection(_ stream: AsyncStream<NWConnection>, timeout: TimeInterval) async
        -> NWConnection?
    {
        await withTaskGroup(of: NWConnection?.self) { group in
            group.addTask {
                var iterator = stream.makeAsyncIterator()
                return await iterator.next()
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                return nil
            }
            defer { group.cancelAll() }
            return await group.next() ?? nil
        }
    }

    static func receiveChunk(from connection: NWConnection) async throws -> Data? {
        try await withCheckedThrowingContinuation { continuation in
            connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, error in
                if let error {
                    continuation.resume(throwing: NWRemoteTunnel.mapNWError(error))
                    return
                }
                if let data, !data.isEmpty {
                    continuation.resume(returning: data)
                    return
                }
                continuation.resume(returning: isComplete ? nil : Data())
            }
        }
    }

    static func send(_ bytes: Data, to connection: NWConnection) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
            connection.send(
                content: bytes,
                completion: .contentProcessed { error in
                    if let error {
                        continuation.resume(throwing: NWRemoteTunnel.mapNWError(error))
                    } else {
                        continuation.resume()
                    }
                })
        }
    }
}
