import DLNet
import DLSecurity
import Foundation
import Network
import Security
import Testing

@testable import DLNet

/// C11 运输准入 spike：**URLSession 直连 `LoopbackTunnelBridge` 是否可行？**
///
/// RFC §15.1 第 2 条的字面要求是「内层由 URLSession 做 TLS 和固定证书校验」。
/// 本 spike 实测该路线，并在不可行时给出**可引用的原因**（§15.1 第 6 条：
/// 「回环方案未达到准入就给出原因和替代 spike，不硬接」）。
///
/// 复用 `RemoteInnerTLSTests` 已验证的成分与仓库内 PKCS#12 fixture，不另造证书。
@Suite(.serialized) struct RemoteLoopbackAdmissionSpikeTests {
    // MARK: - 假隧道（隧道字节直连本机端口，跳过 DLP/1 加密）

    private final class DirectTunnel: RemoteTunnel, @unchecked Sendable {
        private let connection: NWConnection
        private let continuation: AsyncThrowingStream<Data, any Error>.Continuation
        let incoming: AsyncThrowingStream<Data, any Error>

        init(port: UInt16) {
            let (stream, cont) = AsyncThrowingStream<Data, any Error>.makeStream(bufferingPolicy: .unbounded)
            incoming = stream
            continuation = cont
            connection = NWConnection(
                host: NWEndpoint.Host("127.0.0.1"), port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
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
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, any Error>) in
                connection.send(
                    content: bytes,
                    completion: .contentProcessed { error in
                        if let error { cont.resume(throwing: error) } else { cont.resume() }
                    })
            }
        }

