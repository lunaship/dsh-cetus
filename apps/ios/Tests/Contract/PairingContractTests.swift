import DLModels
import DLNet
import DLSecurity
import Foundation
import Testing

/// I3.8：真实合同 fixtures + Android 配对语义，不开端口、不访问真实凭据。
@Suite(.serialized)
struct PairingContractTests {
    private static let pin = String(repeating: "ab", count: 32)
    private static let remote =
        #""remote":{"e":"wss://relay.example/ws","r":"AAAAAAAAAAAAAAAAAAAAAA","s":"AQEBAQEBAQEBAQEBAQEBAQ"}"#

    private final class Registry: @unchecked Sendable {
        enum Reply: Sendable {
            case http(Int, Data)
            case network
        }
        private let lock = NSLock()
        private var replies: [Reply] = []
        private var requests: [URLRequest] = []

        func reset(_ replies: [Reply]) {
            lock.withLock {
                self.replies = replies
                requests = []
            }
        }
        func take(_ request: URLRequest) -> Reply {
            lock.withLock {
                requests.append(request)
                return replies.isEmpty ? .network : replies.removeFirst()
            }
        }
        var records: [URLRequest] { lock.withLock { requests } }
    }

    private final class Stub: URLProtocol, @unchecked Sendable {
        static let registry = Registry()
        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func startLoading() {
            var recorded = request
            if recorded.httpBody == nil, let stream = recorded.httpBodyStream {
                stream.open()
                defer { stream.close() }
                var data = Data()
                var buffer = [UInt8](repeating: 0, count: 1024)
                while true {
                    let count = stream.read(&buffer, maxLength: buffer.count)
                    if count <= 0 { break }
                    data.append(buffer, count: count)
                }
                recorded.httpBody = data
            }
            switch Self.registry.take(recorded) {
            case .network:
                client?.urlProtocol(self, didFailWithError: URLError(.cannotConnectToHost))
            case .http(let status, let data):
                let response = HTTPURLResponse(
                    url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
                client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
                client?.urlProtocol(self, didLoad: data)
                client?.urlProtocolDidFinishLoading(self)
            }
        }
        override func stopLoading() {}
    }

    private struct Context {
        let directory: URL
        let session: URLSession
        let store: HostStore
        let client: PairingClient

        init(replies: [Registry.Reply], policy: PairingClient.FreshnessPolicy = .init(), now: Int = 1_767_225_600_000)
            throws
        {
            directory = URL(fileURLWithPath: "/tmp").appendingPathComponent("pairing-\(UUID().uuidString)")
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            store = HostStore(
                fileURL: directory.appendingPathComponent("hosts.json"), secureStore: InMemorySecureStore())
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [Stub.self]
            session = URLSession(configuration: config)
            client = PairingClient(store: store, session: session, freshness: policy, clock: { now })
            Stub.registry.reset(replies)
        }
        func clean() {
            session.invalidateAndCancel()
            try? FileManager.default.removeItem(at: directory)
        }
    }

    private static func fixture(_ name: String) throws -> Data {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        return try Data(contentsOf: root.appendingPathComponent("testdata/mobile-contract/\(name).json"))
    }

    private func qr(extra: String = "", urls: [String] = ["https://10.0.0.2:18640"], pin: String = Self.pin) throws
        -> PairingQRPayload
    {
        let addresses = String(decoding: try JSONEncoder().encode(urls), as: UTF8.self)
        return try PairingQRPayload(
            #"{"type":"dsh-link","pairingCode":"123456","name":"书房","urls":\#(addresses),"certFingerprint":"\#(pin)"\#(extra)}"#
        )
    }

    private func attempt(_ qr: PairingQRPayload) -> PairingClient.Attempt {
        .init(qr: qr, deviceName: "iPhone", requestId: "request-123", hostId: "local-host-1")
    }

