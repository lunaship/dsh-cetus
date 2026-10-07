import DLNet
import DLSecurity
import Foundation
import Network
import Security
import Testing

/// G4.1 验收测试：**内层 TLS 端到端 + 证书钉扎**（RFC 0001 4.2 第 6 步、4.3）。
///
/// 链路（全程本机，不连任何真实中继）：
/// `HTTP 字节 → InnerTLSChannel(NWProtocolTLS + verify_block) → 回环桥 → 假隧道 → 本机 TLS 服务`
///
/// **最关键的一条是 `mismatchedFingerprintIsRejected`**：指纹不符时必须真的被拒。
/// 这是 4.3 的核心保证——不能只断言「没崩」，要断言连接没建立、服务端没收到请求。
@Suite(.serialized) struct RemoteInnerTLSTests {
    // MARK: - 假隧道

    /// 把隧道字节直连到本机端口（跳过 DLP/1 加密，只验证内层 TLS 这一层）。
    final class DirectTunnel: RemoteTunnel, @unchecked Sendable {
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
                if let error {
                    self.continuation.finish(throwing: error)
                    return
                }
                if isComplete {
                    self.continuation.finish()
                    return
                }
                self.readLoop()
            }
        }

        func write(_ bytes: Data) async throws {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, any Error>) in
                connection.send(
                    content: bytes,
                    completion: .contentProcessed { error in
                        if let error { c.resume(throwing: error) } else { c.resume() }
                    })
            }
        }

        func close() async {
            continuation.finish()
            connection.cancel()
        }
    }

    // MARK: - 本机 TLS 服务（模拟插件的 HTTPS 服务）

    final class FakeTLSServer: @unchecked Sendable {
        private var listener: NWListener?
        private let lock = NSLock()
        private var completed = 0

        /// 服务端完整处理完的请求数。指纹不符时它必须是 0。
        var completedRequests: Int { lock.withLock { completed } }

        func start(identity: sec_identity_t) async throws -> UInt16 {
            let tls = NWProtocolTLS.Options()
            sec_protocol_options_set_local_identity(tls.securityProtocolOptions, identity)
            // 手机不出示客户端证书。
            sec_protocol_options_set_peer_authentication_required(tls.securityProtocolOptions, false)

            let listener = try NWListener(using: NWParameters(tls: tls, tcp: .init()), on: .any)
            self.listener = listener
            listener.newConnectionHandler = { [weak self] connection in
                connection.start(queue: .global())
                Task { await self?.serve(connection) }
            }
            return try await withCheckedThrowingContinuation { continuation in
                listener.stateUpdateHandler = { state in
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
            let request = await receive(connection)
            guard !request.isEmpty else {
                connection.cancel()
                return
            }
            let body = #"{"via":"inner-tls","ok":true}"#
            let response =
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n\(body)"
            await send(connection, Data(response.utf8))
            lock.withLock { completed += 1 }
            connection.cancel()
        }

        private func receive(_ connection: NWConnection) async -> Data {
            await withCheckedContinuation { continuation in
                connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { data, _, _, _ in
                    continuation.resume(returning: data ?? Data())
                }
            }
        }

        private func send(_ connection: NWConnection, _ data: Data) async {
            await withCheckedContinuation { continuation in
                connection.send(content: data, completion: .contentProcessed { _ in continuation.resume() })
            }
        }
    }

    // MARK: - fixtures

    /// 从 PKCS#12 fixture 取身份与叶证书 DER。
    ///
    /// fixture 用 openssl 生成一次后提交（`Tests/Fixtures/inner-tls/`）：
    /// 自签 RSA-2048、CN=127.0.0.1、SAN IP:127.0.0.1。测试里不生成证书，
    /// 避免引入 swiftsyntax/openssl 之外的新依赖，也避免每次跑测试都变指纹。
    static func loadIdentity() throws -> (identity: sec_identity_t, der: Data, fingerprint: String) {
        let url = try #require(
            Bundle(for: BundleToken.self).url(
                forResource: "identity", withExtension: "p12", subdirectory: "inner-tls"))
        let p12 = try Data(contentsOf: url)

        var items: CFArray?
        let options: [String: Any] = [kSecImportExportPassphrase as String: "dlptest"]
        let status = SecPKCS12Import(p12 as CFData, options as CFDictionary, &items)
        guard status == errSecSuccess,
            let list = items as? [[String: Any]],
            let first = list.first,
            let identityRef = first[kSecImportItemIdentity as String]
        else {
            throw RemoteTunnelError.transport("PKCS#12 fixture load failed: \(status)")
        }
        let identity = identityRef as! sec_identity_t

        let derURL = try #require(
            Bundle(for: BundleToken.self).url(
                forResource: "leaf", withExtension: "der", subdirectory: "inner-tls"))
        let der = try Data(contentsOf: derURL)
        return (identity, der, CertificateFingerprint.sha256(der: der))
    }

    /// 取 bundle 用的锚。
    final class BundleToken {}

    // MARK: - 测试

    /// **核心保证**：指纹匹配时，HTTP 明文能穿过内层 TLS 到达服务端并拿回响应。
    @Test func matchingFingerprintCompletesHTTPOverInnerTLS() async throws {
        let fixture = try Self.loadIdentity()
        let server = FakeTLSServer()
        let port = try await server.start(identity: fixture.identity)
        defer { server.stop() }

        let channel = try await NWRemoteTunnelTransport.openInnerTLS(
            over: DirectTunnel(port: port), host: "127.0.0.1", expectedFingerprint: fixture.fingerprint)
        defer { Task { await channel.close() } }

        // 发一条真实 HTTP/1.1 请求（复用 HTTP1Wire 的组装）。
        var request = URLRequest(url: URL(string: "https://127.0.0.1:18640/dsh-link/state")!)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        try await channel.write(HTTP1Wire.requestData(for: request))

        // 读回响应并解析。
        var parser = HTTP1Wire.ResponseParser()
        var finished = false
        for try await chunk in channel.incoming {
            if try parser.push(chunk) {
                finished = true
                break
            }
        }
        #expect(finished, "内层 TLS 之上应能读回完整 HTTP 响应")

        let response = try #require(parser.finish())
        #expect(response.status == 200)
        #expect(String(decoding: response.body, as: UTF8.self) == #"{"via":"inner-tls","ok":true}"#)
        #expect(server.completedRequests == 1)
    }

    /// **4.3 的核心保证**：指纹不符必须真的被拒——连接不建立、服务端收不到请求。
    @Test func mismatchedFingerprintIsRejected() async throws {
        let fixture = try Self.loadIdentity()
        let server = FakeTLSServer()
        let port = try await server.start(identity: fixture.identity)
        defer { server.stop() }

        // 正确的指纹改成全 0：格式合法但与叶证书不符。
        let wrong = String(repeating: "0", count: 64)

        await #expect(throws: CertificatePinError.self) {
            _ = try await NWRemoteTunnelTransport.openInnerTLS(
                over: DirectTunnel(port: port), host: "127.0.0.1", expectedFingerprint: wrong)
        }

        // 关键断言：不是「没崩」，而是服务端**确实没有**完成任何请求。
        #expect(server.completedRequests == 0, "指纹不符时不得建立内层 TLS 会话")
    }

    /// 缺少指纹同样 fail-closed，不回退系统 PKI。
    @Test func missingFingerprintIsRejected() async throws {
        let fixture = try Self.loadIdentity()
        let server = FakeTLSServer()
        let port = try await server.start(identity: fixture.identity)
        defer { server.stop() }

        await #expect(throws: CertificatePinError.self) {
            _ = try await NWRemoteTunnelTransport.openInnerTLS(
                over: DirectTunnel(port: port), host: "127.0.0.1", expectedFingerprint: nil)
        }
        await #expect(throws: CertificatePinError.self) {
            _ = try await NWRemoteTunnelTransport.openInnerTLS(
                over: DirectTunnel(port: port), host: "127.0.0.1", expectedFingerprint: "")
        }
        #expect(server.completedRequests == 0)
    }

    /// 指纹写法不合法（长度 / 字符集）也拒绝。
    @Test func malformedFingerprintIsRejected() async throws {
        let fixture = try Self.loadIdentity()
        let server = FakeTLSServer()
        let port = try await server.start(identity: fixture.identity)
        defer { server.stop() }

        for bad in ["abc", String(repeating: "z", count: 64), String(repeating: "0", count: 63)] {
            await #expect(throws: CertificatePinError.self) {
                _ = try await NWRemoteTunnelTransport.openInnerTLS(
                    over: DirectTunnel(port: port), host: "127.0.0.1", expectedFingerprint: bad)
            }
        }
        #expect(server.completedRequests == 0)
    }

    /// fixture 自带的指纹与叶证书一致（防止 fixture 换掉后测试变成假绿）。
    @Test func fixtureFingerprintMatchesLeaf() throws {
        let fixture = try Self.loadIdentity()
        #expect(CertificateFingerprint.isValid(fixture.fingerprint))
        #expect(PinEvaluation.evaluate(leafDER: fixture.der, expected: fixture.fingerprint) == .match)
    }
}
