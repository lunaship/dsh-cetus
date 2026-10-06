import DLModels
import DLNet
import DLSecurity
import Foundation
import Testing

@testable import DeepLinks

@Suite struct SettingsAccountTests {
    @Test func revokePrefersDeviceID() {
        let body = selfRevokeBody(deviceID: " phone-1 ")
        #expect(body == .device("phone-1"))
        #expect(body?.encoded == RevokeRequestBody(deviceId: "phone-1"))
    }

    @Test func revokeRejectsBlankDeviceID() {
        #expect(selfRevokeBody(deviceID: " ") == nil)
        #expect(selfRevokeBody(deviceID: nil) == nil)
    }

    @Test func selfDeviceIDRequiresUniqueName() {
        let rows = [
            DeviceRow(deviceId: "a", name: "iPhone", status: .active),
            DeviceRow(deviceId: "b", name: " iPhone ", status: .active),
        ]
        #expect(selfDeviceID(in: rows, pairedPhoneName: "iPhone") == nil)
        #expect(selfDeviceID(in: [rows[0]], pairedPhoneName: "iPhone") == "a")
    }

    @Test func credentialsStayUntilRevokeSucceeds() {
        #expect(shouldDeleteCredentials(after: .revoked))
        #expect(shouldDeleteCredentials(after: .alreadyUnauthorized))
        #expect(!shouldDeleteCredentials(after: .kept(.transport)))
        #expect(!shouldDeleteCredentials(after: .kept(.forbidden)))
        #expect(!shouldDeleteCredentials(after: .kept(.storage)))
    }

    @Test func renameStaysLocal() {
        let renamed = renameComputerLocally(alias: nil, originalName: "Mac", draft: " Studio ")
        #expect(renamed == ComputerRename(displayName: "Studio", storedAlias: "Studio"))
        let restored = renameComputerLocally(alias: "Studio", originalName: "Mac", draft: "Mac")
        #expect(restored?.storedAlias == nil)
        #expect(renameComputerLocally(alias: nil, originalName: "Mac", draft: " ") == nil)
        #expect(renameComputerLocally(alias: nil, originalName: "Mac", draft: String(repeating: "a", count: 65)) == nil)
    }

    @Test func diagnosticsKeepStableCodes() {
        let rows = diagnosticRows(
            [
                DiagnosticCheck(id: "host.rpc", status: .ok, code: "HOST_RPC_OK", detail: ["ms": .number(12)]),
                DiagnosticCheck(id: "", status: .fail, code: "HOST_RPC_FAILED"),
            ],
            locale: Locale(identifier: "en")
        )
        #expect(rows.count == 1)
        #expect(rows[0].code == "HOST_RPC_OK")
        #expect(rows[0].subtitle == "Responded in 12 ms")
    }

    @Test func crashExportRedactsSecretsAndDropsEmptyReports() {
        let raw = """
            crash /Users/me/Library/token.txt Bearer abcdefghijklmnopqrstuvwxyz123456
            token=secret-value "message":"private reply"
            """
        let shared = crashShareText(raw)
        #expect(shared?.contains("/Users/me") == false)
        #expect(shared?.contains("abcdefghijklmnopqrstuvwxyz123456") == false)
        #expect(shared?.contains("secret-value") == false)
        #expect(shared?.contains("private reply") == false)
        #expect(shared?.contains("<path>") == true)
        #expect(shared?.contains("<redacted>") == true)
        #expect(crashShareText("   ") == nil)
        #expect(crashShareText(nil) == nil)
    }

    @Test func serviceDeletesCredentialsOnlyAfterSuccessfulRevoke() async throws {
        let fixture = try await AccountFixture()
        fixture.transport.routes["/dsh-link/mobile/devices"] = .json(
            """
            {"devices":[{"deviceId":"phone-1","name":"iPhone","status":"active"}]}
            """
        )
        fixture.transport.routes["/dsh-link/mobile/revoke"] = .status(503, "{\"error\":\"down\"}")

        let failed = await fixture.service.unpair()
        #expect(failed == .kept(.transport))
        #expect(await fixture.store.get(hostId: fixture.hostID) != nil)
        #expect(await fixture.store.token(for: fixture.hostID) == fixture.token)

        fixture.transport.routes["/dsh-link/mobile/revoke"] = .json("{\"ok\":true,\"removed\":1}")
        let revoked = await fixture.service.unpair()
        #expect(revoked == .revoked)
        #expect(await fixture.store.get(hostId: fixture.hostID) == nil)
        #expect(await fixture.store.token(for: fixture.hostID) == nil)
        let bodies = fixture.transport.bodies(for: "/dsh-link/mobile/revoke")
        #expect(bodies.contains(#"{"deviceId":"phone-1"}"#))
        #expect(!bodies.joined().contains("Mac"))
    }