    @Test func parsesAndroidNormalAndLegacyFields() throws {
        let parsed = try qr(extra: #", "v":1,"requireConfirm":true,"issuedAt":1000,"expiresAt":2000,"unknown":1"#)
        #expect(parsed.code == "123456")
        #expect(parsed.urls == ["https://10.0.0.2:18640"])
        #expect(parsed.name == "书房")
        #expect(parsed.certFingerprint == Self.pin)
        #expect(parsed.issuedAt == 1000 && parsed.expiresAt == 2000)
        let old = try PairingQRPayload(#"{"type":"dsh-link","code":" 1 ","urls":[" https://x "],"name":" "}"#)
        #expect(old.code == "1" && old.name == "dsh" && old.urls == ["https://x"])
        #expect(old.issuedAt == nil && old.expiresAt == nil)
        let preferred = try PairingQRPayload(#"{"type":"dsh-link","pairingCode":"2","code":"1","urls":["https://x"]}"#)
        #expect(preferred.code == "2")
        let numeric = try PairingQRPayload(#"{"type":"dsh-link","code":123,"urls":["https://x"]}"#)
        #expect(numeric.code == "123")
    }

    @Test(arguments: ["not-json", #"{"type":"other"}"#, "[]"])
    func notDeepLinks(text: String) {
        #expect(throws: PairingQRParseError.notDeepLinks) { try PairingQRPayload(text) }
    }

    @Test(arguments: [
        #"{"type":"dsh-link","pairingCode":"1","urls":[1,2]}"#,
        #"{"type":"dsh-link","pairingCode":"1","urls":[{"x":1}]}"#,
        #"{"type":"dsh-link","pairingCode":"1","urls":[null]}"#,
        #"{"type":"dsh-link","pairingCode":"1","urls":[" "]}"#,
        #"{"type":"dsh-link","pairingCode":"1"}"#,
        #"{"type":"dsh-link","urls":["https://x"]}"#,
        #"{"type":"dsh-link","pairingCode":"","code":"1","urls":["https://x"]}"#,
    ])
    func incompleteAndroidCases(text: String) {
        #expect(throws: PairingQRParseError.incomplete) { try PairingQRPayload(text) }
    }

    @Test func remoteIsParsedButNeverUsed() async throws {
        let parsed = try qr(extra: ",\(Self.remote)")
        #expect(parsed.remote?.routeId == "AAAAAAAAAAAAAAAAAAAAAA")
        #expect(parsed.remote?.bootstrapSeed == "AQEBAQEBAQEBAQEBAQEBAQ")
        #expect(try qr(extra: #", "remote":{"e":"ws://insecure/ws","r":"x","s":"y"}"#).remote == nil)
        #expect(try qr(extra: ",\(Self.remote)", pin: "").remote == nil)
        let onlyRemote = try PairingQRPayload(#"{"type":"dsh-link","code":"1","certFingerprint":"ab",\#(Self.remote)}"#)
        let context = try Context(replies: [])
        defer { context.clean() }
        #expect(await context.client.pair(attempt(onlyRemote)) == .failed(.noLANAddress))
        #expect(Stub.registry.records.isEmpty)
        #expect(throws: PairingQRParseError.incomplete) {
            try PairingQRPayload(#"{"type":"dsh-link","code":"1",\#(Self.remote)}"#)
        }
        #expect(try qr(extra: #", "relay":{"v":2,"client":"old"}"#).remote == nil)
    }

    @Test func expiryAndInjectableAgePreventRequests() async throws {
        let context = try Context(replies: [], policy: .init(maximumIssuedAgeMilliseconds: 1000), now: 2001)
        defer { context.clean() }
        #expect(await context.client.pair(attempt(try qr(extra: #", "expiresAt":2000"#))) == .failed(.qrExpired))
        #expect(await context.client.pair(attempt(try qr(extra: #", "issuedAt":1000"#))) == .failed(.qrExpired))
        #expect(Stub.registry.records.isEmpty)
    }

    @Test func expiryBoundaryAndOldIssuedAtAreAcceptedByDefault() async throws {
        let context = try Context(replies: [.http(200, try Self.fixture("pair"))], now: 2000)
        defer { context.clean() }
        guard case .paired = await context.client.pair(attempt(try qr(extra: #", "issuedAt":0,"expiresAt":2000"#)))
        else {
            Issue.record("Expiry is strictly greater than expiresAt; no default issuedAt cutoff")
            return
        }
        #expect(Stub.registry.records.count == 1)
    }

    @Test func missingTimeStillSubmitsAndSavesFixture() async throws {
        let context = try Context(replies: [.http(200, try Self.fixture("pair"))])
        defer { context.clean() }
        let result = await context.client.pair(attempt(try qr()))
        guard case .paired(let host) = result else {
            Issue.record("Expected paired")
            return
        }
        #expect(host.hostId == "local-host-1" && host.name == "书房")
        #expect(host.primaryUrl == "https://10.0.0.2:18640" && host.certFingerprint == Self.pin)
        #expect(host.pairedAt == 1_767_225_600_000)
        #expect(await context.store.get(hostId: host.hostId) == host)
        #expect(await context.store.token(for: host.hostId) == "<token>")
        let reopened = HostStore(
            fileURL: context.directory.appendingPathComponent("hosts.json"), secureStore: InMemorySecureStore())
        #expect(await reopened.all().isEmpty)  // 凭据不能只靠沙盒文件恢复。
        let disk = try String(contentsOf: context.directory.appendingPathComponent("hosts.json"), encoding: .utf8)
        #expect(!disk.contains("<token>") && !disk.contains(Self.pin))
        let request = try #require(Stub.registry.records.first)
        let body = try JSONSerialization.jsonObject(with: #require(request.httpBody)) as! [String: String]
        #expect(body == ["code": "123456", "deviceName": "iPhone", "via": "lan", "requestId": "request-123"])
        #expect(request.httpMethod == "POST" && request.url?.path == "/dsh-link/pair")
        #expect(request.value(forHTTPHeaderField: "Content-Type") == "application/json")
    }

    @Test(arguments: ["", "ab"])
    func privateLANRequiresValidFingerprint(pin: String) async throws {
        let context = try Context(replies: [])
        defer { context.clean() }
        #expect(await context.client.pair(attempt(try qr(pin: pin))) == .failed(.fingerprintRequired))
        #expect(Stub.registry.records.isEmpty)
    }

    @Test func sameNameReplacementKeepsCodeAndRequestID() async throws {
        let context = try Context(replies: [
            .http(409, try Self.fixture("pair-same-name-409")), .http(200, try Self.fixture("pair")),
        ])
        defer { context.clean() }
        let operation = attempt(try qr())
        let conflict = await context.client.pair(operation)
        #expect(conflict == .sameName(ExistingDevice(deviceId: "<deviceId>", name: "iPhone 15 Pro", status: "active")))
        #expect(await context.store.all().isEmpty)
        guard case .paired = await context.client.pair(operation, replacing: true) else {
            Issue.record("Replacement failed")
            return
        }
        let bodies = try Stub.registry.records.map {
            try JSONSerialization.jsonObject(with: #require($0.httpBody)) as! [String: Any]
        }
        #expect(bodies.count == 2)
        #expect(bodies[0]["replace"] == nil && bodies[1]["replace"] as? Bool == true)
        for key in ["code", "requestId", "deviceName", "via"] {
            #expect(bodies[0][key] as? String == bodies[1][key] as? String)
        }
    }

    @Test func urlsAreSequentialEvenWithRemoteAndTailnetSpareIsStored() async throws {
        let context = try Context(replies: [.network, .http(503, Data()), .http(200, try Self.fixture("pair"))])
        defer { context.clean() }
        let addresses = [
            "http://10.0.0.1:18640", "10.0.0.2:18640", "https://10.0.0.3:18640", "https://100.64.0.8:18640",
        ]
        guard
            case .paired(let host) = await context.client.pair(
                attempt(try qr(extra: ",\(Self.remote)", urls: addresses)))
        else {
            Issue.record("Expected third URL to succeed")
            return
        }
        #expect(Stub.registry.records.map { $0.url?.host } == ["10.0.0.1", "10.0.0.2", "10.0.0.3"])
        #expect(Stub.registry.records.allSatisfy { $0.url?.scheme == "https" })
        #expect(host.tailnetUrl == "https://100.64.0.8:18640")
        for request in Stub.registry.records {
            let body = try JSONSerialization.jsonObject(with: #require(request.httpBody)) as! [String: String]
            #expect(body["requestId"] == "request-123")
        }
    }

    @Test func hostClientErrorStopsAddressFallback() async throws {
        let context = try Context(replies: [
            .http(401, Data(#"{"error":"配对码已过期"}"#.utf8)), .network,
        ])
        defer { context.clean() }
        let result = await context.client.pair(attempt(try qr(urls: ["https://10.0.0.2", "https://10.0.0.3"])))
        #expect(result == .failed(.http(code: .invalidCode, status: 401, hint: "配对码已过期")))
        #expect(Stub.registry.records.count == 1)
        #expect(await context.store.all().isEmpty)
    }

    @Test func tailnetRulesMatchAndroidRatherThanRouteSelectorDNSRules() {
        #expect(
            PairingClient.tailnetSpare(
                urls: ["https://100.64.0.8:18640", "https://100.65.0.9:18640"], primary: "https://100.64.0.8:18640/")
                == nil)
        #expect(
            PairingClient.tailnetSpare(urls: ["https://100.127.1.1"], primary: "https://10.0.0.2")
                == "https://100.127.1.1")
        #expect(
            PairingClient.tailnetSpare(urls: ["https://[fd7a:115c:a1e0::8]:18640"], primary: "https://10.0.0.2") != nil)
        #expect(
            PairingClient.tailnetSpare(
                urls: ["https://100.63.1.1", "https://8.8.8.8", "https://host.ts.net"], primary: "https://10.0.0.2")
                == nil)
    }

    @Test(arguments: [401, 409, 415, 429, 503, 418])
    func errorMapping(status: Int) async throws {
        let context = try Context(replies: [.http(status, Data(#"{"error":"host hint","code":"OTHER"}"#.utf8))])
        defer { context.clean() }
        let expected: PairingError.HTTPCode =
            switch status {
            case 401: .invalidCode
            case 409: .conflict
            case 415: .badRequest
            case 429: .tooManyAttempts
            case 500...599: .hostUnavailable
            default: .other
            }
        #expect(
            await context.client.pair(attempt(try qr()))
                == .failed(.http(code: expected, status: status, hint: status == 415 ? nil : "host hint")))
        #expect(await context.store.all().isEmpty)
    }

    @Test func pairResponseRemoteIsSavedAndBrokenRemoteIsIgnored() async throws {
        let remote =
            #"{"e":"wss://relay.example/ws","r":"AAAAAAAAAAAAAAAAAAAAAA","h":"AQEBAQEBAQEBAQEBAQEBAQ","k":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","p":"\#(Self.pin)"}"#
        let bodies = [
            #"{"token":"tok","remote":\#(remote)}"#, #"{"token":"tok","remote":{"e":1}}"#,
            #"{"token":"tok","remote":{"e":"ws://insecure","r":"x","h":"x","k":"x"}}"#,
        ]
        for (index, body) in bodies.enumerated() {
            let context = try Context(replies: [.http(200, Data(body.utf8))])
            defer { context.clean() }
            guard case .paired(let host) = await context.client.pair(attempt(try qr())) else {
                Issue.record("Remote must not break LAN pairing")
                continue
            }
            if index == 0 {
                #expect(host.remote?.deviceHandle == "AQEBAQEBAQEBAQEBAQEBAQ")
                #expect(host.remote?.relayKey == String(repeating: "A", count: 43))
                #expect(await context.store.get(hostId: host.hostId)?.remote == host.remote)
                let disk = try String(
                    contentsOf: context.directory.appendingPathComponent("hosts.json"), encoding: .utf8)
                #expect(!disk.contains(String(repeating: "A", count: 43)))
            } else {
                #expect(host.remote == nil)
            }
        }
    }

    @Test func sameNameStopsAddressFallbackAndReplacementRechecksExpiry() async throws {
        let context = try Context(
            replies: [.http(409, try Self.fixture("pair-same-name-409")), .http(200, try Self.fixture("pair"))],
            now: 1000)
        defer { context.clean() }
        let operation = attempt(try qr(extra: #", "expiresAt":1001"#, urls: ["https://10.0.0.2", "https://10.0.0.3"]))
        guard case .sameName = await context.client.pair(operation) else {
            Issue.record("Expected conflict")
            return
        }
        #expect(Stub.registry.records.count == 1)
        let expiredClient = PairingClient(store: context.store, session: context.session, clock: { 1002 })
        #expect(await expiredClient.pair(operation, replacing: true) == .failed(.qrExpired))
        #expect(Stub.registry.records.count == 1)
    }

    @Test func malformedTimestampIsExplicitError() {
        #expect(throws: PairingQRParseError.invalidTimestamp(field: "expiresAt")) {
            try qr(extra: #", "expiresAt":"2000""#)
        }
        #expect(throws: PairingQRParseError.invalidTimestamp(field: "issuedAt")) {
            try qr(extra: #", "issuedAt":true"#)
        }
    }

    @Test func repairingSameComputerReplacesTheStoredHost() async throws {
        let context = try Context(replies: [
            .http(200, try Self.fixture("pair")), .http(200, try Self.fixture("pair")),
        ])
        defer { context.clean() }
        let firstQR = try qr(extra: #","deviceId":"dsh-computer""#)
        guard case .paired(let first) = await context.client.pair(attempt(firstQR))
        else {
            Issue.record("Expected first pairing")
            return
        }
        guard
            case .paired(let second) = await context.client.pair(
                .init(
                    qr: try qr(extra: #","deviceId":"dsh-computer""#, urls: ["https://192.168.10.42:18640"]),
                    deviceName: "iPhone", requestId: "request-456", hostId: "local-host-2"))
        else {
            Issue.record("Expected replacement pairing")
            return
        }
        let hosts = await context.store.all()
        #expect(hosts.count == 1)
        #expect(second.hostId == first.hostId)
        #expect(second.pluginHostId == "dsh-computer")
        #expect(second.primaryUrl == "https://192.168.10.42:18640")
        #expect(await context.store.token(for: first.hostId) == "<token>")
        #expect(await context.store.get(hostId: "local-host-2") == nil)
    }

    @Test func repairingSameFingerprintReplacesHostWithoutPluginIdentity() async throws {
        let context = try Context(replies: [.http(200, try Self.fixture("pair")), .http(200, try Self.fixture("pair"))])
        defer { context.clean() }
        guard case .paired(let first) = await context.client.pair(attempt(try qr())) else {
            Issue.record("Expected first pairing")
            return
        }
        guard
            case .paired(let second) = await context.client.pair(
                .init(
                    qr: try qr(urls: ["https://192.168.10.42:18640"]), deviceName: "iPhone", requestId: "request-456",
                    hostId: "local-host-2"))
        else {
            Issue.record("Expected fingerprint replacement")
            return
        }
        #expect(await context.store.all().count == 1)
        #expect(second.hostId == first.hostId)
        #expect(second.pluginHostId == nil)
        #expect(second.primaryUrl == "https://192.168.10.42:18640")
    }

    @Test func storageFailureDoesNotRetryAfterHostAcceptedPairing() async throws {
        let context = try Context(replies: [.http(200, try Self.fixture("pair"))])
        defer { context.clean() }
        try FileManager.default.createDirectory(
            at: context.directory.appendingPathComponent("hosts.json"), withIntermediateDirectories: true)
        #expect(
            await context.client.pair(attempt(try qr(urls: ["https://10.0.0.2", "https://10.0.0.3"])))
                == .failed(.storage))
        #expect(Stub.registry.records.count == 1)
    }

    @Test func actualTaskCancellationBeforePollLeavesCredentialsAndSendsNothing() async throws {
        let context = try Context(replies: [.http(200, try Self.fixture("pair-pending"))])
        defer { context.clean() }
        guard case .pending(let host, _) = await context.client.pair(attempt(try qr())) else {
            Issue.record("Expected pending")
            return
        }
        let poller = PairingApprovalPoller(store: context.store, session: context.session)
        let task = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await poller.wait(for: host)
        }
        do {
            _ = try await task.value
            Issue.record("Expected CancellationError")
        } catch is CancellationError {}
        #expect(Stub.registry.records.count == 1)
        #expect(await context.store.get(hostId: host.hostId) == host)
    }

    private actor Delays {
        var values: [Duration] = []
        func add(_ value: Duration) { values.append(value) }
    }

    @Test func pendingSavesThenPollsUnknownAndPendingUntilApprovedDespiteLocalExpiry() async throws {
        let context = try Context(
            replies: [
                .http(200, try Self.fixture("pair-pending")), .http(403, Data(#"{"pending":true}"#.utf8)),
                .http(403, Data("not-json".utf8)), .http(502, Data()), .network, .http(200, Data("not-json".utf8)),
            ], now: 1_900_000_000_000)
        defer { context.clean() }
        guard case .pending(let host, let expiresAt) = await context.client.pair(attempt(try qr())) else {
            Issue.record("Expected pending")
            return
        }
        #expect(expiresAt == 1_767_225_900_000)
        #expect(await context.store.token(for: host.hostId) == "<token>")
        let delays = Delays()
        let poller = PairingApprovalPoller(
            store: context.store, session: context.session, sleep: { await delays.add($0) })
        #expect(try await poller.wait(for: host) == .approved)
        #expect(await delays.values == Array(repeating: .seconds(2), count: 4))
        #expect(await context.store.get(hostId: host.hostId) == host)
        let polls = Stub.registry.records.dropFirst()
        #expect(polls.count == 5)
        #expect(
            polls.allSatisfy {
                $0.url?.path == "/dsh-link/mobile/sessions" && $0.httpMethod == "GET"
                    && $0.value(forHTTPHeaderField: HostClient.tokenHeaderName) == "<token>"
            })
    }

    @Test func rejectionDeletesOnlyPendingLocalCredentials() async throws {
        let context = try Context(replies: [.http(200, try Self.fixture("pair-pending")), .http(401, Data())])
        defer { context.clean() }
        guard case .pending(let host, _) = await context.client.pair(attempt(try qr())) else {
            Issue.record("Expected pending")
            return
        }
        let other = PairedHost(
            hostId: "other", name: "Other", primaryUrl: host.primaryUrl,
            certFingerprint: String(repeating: "cd", count: 32), pairedAt: 0)
        try await context.store.save(host: other, token: "other-token")
        let poller = PairingApprovalPoller(
            store: context.store, session: context.session, sleep: { _ in Issue.record("Must not sleep after 401") })
        #expect(try await poller.wait(for: host) == .rejected)
        #expect(await context.store.get(hostId: host.hostId) == nil)
        #expect(await context.store.token(for: host.hostId) == nil)
        #expect(await context.store.get(hostId: "other") == other)
    }

    @Test func cancellationCanStopWaitingAndExplicitCancelDeletesRecord() async throws {
        let context = try Context(replies: [
            .http(200, try Self.fixture("pair-pending")), .http(403, Data(#"{"pending":true}"#.utf8)),
        ])
        defer { context.clean() }
        guard case .pending(let host, _) = await context.client.pair(attempt(try qr())) else {
            Issue.record("Expected pending")
            return
        }
        let poller = PairingApprovalPoller(
            store: context.store, session: context.session, interval: .milliseconds(25),
            sleep: { duration in
                #expect(duration == .milliseconds(25))
                throw CancellationError()
            })
        do {
            _ = try await poller.wait(for: host)
            Issue.record("Expected cancellation")
        } catch is CancellationError {}
        #expect(await context.store.get(hostId: host.hostId) != nil)
        try await poller.discardPendingPairing(for: host)
        #expect(await context.store.get(hostId: host.hostId) == nil)
    }

    @Test(arguments: [200, 204, 299, 401, 403, 502])
    func approvalClassificationMatchesAndroid(status: Int) {
        let body = Data(#"{"pending":true}"#.utf8)
        let expected: PairingApprovalPoller.Approval =
            switch status {
            case 200...299: .approved
            case 401: .rejected
            case 403: .pending
            default: .unknown
            }
        #expect(PairingApprovalPoller.approval(status: status, body: body) == expected)
    }
}
