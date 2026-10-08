import DLModels
import DLNet
import Foundation
import Testing

/// I3.5 合同测试：`HostClient` 的请求头与错误映射。
///
/// 用 `URLProtocol` 子类桩（不开端口、不发真实请求）覆盖：统一设备 token 头、
/// 401 / 403（pending）/ 409 `session_busy` / 409 其他 code / 404 能力缺失 / 5xx、
/// 传输失败与证书变更映射、以及用 fixtures `bootstrap.json` 做一次成功解码。
/// 桩按请求路径隔离（Swift Testing 默认并行执行），互不串扰。
struct HostClientContractTests {
    // MARK: - URLProtocol 桩

    private final class StubRegistry: @unchecked Sendable {
        struct Stub {
            var status: Int
            var body: Data
        }

        struct RequestRecord: Sendable {
            var method: String
            var url: URL
            var tokenHeaderName: String?
            var tokenHeaderValue: String?
        }

        private let lock = NSLock()
        private var stubs: [String: Stub] = [:]
        private var failures: [String: URLError] = [:]
        private var requests: [RequestRecord] = []

        func stub(path: String, status: Int, body: Data) {
            lock.withLock { stubs[path] = Stub(status: status, body: body) }
        }

        func failNext(forPath path: String, with error: URLError) {
            lock.withLock { failures[path] = error }
        }

        func record(_ record: RequestRecord) {
            lock.withLock { requests.append(record) }
        }

        func records(forPath path: String) -> [RequestRecord] {
            lock.withLock { requests.filter { $0.url.path == path } }
        }

        func stubResponse(forPath path: String) -> Stub? {
            lock.withLock { stubs[path] }
        }

        func takeFailure(forPath path: String) -> URLError? {
            lock.withLock { failures.removeValue(forKey: path) }
        }
    }

    private final class StubURLProtocol: URLProtocol, @unchecked Sendable {
        static let registry = StubRegistry()

        override class func canInit(with request: URLRequest) -> Bool {
            true
        }

        override class func canonicalRequest(for request: URLRequest) -> URLRequest {
            request
        }

        override func startLoading() {
            guard let url = request.url else {
                client?.urlProtocol(self, didFailWithError: URLError(.badURL))
                return
            }
            let tokenHeaderName = request.allHTTPHeaderFields?
                .keys
                .first { $0.caseInsensitiveCompare("x-dsh-link-token") == .orderedSame }
            Self.registry.record(
                StubRegistry.RequestRecord(
                    method: request.httpMethod ?? "GET",
                    url: url,
                    tokenHeaderName: tokenHeaderName,
                    tokenHeaderValue: request.value(forHTTPHeaderField: "x-dsh-link-token")
                )
            )
            if let failure = Self.registry.takeFailure(forPath: url.path) {
                client?.urlProtocol(self, didFailWithError: failure)
                return
            }
            let stub =
                Self.registry.stubResponse(forPath: url.path)
                ?? StubRegistry.Stub(status: 404, body: Data("{\"error\":\"no stub\"}".utf8))
            let response = HTTPURLResponse(
                url: url,
                statusCode: stub.status,
                httpVersion: "HTTP/1.1",
                headerFields: ["Content-Type": "application/json"]
            )
            guard let response else {
                client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
                return
            }
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            if !stub.body.isEmpty {
                client?.urlProtocol(self, didLoad: stub.body)
            }
            client?.urlProtocolDidFinishLoading(self)
        }

        override func stopLoading() {}
    }

    // MARK: - 测试基建

    private static let baseURL = URL(string: "https://host.example:18640")!