    @Test func serviceKeepsCredentialsWhenRevokeIsForbidden() async throws {
        let fixture = try await AccountFixture()
        fixture.transport.routes["/dsh-link/mobile/devices"] = .status(403, "{\"error\":\"forbidden\"}")
        fixture.transport.routes["/dsh-link/mobile/revoke"] = .status(403, "{\"error\":\"forbidden\"}")

        let outcome = await fixture.service.unpair()
        #expect(outcome == .kept(.transport))
        #expect(await fixture.store.token(for: fixture.hostID) == fixture.token)
        #expect(fixture.transport.bodies(for: "/dsh-link/mobile/revoke").isEmpty)
    }

    @Test func ambiguousOrBlankDevicesDoNotRevoke() async throws {
        let duplicate = try await AccountFixture()
        duplicate.transport.routes["/dsh-link/mobile/devices"] = .json(
            """
            {"devices":[
            {"deviceId":"a","name":"iPhone","status":"active"},
            {"deviceId":"b","name":"iPhone","status":"active"}]}
            """
        )
        duplicate.transport.routes["/dsh-link/mobile/revoke"] = .json("{\"ok\":true}")
        #expect(await duplicate.service.unpair() == .kept(.unavailable))
        #expect(duplicate.transport.bodies(for: "/dsh-link/mobile/revoke").isEmpty)
        #expect(await duplicate.store.token(for: duplicate.hostID) == duplicate.token)

        let blank = try await AccountFixture()
        blank.transport.routes["/dsh-link/mobile/devices"] = .json(
            """
            {"devices":[{"deviceId":"  ","name":"iPhone","status":"active"}]}
            """
        )
        blank.transport.routes["/dsh-link/mobile/revoke"] = .json("{\"ok\":true}")
        #expect(await blank.service.unpair() == .kept(.unavailable))
        #expect(blank.transport.bodies(for: "/dsh-link/mobile/revoke").isEmpty)
        #expect(await blank.store.token(for: blank.hostID) == blank.token)
    }

    @Test func unauthorizedRevokeDeletesOnlyAfterUniqueDeviceRequest() async throws {
        let fixture = try await AccountFixture()
        fixture.transport.routes["/dsh-link/mobile/devices"] = .json(
            """
            {"devices":[{"deviceId":"phone-1","name":"iPhone","status":"active"}]}
            """
        )
        fixture.transport.routes["/dsh-link/mobile/revoke"] = .status(401, "{\"error\":\"unauthorized\"}")
        #expect(await fixture.service.unpair() == .alreadyUnauthorized)
        #expect(await fixture.store.token(for: fixture.hostID) == nil)
        #expect(fixture.transport.bodies(for: "/dsh-link/mobile/revoke") == [#"{"deviceId":"phone-1"}"#])
    }

    @Test func renameWritesLocalAliasWithoutNetwork() async throws {
        let fixture = try await AccountFixture()
        let renamed = await fixture.service.renameComputer(" Studio ")
        #expect(renamed == ComputerRename(displayName: "Studio", storedAlias: "Studio"))
        let loaded = await fixture.service.loadComputer()
        #expect(loaded.displayName == "Studio")
        #expect(loaded.originalName == "Mac")
        #expect(await fixture.store.get(hostId: fixture.hostID)?.name == "Mac")
        #expect(fixture.transport.requests.isEmpty)
        #expect(await fixture.service.renameComputer(" ") == nil)
    }

    @Test func diagnosticsReadsMobileRoute() async throws {
        let fixture = try await AccountFixture()
        fixture.transport.routes["/dsh-link/mobile/diagnostics"] = .json(
            """
            {"version":1,"generatedAt":1730000000,"checks":[{"id":"host.rpc","status":"ok","code":"HOST_RPC_OK"}]}
            """
        )
        let report = try await fixture.service.diagnostics()
        #expect(report.checks?.first?.code == "HOST_RPC_OK")
        #expect(fixture.transport.requests.map(\.path) == ["/dsh-link/mobile/diagnostics"])
    }
}

