import DLSecurity
import Foundation
import Network
import Security
import os

/// 内层 TLS 客户端：连回环桥，用系统 TLS 完成到**插件 HTTPS 服务**的端到端握手（RFC §4.2 第 6 步）。
///
/// 链路：`本类型(NWConnection + NWProtocolTLS)` → `127.0.0.1:bridgePort` → `回环桥` → `DLP/1 隧道`
/// → 电脑端 Agent → `127.0.0.1:pluginPort` 的插件 HTTPS 服务。
/// 也就是说 **TLS 是端到端穿过隧道的**，回环桥只是字节中转，不参与 TLS。
///
/// 证书校验把 `sec_protocol_options_set_verify_block` **完全替换**成
/// 「叶证书 SHA-256 == 配对时记录的指纹」，判定走 `PinEvaluation`（复用，不重写算法）。
/// 指纹缺失或非法一律 fail-closed，不回退系统 PKI。
final class InnerTLSChannel: TunnelByteChannel, @unchecked Sendable {
    /// 握手超时。
    static let handshakeTimeout: TimeInterval = 10

    private let connection: NWConnection
    private let bridge: LoopbackTunnelBridge
    private let queue = DispatchQueue(label: "dev.deeplinks.remote.inner-tls")
    private let closed = OSAllocatedUnfairLock<Bool>(initialState: false)
    private let continuation: AsyncThrowingStream<Data, any Error>.Continuation
    /// 证书钉扎是否已拒绝。由 `verify_block` 直接置位。
    ///
    /// 不能只等 `NWConnection` 的 `.failed`：实测拒绝后它**不发任何 state 回调**，
    /// 只等 `.failed` 会把「指纹不符（硬停止）」错报成「握手超时（可重试）」。
    private let rejection: RejectionFlag
    /// TLS 握手是否已就绪（由 `stateUpdateHandler` 置位）。
    private let handshakeReady = ReadinessFlag()

    /// TLS 解密后的明文字节（HTTP/1.1 直接读这里）。
    let incoming: AsyncThrowingStream<Data, any Error>

    private init(connection: NWConnection, bridge: LoopbackTunnelBridge, rejection: RejectionFlag) {
        self.connection = connection
        self.bridge = bridge
        self.rejection = rejection
        let (stream, cont) = AsyncThrowingStream<Data, any Error>.makeStream(bufferingPolicy: .unbounded)
        self.incoming = stream
        self.continuation = cont
    }

    /// `verify_block` 与 channel 之间传递「已拒绝」的最小载体。
    ///
    /// verify_block 是在 `NWConnection` 创建前装的，那时 channel 还不存在，
    /// 所以用一个独立的小对象转交，避免让 pin 回调持有 channel（会造成循环引用）。
    final class RejectionFlag: @unchecked Sendable {
        private let lock = OSAllocatedUnfairLock<Bool>(initialState: false)
        func mark() { lock.withLock { $0 = true } }
        var isRejected: Bool { lock.withLock { $0 } }
    }

    /// 一次性标记：握手就绪 / 连接终止。
    ///
    /// 不用 `AsyncStream` 是因为等待循环要反复查询状态，而 `AsyncStream` 是
    /// 消费型的——多迭代器会互相抢元素。
    final class ReadinessFlag: @unchecked Sendable {
        private struct State {
            var marked = false
            var closed = false
        }
        private let lock = OSAllocatedUnfairLock<State>(initialState: State())
        func mark() { lock.withLock { $0.marked = true } }
        func markClosed() { lock.withLock { $0.closed = true } }
        var isMarked: Bool { lock.withLock { $0.marked } }
        var isClosed: Bool { lock.withLock { $0.closed } }
    }

    /// 建立内层 TLS。
    ///
    /// - Parameters:
    ///   - tunnel: 已 `ready` 的 DLP/1 隧道。
    ///   - host: 内层 SNI / 主机名（插件证书上的名字）。
    ///   - expectedFingerprint: 配对时记录的插件叶证书指纹（64 位小写 hex）。
    static func open(
        tunnel: any RemoteTunnel,
        host: String,
        expectedFingerprint: String?
    ) async throws -> InnerTLSChannel {
        let pin = CertificateFingerprint.normalize(expectedFingerprint)
        // 没有合法指纹：不允许建立内层 TLS，也不回退系统 PKI（§4.3 fail-closed）。
        guard CertificateFingerprint.isValid(pin) else {
            throw CertificatePinError.certificateChanged
        }

        let bridge = try LoopbackTunnelBridge(tunnel: tunnel)
        // 顺序很重要：先让监听到 ready 拿到端口，再建 TLS 连接，最后才等桥接受。
        // 若先 `await bridge.start()`（它会等 TLS 端连上来）再建连接，就会永远互等。
        let bridgePort = try await bridge.listen()

        // 延迟绑定：channel 建好后再把「拒绝」回调接上去（见下）。
        let rejection = RejectionFlag()
        let tlsOptions = NWProtocolTLS.Options()
        NWRemoteTunnelTransport.installPin(tlsOptions, expected: pin) {
            rejection.mark()
        }
        sec_protocol_options_set_tls_server_name(tlsOptions.securityProtocolOptions, host)

        let parameters = NWParameters(tls: tlsOptions, tcp: .init())
        let connection = NWConnection(
            host: NWEndpoint.Host("127.0.0.1"),
            port: NWEndpoint.Port(rawValue: bridgePort)!,
            using: parameters)

        let channel = InnerTLSChannel(connection: connection, bridge: bridge, rejection: rejection)
        do {
            // 三步顺序都是硬要求：
            // 1. 发起 TLS 连接（构造 `NWConnection` 只是建对象，**不等于**发起；
            //    真正发起是 `connection.start()`）。
            // 2. 桥才可能接受到它。
            // 3. 最后等 TLS 握手完成。
            // 若把 1 放在 2 之后，桥就在等一个还没发起的连接，必然超时。
            channel.beginConnecting()
            try await bridge.acceptAndPump()
            try await channel.awaitHandshake()
        } catch {
            await channel.close()
            throw error
        }
        return channel
    }

