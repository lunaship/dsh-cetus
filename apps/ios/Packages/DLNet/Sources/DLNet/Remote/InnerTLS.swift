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

    /// TLS 解密后的明文字节（HTTP/1.1 直接读这里）。
    let incoming: AsyncThrowingStream<Data, any Error>

    private init(connection: NWConnection, bridge: LoopbackTunnelBridge) {
        self.connection = connection
        self.bridge = bridge
        let (stream, cont) = AsyncThrowingStream<Data, any Error>.makeStream(bufferingPolicy: .unbounded)
        self.incoming = stream
        self.continuation = cont
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
        // 端口在 start() 返回后才有效。
        let bridgePort = try await bridge.start()

        let tlsOptions = NWProtocolTLS.Options()
        NWRemoteTunnelTransport.installPin(tlsOptions, expected: pin)
        sec_protocol_options_set_tls_server_name(tlsOptions.securityProtocolOptions, host)

        let parameters = NWParameters(tls: tlsOptions, tcp: .init())
        let connection = NWConnection(
            host: NWEndpoint.Host("127.0.0.1"),
            port: NWEndpoint.Port(rawValue: bridgePort)!,
            using: parameters)

        let channel = InnerTLSChannel(connection: connection, bridge: bridge)
        do {
            try await channel.start()
        } catch {
            await channel.close()
            throw error
        }
        return channel
    }

    private func start() async throws {
        let ready = AsyncStream<Void>.makeStream()
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready: ready.continuation.yield()
            case .failed, .cancelled: ready.continuation.finish()
            default: break
            }
        }
        connection.start(queue: queue)

        guard await Self.first(ready.stream, timeout: Self.handshakeTimeout) else {
            // 握手失败（含指纹不匹配：verify_block 拒绝后连接进 .failed）。
            throw CertificatePinError.certificateChanged
        }
        startReading()
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