    private func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        return URLSession(configuration: configuration)
    }

    private func makeClient() -> HostClient {
        HostClient(baseURL: Self.baseURL, token: "tok-test-1", session: makeSession())
    }

    private static func fixtureData(_ name: String) throws -> Data {
        // …/apps/ios/Tests/Contract/<file>.swift 逐级上溯 5 层到仓库根
        let repoRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        return try Data(
            contentsOf:
                repoRoot
                .appendingPathComponent("testdata")
                .appendingPathComponent("mobile-contract")
                .appendingPathComponent(name))
    }

    @Test func rawDownloadUsesTokenAndNormalizesHeaders() async throws {
        let path = "/raw-success"
        let bytes = Data([0, 1, 2, 255])
        StubURLProtocol.registry.stub(path: path, status: 200, body: bytes)
        let response = try await makeClient().getRaw(path: path, query: ["path": "中文 +.png"])
        #expect(response.data == bytes)
        #expect(response.headers["content-type"] == "application/json")
        let record = try #require(StubURLProtocol.registry.records(forPath: path).last)
        #expect(record.tokenHeaderValue != nil)
        #expect(URLComponents(url: record.url, resolvingAgainstBaseURL: false)?.queryItems?.first?.value == "中文 +.png")
    }

    @Test func rawDownloadRejectsOversizedResponse() async {
        let path = "/raw-limit"
        StubURLProtocol.registry.stub(path: path, status: 200, body: Data([1, 2, 3, 4]))
        await #expect(throws: HostClientError.decoding("File exceeds download limit")) {
            _ = try await makeClient().getRaw(path: path, maxBytes: 3)
        }
    }

    @Test func rawDownloadMapsForbiddenAndTransportErrors() async {
        let path = "/raw-forbidden"
        StubURLProtocol.registry.stub(path: path, status: 403, body: Data("{}".utf8))
        await #expect(throws: HostClientError.forbidden(pending: false)) {
            _ = try await makeClient().getRaw(path: path)
        }
        let broken = "/raw-timeout"
        StubURLProtocol.registry.failNext(forPath: broken, with: URLError(.timedOut))
        do {
            _ = try await makeClient().getRaw(path: broken)
            Issue.record("Expected timeout")
        } catch let error as HostClientError {
            guard case .transport(let underlying) = error else {
                Issue.record("Expected transport error, got \(error)")
                return
            }
            #expect(underlying.code == .timedOut)
        } catch { Issue.record("Unexpected error: \(error)") }
    }

    // MARK: - 成功路径（fixtures 解码）

    @Test("bootstrap fixtures：成功解码 + 统一设备 token 头")
    func decodesBootstrapFixtureWithTokenHeader() async throws {
        let path = "/dsh-link/mobile/bootstrap"
        StubURLProtocol.registry.stub(path: path, status: 200, body: try Self.fixtureData("bootstrap.json"))
        let client = makeClient()

        let bootstrap = try await client.get(BootstrapResponse.self, path: path)

        #expect(bootstrap.version == 1)
        #expect(bootstrap.protocolVersion == 2)
        #expect(bootstrap.capabilities?.sync?.resync == true)
        #expect(bootstrap.capabilities?.sync?.catchupIntegrity == true)
        #expect(bootstrap.capabilities?.events?.host == true)
        #expect(bootstrap.capabilities?.requests?.reconnectGraceMs == 30000)
        #expect(bootstrap.remote == .disabled)
        #expect(bootstrap.sessions?.isEmpty == false)

        let records = StubURLProtocol.registry.records(forPath: path)
        #expect(records.count == 1)
        #expect(records.first?.method == "GET")
        #expect(records.first?.tokenHeaderName == "x-dsh-link-token")
        #expect(records.first?.tokenHeaderValue == "tok-test-1")
        #expect(records.first?.url.query == nil)
    }

    @Test("POST：JSON 请求体 + 响应解码（approval-submit fixtures）")
    func postsJSONAndDecodesResponse() async throws {
        let path = "/dsh-link/mobile/sessions/sess-demo/approval"
        StubURLProtocol.registry.stub(path: path, status: 200, body: try Self.fixtureData("approval-submit.json"))
        let client = makeClient()

        let response = try await client.post(
            RequestSubmitResponse.self,
            path: path,
            json: ["outcome": "allowed-once"]
        )

        #expect(response.ok == true)
        #expect(response.accepted == true)
        #expect(response.outcome == "allowed-once")
        #expect(response.status == .resolved)
        #expect(response.handledBy == "plugin")

        let records = StubURLProtocol.registry.records(forPath: path)
        #expect(records.first?.method == "POST")
        #expect(records.first?.tokenHeaderValue == "tok-test-1")
    }

    // MARK: - 错误映射（合同错误码逐一覆盖）

    @Test("401 → unauthorized")
    func maps401ToUnauthorized() async {
        await expectMapping(status: 401, body: "{\"error\":\"token 无效\"}") { error in
            error == .unauthorized
        }
    }

    @Test("403 pending → forbidden(pending: true)（配对等待主机确认）")
    func maps403PendingToForbiddenPending() async {
        await expectMapping(status: 403, body: "{\"error\":\"设备待主机确认\",\"pending\":true}") { error in
            error == .forbidden(pending: true)
        }
    }

    @Test("403 普通 → forbidden(pending: false)")
    func maps403PlainToForbidden() async {
        await expectMapping(status: 403, body: "{\"error\":\"forbidden\"}") { error in
            error == .forbidden(pending: false)
        }
    }

    @Test("409 session_busy → sessionBusy")
    func maps409SessionBusy() async {
        await expectMapping(status: 409, body: "{\"error\":\"会话被占用\",\"code\":\"session_busy\"}") { error in
            error == .sessionBusy
        }
    }

    @Test("409 其他 code → conflict(code:)（用 pair-same-name-409 fixtures）")
    func maps409OtherCodeToConflict() async throws {
        let path = "/dsh-link/pair"
        StubURLProtocol.registry.stub(path: path, status: 409, body: try Self.fixtureData("pair-same-name-409.json"))
        let client = makeClient()

        do {
            _ = try await client.get(PairResponse.self, path: path)
            Issue.record("应抛出 conflict")
        } catch {
            #expect(error as? HostClientError == .conflict(code: "SAME_NAME"))
        }
    }

    @Test("404 → capabilityMissing")
    func maps404ToCapabilityMissing() async {
        await expectMapping(status: 404, body: "{\"error\":\"not found\"}") { error in
            error == .capabilityMissing
        }
    }

    @Test("5xx → server(status:code:)")
    func maps500ToServer() async {
        await expectMapping(status: 500, body: "{\"error\":\"boom\",\"code\":\"HOST_DOWN\"}") { error in
            error == .server(status: 500, code: "HOST_DOWN")
        }
    }

    @Test("传输失败 → transport(URLError)，错误码保留")
    func mapsTransportFailure() async {
        let path = "/transport-failure"
        StubURLProtocol.registry.failNext(forPath: path, with: URLError(.notConnectedToInternet))
        let client = makeClient()

        do {
            _ = try await client.get(BootstrapResponse.self, path: path)
            Issue.record("应抛出 transport")
        } catch {
            let mapped = error as? HostClientError
            guard case .transport(let urlError)? = mapped else {
                Issue.record("应为 .transport，实际 \(String(describing: mapped))")
                return
            }
            #expect(urlError.code == .notConnectedToInternet)
        }
    }

    @Test("证书变更映射：钉扎失败 → certificateChanged（优先于 transport）")
    func mapsCertificateChanged() {
        #expect(HostClient.mapTransportError(URLError(.cancelled), pinErrorChanged: true) == .certificateChanged)
        #expect(
            HostClient.mapTransportError(URLError(.notConnectedToInternet), pinErrorChanged: false)
                == .transport(URLError(.notConnectedToInternet))
        )
        #expect(
            HostClient.mapTransportError(URLError(.cancelled), pinErrorChanged: false)
                == .transport(URLError(.cancelled)))
    }

    // MARK: - 断言辅助

    /// 每次调用用唯一路径注册桩，避免并行测试互相覆盖。
    private func expectMapping(
        status: Int,
        body: String,
        fileID: StaticString = #fileID,
        filePath: StaticString = #filePath,
        line: UInt = #line,
        column: UInt = #column,
        _ assertion: (HostClientError) -> Bool
    ) async {
        let path = "/probe-\(UUID().uuidString)"
        StubURLProtocol.registry.stub(path: path, status: status, body: Data(body.utf8))
        let client = makeClient()
        let sourceLocation = SourceLocation(
            fileID: String(describing: fileID),
            filePath: String(describing: filePath),
            line: Int(line),
            column: Int(column)
        )

        do {
            _ = try await client.get(JSONValue.self, path: path)
            Issue.record("应抛错", sourceLocation: sourceLocation)
        } catch {
            let matched = (error as? HostClientError).map(assertion) ?? false
            #expect(matched, "实际 \(String(describing: error))", sourceLocation: sourceLocation)
        }
    }
}