        func close() async { connection.cancel() }
    }

    // MARK: - 本机 TLS 服务（模拟插件 18640）

    private final class FakeTLSServer: @unchecked Sendable {
        private var listener: NWListener?
        private let lock = NSLock()
        private var completed = 0
        var completedRequests: Int { lock.withLock { completed } }

        func start(identity: sec_identity_t) async throws -> UInt16 {
            let tls = NWProtocolTLS.Options()
            sec_protocol_options_set_local_identity(tls.securityProtocolOptions, identity)
            sec_protocol_options_set_peer_authentication_required(tls.securityProtocolOptions, false)
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
            let request = await withCheckedContinuation { continuation in
                connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { data, _, _, _ in
                    continuation.resume(returning: data ?? Data())
                }
            }
            guard !request.isEmpty else {
                connection.cancel()
                return
            }
            let body = #"{"via":"spike","ok":true}"#
            let response =
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n\(body)"
            await withCheckedContinuation { continuation in
                connection.send(
                    content: Data(response.utf8), completion: .contentProcessed { _ in continuation.resume() })
            }
            lock.withLock { completed += 1 }
            connection.cancel()
        }
    }

    // MARK: - fixture（与 RemoteInnerTLSTests 同一份 PKCS#12）

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

    final class BundleAnchor {}

    // MARK: - Spike A：带**钉扎 delegate** 的 URLSession 直连桥端口

    /// 这一组是为了**诚实地检验**「URLSession + 固定证书校验」这条路是否可行。
    ///
    /// 注意：第一版 spike 用的是 `URLSession(configuration: .ephemeral)`（**没有**钉扎 delegate），
    /// 它必然以 `-1202`（证书无效）失败 —— 但那只能说明「默认 session 不接受自签证书」，
    /// **不能**说明钉扎方案不可行。带 `PinnedSessionDelegate` 的 session 在 serverTrust
    /// challenge 里可以放行自签证书。所以这里换成真实 delegate 重测。
    @Test func pinnedURLSessionThroughBridgeRecordsAdmissionEvidence() async throws {
        let fixture = try Self.loadIdentity()
        let server = FakeTLSServer()
        let serverPort = try await server.start(identity: fixture.identity)
        defer { server.stop() }

        let tunnel = DirectTunnel(port: serverPort)
        let bridge = try LoopbackTunnelBridge(tunnel: tunnel)
        let bridgePort = try await bridge.listen()
        #expect(bridgePort != 0, "桥必须绑定到回环随机端口")

        let pump = Task { try await bridge.acceptAndPump() }
        let waiter = OneShotOutcome()

        // 用真实的钉扎 delegate，指纹取自 fixture 的叶证书（与打桩服务器同一张）。
        let fingerprint = CertificateFingerprint.sha256(der: fixture.der)
        let configuration = URLSessionConfiguration.ephemeral
        let session = URLSession(
            configuration: configuration,
            delegate: PinnedSessionDelegate(expectedFingerprint: fingerprint),
            delegateQueue: nil)

        let url = URL(string: "https://127.0.0.1:\(bridgePort)/dsh-link/mobile/bootstrap")!
        session.dataTask(with: url) { _, response, error in
            waiter.finish((response as? HTTPURLResponse)?.statusCode, error.map { "\($0)" })
        }.resume()

        let outcome = await waiter.wait(timeout: 6)
        let pinFailures = (session.delegate as? PinnedSessionDelegate)?.pinFailureCount ?? 0
        session.invalidateAndCancel()
        await bridge.close()
        _ = try? await pump.value

        // 证据无条件打到测试日志（断言消息只在失败时显示，不利于取证）。
        print(
            "SPIKE-A2 pinnedURLSessionStatus=\(outcome.status.map(String.init) ?? "nil") "
                + "error=\(outcome.error ?? "none") pinFailures=\(pinFailures) "
                + "pluginSawRequests=\(server.completedRequests)"
        )
        #expect(bridgePort != 0)

        // ---- 断言真正的结论，而不是只打日志 ----
        //
        // 这三条合起来是「带钉扎的 URLSession 能穿过回环桥与插件完成一次真实
        // 请求/响应」的完整证据。此前只有 `bridgePort != 0`，等于 spike 跑完不判定，
        // 结论只留在日志里 —— 谁也无法在 CI 上防止这条路悄悄坏掉。
        #expect(outcome.error == nil, "URLSession 报错（说明路线不可行或钉扎拒绝）：\(outcome.error ?? "")")
        #expect(outcome.status == 200, "未拿到 200，实际 status=\(outcome.status.map(String.init) ?? "nil")")
        #expect(pinFailures == 0, "钉扎失败 \(pinFailures) 次 —— 指纹应匹配 fixture 叶证书")
        #expect(
            server.completedRequests == 1,
            "插件侧应恰好收到 1 次请求，实际 \(server.completedRequests)（0 说明字节没送达）")
    }

    /// 桥是「一次一条连接」的设计，所以每个请求各配一条桥。
    /// 这里连做两次，证明该模式**可重复**（不是只能成功一次的巧合）。
    @Test func repeatedRequestsEachGetTheirOwnBridge() async throws {
        let fixture = try Self.loadIdentity()
        let server = FakeTLSServer()
        let serverPort = try await server.start(identity: fixture.identity)
        defer { server.stop() }

        let fingerprint = CertificateFingerprint.sha256(der: fixture.der)
        for attempt in 1...2 {
            let bridge = try LoopbackTunnelBridge(tunnel: DirectTunnel(port: serverPort))
            let bridgePort = try await bridge.listen()
            let pump = Task { try await bridge.acceptAndPump() }

            let session = URLSession(
                configuration: .ephemeral,
                delegate: PinnedSessionDelegate(expectedFingerprint: fingerprint),
                delegateQueue: nil)
            let waiter = OneShotOutcome()
            let url = URL(string: "https://127.0.0.1:\(bridgePort)/dsh-link/mobile/bootstrap")!
            session.dataTask(with: url) { _, response, error in
                waiter.finish((response as? HTTPURLResponse)?.statusCode, error.map { "\($0)" })
            }.resume()
            let outcome = await waiter.wait(timeout: 6)
            session.invalidateAndCancel()
            await bridge.close()
            _ = try? await pump.value

            #expect(outcome.status == 200, "第 \(attempt) 次请求失败：\(String(describing: outcome))")
        }
        #expect(server.completedRequests == 2, "两次请求都应送达插件，实际 \(server.completedRequests)")
    }

    // MARK: - Spike B：现有可用路线（HTTP over channel，无 URLSession）

    @Test func existingChannelRouteSpeaksHTTPWithoutURLSession() throws {
        let request = URLRequest(url: URL(string: "https://192.0.2.10:18640/dsh-link/mobile/bootstrap")!)
        let text = String(decoding: try HTTP1Wire.requestData(for: request, mode: .close), as: UTF8.self)
        #expect(text.hasPrefix("GET /dsh-link/mobile/bootstrap HTTP/1.1\r\n"))
        #expect(text.contains("Connection: close"))
    }

    /// SSE 必须声明长连接（RFC §4.2 不得发 `Connection: close`）。
    @Test func keepAliveModeIsUsedForSSE() throws {
        let request = URLRequest(url: URL(string: "https://192.0.2.10:18640/dsh-link/mobile/events")!)
        let text = String(decoding: try HTTP1Wire.requestData(for: request, mode: .keepAlive), as: UTF8.self)
        #expect(text.contains("Connection: keep-alive"))
        #expect(!text.contains("Connection: close"))
    }

    /// 桥只绑回环（§15.1 第 3 条：严禁暴露 LAN）。
    @Test func bridgeBindsLoopbackOnly() async throws {
        let fixture = try Self.loadIdentity()
        let server = FakeTLSServer()
        let serverPort = try await server.start(identity: fixture.identity)
        defer { server.stop() }

        let bridge = try LoopbackTunnelBridge(tunnel: DirectTunnel(port: serverPort))
        let port = try await bridge.listen()
        #expect(port != 0)

        // 从非回环地址连它必须失败：桥的 requiredLocalEndpoint 固定为 ipv4 loopback。
        let remote = NWConnection(
            host: NWEndpoint.Host("192.0.2.1"), port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
        let outcome = OneShotOutcome()
        remote.stateUpdateHandler = { (state: NWConnection.State) in
            switch state {
            case .ready: outcome.finish(nil, nil)
            case .failed(let error): outcome.finish(nil, "\(error)")
            case .waiting(let error): outcome.finish(nil, "waiting: \(error)")
            default: break
            }
        }
        remote.start(queue: .global())
        let result = await outcome.wait(timeout: 4)
        remote.cancel()
        await bridge.close()

        #expect(result.status == nil || result.error != nil, "非回环地址不应连上桥（实测：\(String(describing: result))）")
    }
}

/// 极简一次性等待器（spike 用）。
private final class OneShotOutcome: @unchecked Sendable {
    private let semaphore = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var stored: (status: Int?, error: String?)?

    func finish(_ status: Int?, _ error: String?) {
        lock.lock()
        if stored == nil { stored = (status, error) }
        lock.unlock()
        semaphore.signal()
    }

    func wait(timeout: TimeInterval) async -> (status: Int?, error: String?) {
        _ = semaphore.wait(timeout: .now() + timeout)
        lock.lock()
        defer { lock.unlock() }
        return stored ?? (nil, "no outcome")
    }
}
