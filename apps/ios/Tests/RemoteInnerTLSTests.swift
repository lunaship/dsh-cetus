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
        private var lastRequest = Data()
        var requestBytes: Data { lock.withLock { lastRequest } }

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
            lock.withLock { lastRequest = request }
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
        let p12 = try Data(contentsOf: try fixtureURL(name: "identity", extension: "p12"))

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
        // SecPKCS12Import 的字典值是 Any；这里必须是 SecIdentity。
        // 用 `as?` 做条件转换，失败即视为 fixture 有问题，不静默继续。
        let secIdentity = identityRef as! SecIdentity
        guard let identity = sec_identity_create(secIdentity) else {
            throw RemoteTunnelError.transport("sec_identity_create failed")
        }

        let der = try Data(contentsOf: try fixtureURL(name: "leaf", extension: "der"))
        return (identity, der, CertificateFingerprint.sha256(der: der))
    }

    /// 在 bundle 里找 `Tests/Fixtures/inner-tls/` 下的 fixture。
    ///
    /// 先试带子目录的路径，再试平铺到 bundle 根的（照 `DlpVectorTests.load()`
    /// 对 `dlp1/vectors.json` 的既有写法）。Xcode 对 `buildPhase: resources`
    /// 的目录处理会因版本而异，两种都兜住，避免因打包方式改变而整组测试挂掉。
    static func fixtureURL(name: String, extension ext: String) throws -> URL {
        let bundle = Bundle(for: BundleToken.self)
        let nested = bundle.url(forResource: name, withExtension: ext, subdirectory: "inner-tls")
        let flat = bundle.url(forResource: name, withExtension: ext)
        guard let url = nested ?? flat else {
            throw RemoteTunnelError.transport("fixture \(name).\(ext) not found in test bundle")
        }
        return url
    }

    /// 取 bundle 用的锚。
    final class BundleToken {}

    @Test func previewUpstreamPinsCertificateAndAddsTokenOnlyToHostRequest() async throws {
        let fixture = try Self.loadIdentity()
        let server = FakeTLSServer()
        let port = try await server.start(identity: fixture.identity)
        defer { server.stop() }
        let connection = try await PreviewUpstream.open(
            baseURL: URL(string: "https://127.0.0.1:\(port)")!, fingerprint: fixture.fingerprint,
            token: "isolated-preview-token",
            path: "/dsh-link/mobile/preview/" + String(repeating: "ab", count: 12) + "/socket",
            key: "dGhlIHNhbXBsZSBub25jZQ==", protocols: "vite-hmr")
        defer { connection.cancel() }
        _ = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
            connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { data, _, _, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: data ?? Data())
                }
            }
        }
        let request = String(decoding: server.requestBytes, as: UTF8.self)
        #expect(request.contains("x-dsh-link-token: isolated-preview-token\r\n"))
        #expect(request.contains("Sec-WebSocket-Protocol: vite-hmr\r\n"))
        #expect(!request.components(separatedBy: "\r\n")[0].contains("isolated-preview-token"))
    }

    @Test func previewUpstreamRejectsMismatchedCertificateBeforeSendingToken() async throws {
        let fixture = try Self.loadIdentity()
        let server = FakeTLSServer()
        let port = try await server.start(identity: fixture.identity)
        defer { server.stop() }
        await #expect(throws: HostClientError.certificateChanged) {
            _ = try await PreviewUpstream.open(
                baseURL: URL(string: "https://127.0.0.1:\(port)")!, fingerprint: String(repeating: "00", count: 32),
                token: "must-not-arrive",
                path: "/dsh-link/mobile/preview/" + String(repeating: "ab", count: 12) + "/socket",
                key: "dGhlIHNhbXBsZSBub25jZQ==", protocols: nil)
        }
        #expect(server.requestBytes.isEmpty)
    }

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

        let parsed = parser.finish()
        let response = try #require(parsed)
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

        // 必须**快速失败**，不能把「超时」当成「拒绝」。握手超时是 10 秒；
        // 若走了超时路径，说明拒绝并没有真的发生，这条断言就形同虚设。
        let started = Date()
        await #expect(throws: CertificatePinError.self) {
            _ = try await NWRemoteTunnelTransport.openInnerTLS(
                over: DirectTunnel(port: port), host: "127.0.0.1", expectedFingerprint: wrong)
        }
        let elapsed = Date().timeIntervalSince(started)
        #expect(
            elapsed < NWRemoteTunnelTransport.innerTLSTimeout - 1,
            "指纹不符应立即被 verify_block 拒绝，而不是拖到握手超时（实际 \(elapsed)s）")

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