    /// 发起连接；`ready` 由 `handshakeReady` 标记反映。
    ///
    /// `stateUpdateHandler` 必须在 `connection.start()` **之前**装好：
    /// Network.framework 的状态回调不会追溯补发，错过的状态就丢了
    /// （`NWListener` 的 `.ready` 同理，见 `LoopbackTunnelBridge.listen()`）。
    ///
    /// 用标记而不是 `AsyncStream`：`AsyncStream` 是消费型的，而这里需要在
    /// 等待循环里反复查询「到了没」，多迭代器会互相抢元素。
    private func beginConnecting() {
        let flag = handshakeReady
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready: flag.mark()
            case .failed, .cancelled: flag.markClosed()
            default: break
            }
        }
        connection.start(queue: queue)
    }

    /// 等 TLS 握手完成（含证书钉扎判定）。
    ///
    /// 失败分两种，必须区分：
    /// - **指纹不符**：`verify_block` 已置位 `rejection`。这是 4.3 的硬停止，
    ///   抛 `CertificatePinError`（上层不得重试、不得删凭据）。
    /// - **握手超时**：同样抛 `CertificatePinError`（fail-closed），但语义上是
    ///   「没连上」。这里不引入新的错误类型，保持调用方只需处理一种失败。
    ///
    /// 轮询 `rejection` 是必要的：`verify_block` 拒绝后 `NWConnection` 不发
    /// 任何 state 回调，光等 `ready` 流会一直挂到超时，把「被拒」伪装成「超时」。
    private func awaitHandshake() async throws {
        let deadline = Date().addingTimeInterval(Self.handshakeTimeout)
        while Date() < deadline {
            if rejection.isRejected { throw CertificatePinError.certificateChanged }
            if handshakeReady.isMarked {
                startReading()
                return
            }
            if handshakeReady.isClosed { throw CertificatePinError.certificateChanged }
            try? await Task.sleep(nanoseconds: 10_000_000)  // 10ms
        }
        throw CertificatePinError.certificateChanged
    }

    /// 读 TLS 明文。
    private func startReading() {
        Task { [weak self] in
            guard let self else { return }
            while !self.isClosed {
                do {
                    guard let chunk = try await Self.receiveChunk(from: self.connection) else {
                        self.finish(throwing: nil)
                        return
                    }
                    if !chunk.isEmpty { self.continuation.yield(chunk) }
                } catch {
                    self.finish(throwing: NWRemoteTunnel.mapNWError(error))
                    return
                }
            }
        }
    }

    func write(_ bytes: Data) async throws {
        guard !isClosed else { throw RemoteTunnelError.cancelled }
        try await LoopbackTunnelBridge.send(bytes, to: connection)
    }

    func close() async {
        guard markClosed() else { return }
        continuation.finish()
        connection.cancel()
        bridge.close()
    }

    private var isClosed: Bool { closed.withLock { $0 } }

    private func markClosed() -> Bool {
        closed.withLock { current in
            if current { return false }
            current = true
            return true
        }
    }

    private func finish(throwing error: (any Error)?) {
        guard markClosed() else { return }
        continuation.finish(throwing: error)
        connection.cancel()
        bridge.close()
    }

    // MARK: - 工具

    private static func first(_ stream: AsyncStream<Void>, timeout: TimeInterval) async -> Bool {
        await withTaskGroup(of: Bool.self) { group in
            group.addTask {
                var iterator = stream.makeAsyncIterator()
                return await iterator.next() != nil
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                return false
            }
            defer { group.cancelAll() }
            return await group.next() ?? false
        }
    }

    static func receiveChunk(from connection: NWConnection) async throws -> Data? {
        try await withCheckedThrowingContinuation { continuation in
            connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, error in
                if let error {
                    continuation.resume(throwing: error)
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
}