private struct AccountFixture {
    let hostID = "host-1"
    let token = "token-1"
    let store: HostStore
    let names: ComputerLocalNames
    let transport: SettingsScriptedTransport
    let service: SettingsAccountService

    init() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("settings-account-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let secure = InMemorySecureStore()
        let store = HostStore(
            fileURL: directory.appendingPathComponent("hosts.json"), secureStore: secure)
        let host = PairedHost(
            hostId: hostID, name: "Mac", primaryUrl: "https://host.example:18640",
            certFingerprint: "sha256/ab", pairedAt: 1)
        try await store.save(host: host, token: token)
        let defaults = UserDefaults(suiteName: "settings-account-\(UUID().uuidString)")!
        defaults.removePersistentDomain(forName: defaults.dictionaryRepresentation().keys.joined())
        let names = ComputerLocalNames(defaults: defaults)
        names.setPhoneName("iPhone", hostID: hostID)
        let transport = SettingsScriptedTransport()
        self.store = store
        self.names = names
        self.transport = transport
        service = SettingsAccountService(
            client: { [transport] in
                HostClient(
                    baseURL: URL(string: "https://\(transport.id).settings.test:18640")!, token: "token-1",
                    session: transport.session)
            },
            host: { await store.get(hostId: "host-1") },
            rename: { alias in names.setAlias(alias, hostID: "host-1") },
            alias: { names.alias(hostID: "host-1") },
            phoneName: { names.phoneName(hostID: "host-1") },
            deleteHost: { try await store.delete(hostId: "host-1") }
        )
    }
}

private final class SettingsScriptedTransport: @unchecked Sendable {
    enum Route {
        case json(String)
        case status(Int, String)
    }

    struct Call: Equatable {
        var path: String
        var body: String
    }

    let id = UUID().uuidString.lowercased()
    var routes: [String: Route] = [:]
    private(set) var requests: [Call] = []
    private let lock = NSLock()

    var session: URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SettingsScriptedProtocol.self]
        SettingsScriptedProtocol.register(self)
        return URLSession(configuration: configuration)
    }

    func bodies(for path: String) -> [String] {
        lock.withLock { requests.filter { $0.path == path }.map(\.body) }
    }

    fileprivate func answer(_ request: URLRequest) -> (Int, Data) {
        let path = request.url?.path ?? ""
        let body = Self.bodyText(request)
        lock.withLock { requests.append(Call(path: path, body: body)) }
        switch routes[path] {
        case .json(let text): return (200, Data(text.utf8))
        case .status(let status, let text): return (status, Data(text.utf8))
        case nil: return (404, Data("{}".utf8))
        }
    }

    private static func bodyText(_ request: URLRequest) -> String {
        if let body = request.httpBody { return String(decoding: body, as: UTF8.self) }
        guard let stream = request.httpBodyStream else { return "" }
        stream.open()
        defer { stream.close() }
        var data = Data()
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: 1024)
        defer { buffer.deallocate() }
        while stream.hasBytesAvailable {
            let count = stream.read(buffer, maxLength: 1024)
            if count <= 0 { break }
            data.append(buffer, count: count)
        }
        return String(decoding: data, as: UTF8.self)
    }
}

private final class SettingsScriptedProtocol: URLProtocol, @unchecked Sendable {
    private static let registry = SettingsTransportRegistry()

    static func register(_ transport: SettingsScriptedTransport) {
        registry.store(transport)
    }

    override class func canInit(with request: URLRequest) -> Bool {
        request.url?.host?.hasSuffix(".settings.test") == true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let id = request.url?.host?.split(separator: ".").first.map(String.init) ?? ""
        let answer = Self.registry.transport(id: id)?.answer(request) ?? (404, Data("{}".utf8))
        let response = HTTPURLResponse(
            url: request.url ?? URL(string: "https://host.example")!, statusCode: answer.0, httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: answer.1)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

private final class SettingsTransportRegistry: @unchecked Sendable {
    private let lock = NSLock()
    private var transports: [String: SettingsScriptedTransport] = [:]

    func store(_ transport: SettingsScriptedTransport) {
        lock.withLock { transports[transport.id] = transport }
    }

    func transport(id: String) -> SettingsScriptedTransport? {
        lock.withLock { transports[id] }
    }
}
