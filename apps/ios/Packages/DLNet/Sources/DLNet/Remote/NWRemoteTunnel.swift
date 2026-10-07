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
    /// 下行在途上限（背压）：读满 `capacity` 段且下游未消费时停止继续接收。
    public static let defaultCapacity = 32

    private let connection: NWConnection
    private let queue = DispatchQueue(label: "dev.deeplinks.remote.nw-tunnel")
    private let credits: BackpressureCredits
    private let closed = OSAllocatedUnfairLock<Bool>(initialState: false)
    private let continuation: AsyncThrowingStream<Data, any Error>.Continuation

    /// 内层 TLS 之上的字节流（HTTP/1.1 直接写这里）。
    public let incoming: AsyncThrowingStream<Data, any Error>

    init(connection: NWConnection, capacity: Int = NWRemoteTunnel.defaultCapacity) {
        self.connection = connection
        self.credits = BackpressureCredits(capacity: capacity)
        let (stream, cont) = AsyncThrowingStream<Data, any Error>.makeStream(bufferingPolicy: .unbounded)
        self.incoming = stream
        self.continuation = cont
    }

    /// 读完一条 WebSocket 消息并交给下游。
    func startReceiving() {
        Task { [weak self] in
            guard let self else { return }
            while !self.isClosed {
                // 背压：先取额度再收下一条；下游不读就停在这里，不再 receiveMessage。
                guard await self.credits.take(timeout: 60) else {
                    if !self.isClosed { self.finish(throwing: RemoteTunnelError.transport("downstream stalled")) }
                    return
                }
                if self.isClosed { return }
                do {
                    guard let message = try await self.receiveMessage() else { return }
                    self.continuation.yield(message)
                } catch {
                    self.finish(throwing: error)
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

    /// 下游消费一段后归还额度。
    func releaseCredit() { credits.give() }

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
        credits.wakeAll()
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
        credits.wakeAll()
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

/// 有界在途额度：读循环 `take`，下游消费后 `give`。
///
/// 与 Android「写不进就停读」同义；下游不读时读循环停在 `take` 上，不无界排队。
final class BackpressureCredits: @unchecked Sendable {
    private let capacity: Int
    private let lock = OSAllocatedUnfairLock<Int>(initialState: 0)

    init(capacity: Int) {
        let value = max(1, capacity)
        self.capacity = value
        lock.withLock { $0 = value }
    }

    func take(timeout: TimeInterval) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while true {
            let granted = lock.withLock { available -> Bool in
                if available > 0 {
                    available -= 1
                    return true
                }
                return false
            }
            if granted { return true }
            if Date() >= deadline { return false }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
    }

    func give() {
        lock.withLock { available in
            if available < capacity { available += 1 }
        }
    }

    func wakeAll() {
        lock.withLock { $0 = capacity }
    }
}
