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
/// 所以唯一的做法是：在本机 `127.0.0.1` 上暴露一个**真实的裸字节端点**（让系统 TLS 栈
/// 有东西可连），再把它的字节泵进 DLP/1 隧道。Android `WebSocketTunnelSocketFactory`
/// 的 `openGateway()` 用的是同一个模式，只是它用裸 `ServerSocket`/`Socket`。
///
/// ## 它不是信任边界
///
/// 桥只绑回环、只接受**一条**连接、用一次性随机 secret 认亲，生命周期与隧道严格绑定
/// （隧道关闭 → 桥立即关闭，不留常驻监听端口）。**身份保证完全由隧道之上的内层 TLS
/// 证书钉扎承担**（§4.3），桥本身不做任何信任判断——这一点与 Android 完全一致。
final class LoopbackTunnelBridge: @unchecked Sendable {
    /// 认亲 secret 长度，照 Android `openGateway` 的 32 字节。
    static let secretBytes = 32
    /// 认亲超时。
    static let claimTimeout: TimeInterval = 5

    /// 本机端点端口：上层用它去建内层 TLS 连接。
    let port: UInt16

    private let listener: NWListener
    private let secret: Data
    private let tunnel: any RemoteTunnel
    private let queue = DispatchQueue(label: "dev.deeplinks.remote.bridge")
    private let closed = OSAllocatedUnfairLock<Bool>(initialState: false)
    /// 认亲后的对端（即内层 TLS 栈连过来的那条裸连接）。
    private let peer = OSAllocatedUnfairLock<NWConnection?>(initialState: nil)

    /// - Parameters:
    ///   - tunnel: 已 `ready` 的 DLP/1 隧道。
    ///   - port: 监听端口；测试可指定，生产传 0 由系统分配。
    init(tunnel: any RemoteTunnel, port: UInt16 = 0) throws {
        self.tunnel = tunnel

        var secret = Data(count: Self.secretBytes)
        let status = secret.withUnsafeMutableBytes {
            SecRandomCopyBytes(kSecRandomDefault, Self.secretBytes, $0.baseAddress!)
        }
        guard status == errSecSuccess else {
            throw RemoteTunnelError.transport("random source unavailable")
        }
        self.secret = secret

        // 只绑回环：不接受任何外部连接。
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: NWEndpoint.Port(rawValue: port) ?? .any)
        parameters.allowLocalEndpointReuse = false
        let listener = try NWListener(using: parameters)
        self.listener = listener

        guard let bound = listener.port else {
            throw RemoteTunnelError.transport("bridge listener has no port")
        }
        self.port = bound.rawValue
    }

    /// 启动监听并等第一条（也是唯一一条）连接完成认亲。
    func start() async throws {
        let accepted = AsyncStream<NWConnection>.makeStream()

        listener.newConnectionHandler = { connection in
            connection.start(queue: self.queue)
            accepted.continuation.yield(connection)
        }
        listener.stateUpdateHandler = { state in
            if case .failed = state { accepted.continuation.finish() }
        }
        listener.start(queue: queue)

        // 只接受第一条；其余立即断开（回环上不该有第二个客户端）。
        guard let first = await Self.firstConnection(accepted.stream, timeout: Self.claimTimeout) else {
            close()
            throw RemoteTunnelError.transport("bridge did not accept a connection")
        }
        do {
            try await claim(first)
        } catch {
            first.cancel()
            close()
            throw error
        }
        peer.withLock { $0 = first }
        pumpDownstream(peer: first)
        pumpUpstream(peer: first)
    }

    /// 认亲：对端必须先发一个和 secret 完全一致的 32 字节前缀，之后才是 TLS 记录。
    ///
    /// 照 Android `openGateway` 的 `MessageDigest.isEqual(secret, received)` 语义。
    /// 用常量时间比较，避免在回环上泄露 secret 的比对进度。
    private func claim(_ connection: NWConnection) async throws {
        let received = try await Self.receiveExactly(Self.secretBytes, from: connection, timeout: Self.claimTimeout)
        guard DlpCryptoConstantTime.equal(received, secret) else {
            throw RemoteTunnelError.transport("bridge authentication failed")
        }
    }

    /// 下行：隧道 → 桥对端（即 TLS 栈）。
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

    /// 上行：桥对端（TLS 栈）→ 隧道。
    ///
    /// 背压：`tunnel.write` 在隧道写不出去时会挂住，此时不再继续 `receive`，
    /// 背压沿回环连接自然传回 TLS 栈（与 Android `pumpUpstream` 的
    /// 「`queueSize() > 1 MiB` 就等」同义，只是这里由 TCP 自身兜住）。
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
    private static func firstConnection(_ stream: AsyncStream<NWConnection>, timeout: TimeInterval) async -> NWConnection? {
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

    /// 读满 `count` 字节。
    private static func receiveExactly(_ count: Int, from connection: NWConnection, timeout: TimeInterval) async throws -> Data {
        try await withThrowingTaskGroup(of: Data.self) { group in
            group.addTask {
                var collected = Data()
                while collected.count < count {
                    let chunk = try await receiveChunk(from: connection)
                    guard let chunk, !chunk.isEmpty else {
                        throw RemoteTunnelError.transport("bridge closed during authentication")
                    }
                    collected.append(chunk)
                }
                return collected
            }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                throw RemoteTunnelError.transport("bridge authentication timed out")
            }
            defer { group.cancelAll() }
            guard let first = try await group.next() else {
                throw RemoteTunnelError.transport("bridge authentication failed")
            }
            return first
        }
    }

    private static func receiveChunk(from connection: NWConnection) async throws -> Data? {
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
            connection.send(content: bytes, completion: .contentProcessed { error in
                if let error {
                    continuation.resume(throwing: NWRemoteTunnel.mapNWError(error))
                } else {
                    continuation.resume()
                }
            })
        }
    }
}

/// 常量时间比较（认亲 secret）。
enum DlpCryptoConstantTime {
    static func equal(_ lhs: Data, _ rhs: Data) -> Bool {
        guard lhs.count == rhs.count else { return false }
        var diff: UInt8 = 0
        for index in lhs.indices { diff |= lhs[index] ^ rhs[index] }
        return diff == 0
    }
}
