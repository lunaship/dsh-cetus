import DLRemote
import DLSecurity
import Foundation
import Network
import os

/// 基于 `NWConnection` 的 DLP/1 数据隧道（G4.1，方案 A″）。
///
/// 为什么不是 `URLProtocol`：`docs/ios/G4.0-review.md` 选 URLProtocol 的前提是
/// 「隧道那头本来就是 HTTP，不需要假装自己是 socket」。但 RFC 0001 §4.2 第 6 步要求
/// **手机在管道上做内层 TLS 并钉扎插件证书**，而 `URLProtocol` 跳过了 `URLSession`
/// 的真实建连，iOS 又没有 `SSLSocketFactory.createSocket(raw, host, port, false)`
/// （Android `HostHttp.kt:515` 用的那个）的等价物——无法在已有字节流上叠 TLS。
///
/// `NWConnection` 提供完整的协议栈（`NWProtocolWebSocket` + `NWProtocolTLS`），
/// 内层 TLS 用 `sec_protocol_options_set_verify_block` 做「只认配对时记录的叶证书指纹」，
/// 判定逻辑复用 `DLSecurity.PinEvaluation`，不重写指纹算法。
///
/// 本类型负责**外层 WSS 的会合**（§5.4.3）与**内层 TLS**（§4.2 第 6 步），产出一条
/// 可在其上跑 HTTP/1.1 的字节通道。
public final class NWRemoteTunnel: RemoteTunnel, @unchecked Sendable {
    /// 下行最多缓冲多少段；到顶后读循环停下（背压），不再从隧道取数据。
    public static let defaultCapacity = 32

    private let connection: NWConnection
    private let queue = DispatchQueue(label: "dev.deeplinks.remote.nw-tunnel")
    private let capacity: Int
    private let closed = OSAllocatedUnfairLock<Bool>(initialState: false)
    private let continuation: AsyncThrowingStream<Data, any Error>.Continuation

    /// 内层 TLS 之上的字节流（HTTP/1.1 直接读这里）。
    public let incoming: AsyncThrowingStream<Data, any Error>

    init(connection: NWConnection, capacity: Int = NWRemoteTunnel.defaultCapacity) {
        self.connection = connection
        self.capacity = max(1, capacity)
        // 有界缓冲：下游不读时 `yield` 会返回 `.dropped`，读循环据此停手（背压）。
        // 用有界策略而不是自己记账，是因为 `RemoteTunnel` 只暴露一个只读流，
        // 下游没有回调可以归还额度——自建额度必然漏放，最终卡死。
        let (stream, cont) = AsyncThrowingStream<Data, any Error>.makeStream(
            bufferingPolicy: .bufferingNewest(self.capacity))
        self.incoming = stream
        self.continuation = cont
        onTermination()
        // 构造即开始接收：隧道一旦就绪就应把数据交给下游。
        //（漏掉这一步会让 incoming 永远不产出，而调用方无从知道该调它。）
        startReceiving()
    }

    /// 下游取消读取时关闭整条隧道（没有半关闭语义）。
    private func onTermination() {
        continuation.onTermination = { [weak self] _ in
            guard let self else { return }
            Task { await self.close() }
        }
    }

    /// 读循环：一条 WebSocket 消息 → 一段下行字节。
    ///
    /// 背压：`yield` 在有界缓冲满时返回 `.dropped`，此时读循环立即停止，
    /// 不再从隧道 `receiveMessage`；下游恢复读取后由新的隧道建立重试
    /// （RFC §5.4.4 的「写不出去就不再读」同义）。
    private func startReceiving() {
        Task { [weak self] in
            guard let self else { return }
            while !self.isClosed {
                let message: Data
                do {
                    guard let received = try await self.receiveMessage() else { return }
                    message = received
                } catch {
                    self.finish(throwing: error)
                    return
                }
                if self.isClosed { return }
                // `.dropped` / `.terminated` 都表示下游不再消费：停手，不无界积压。
                if case .dropped = self.continuation.yield(message) {
                    self.finish(throwing: RemoteTunnelError.transport("downstream stopped reading"))
                    return
                }
            }
        }
    }

    private func receiveMessage() async throws -> Data? {
        try await withCheckedThrowingContinuation { continuation in
            connection.receiveMessage { data, context, isComplete, error in
                if let error {
                    continuation.resume(throwing: Self.mapNWError(error))
                    return
                }
                // 只接受二进制帧（RFC §5.4.4）。
                let opcode =
                    context?.protocolMetadata(definition: NWProtocolWebSocket.definition)
                    as? NWProtocolWebSocket.Metadata
                if let opcode, opcode.opcode == .text {
                    continuation.resume(throwing: RemoteTunnelError.protocolViolation)
                    return
                }
                if let data, !data.isEmpty {
                    guard DlpWire.isValidDataMessage(data) else {
                        continuation.resume(throwing: RemoteTunnelError.protocolViolation)
                        return
                    }
                    continuation.resume(returning: data)
                    return
                }
                if isComplete {
                    continuation.resume(returning: nil)
                    return
                }
                continuation.resume(returning: Data())
            }
        }
    }

    // MARK: - RemoteTunnel

    public func write(_ bytes: Data) async throws {
        guard !bytes.isEmpty else { return }
        guard !isClosed else { throw RemoteTunnelError.cancelled }
        for chunk in DlpWire.chunkData(bytes) {
            try await send(chunk)
        }
    }

    private func send(_ bytes: Data) async throws {
        let metadata = NWProtocolWebSocket.Metadata(opcode: .binary)
        let context = NWConnection.ContentContext(identifier: "dlp1-data", metadata: [metadata])
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
            connection.send(
                content: bytes, contentContext: context, isComplete: true,
                completion: .contentProcessed { error in
                    if let error {
                        continuation.resume(throwing: Self.mapNWError(error))
                    } else {
                        continuation.resume()
                    }
                })
        }
    }

    public var isClosed: Bool { closed.withLock { $0 } }

    public func close() async {
        guard markClosed() else { return }
        continuation.finish()
        connection.cancel()
    }

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
    }

    static func mapNWError(_ error: any Error) -> RemoteTunnelError {
        if let tunnel = error as? RemoteTunnelError { return tunnel }
        guard let nwError = error as? NWError else { return .transport(String(describing: error)) }
        switch nwError {
        case .posix(let code):
            if code == .ECANCELED { return .cancelled }
            return .transport("posix \(code.rawValue)")
        case .tls(let status):
            return .transport("tls \(status)")
        case .dns:
            return .relayUnreachable
        @unknown default:
            return .transport(String(describing: nwError))
        }
    }
}
