import DLModels
import DLNet
import DLSecurity
import Foundation

enum SettingsModelsError: Error, Equatable, Sendable {
    case offline
    case missingHost
    case unauthorized
    case certificate
    case notWritable
    case conflict(String?)
    case rejected(String?)
    case emptyKey
    case invalidKey
    case invalidAmount
    case notAddable
    case modelsInherited
    case emptyModels
}

protocol SettingsModelsServing: Sendable {
    func settings() async throws -> MobileSettingsView
    func update(ns: String, patch: [String: String], expectedRevision: Int?) async throws -> MobileSettingsNamespace
    func presets() async throws -> [AgentPreset]
    func models() async throws -> SessionModelsResponse
    func balance(locale: String) async throws -> BalanceResponse
    func providers() async throws -> ProvidersResponse
    func addProvider(id: String, apiKey: String?) async throws -> ProvidersResponse
    func replaceCredential(provider: String, apiKey: String) async throws -> ProvidersResponse
    func discover(provider: String) async throws -> DiscoverModelsResponse
    func saveModels(provider: String, add: [DiscoveredModel], remove: [String]) async throws -> ProvidersResponse
}

actor SettingsModelsLiveService: SettingsModelsServing {
    private let hostID: String
    private let store: HostStore
    private let routes: RouteSelector
    private var client: HostClient?

    init(hostID: String, store: HostStore = HostStore(), routes: RouteSelector = RouteSelector()) {
        self.hostID = hostID
        self.store = store
        self.routes = routes
    }

    func settings() async throws -> MobileSettingsView {
        try await get("/dsh-link/mobile/settings")
    }

    func update(ns: String, patch: [String: String], expectedRevision: Int?) async throws -> MobileSettingsNamespace {
        try await post(
            "/dsh-link/mobile/settings/update",
            SettingsUpdateBody(ns: ns, patch: patch, expectedRevision: expectedRevision))
    }

    func presets() async throws -> [AgentPreset] {
        let response: AgentPresetListResponse = try await get("/dsh-link/mobile/agent-presets")
        return response.presets ?? []
    }

    func models() async throws -> SessionModelsResponse {
        try await get("/dsh-link/mobile/llm-models")
    }

    func balance(locale: String) async throws -> BalanceResponse {
        try await get("/dsh-link/mobile/balance", query: ["locale": locale])
    }

    func providers() async throws -> ProvidersResponse {
        try await get("/dsh-link/mobile/providers")
    }

    func addProvider(id: String, apiKey: String?) async throws -> ProvidersResponse {
        try await post("/dsh-link/mobile/providers/add", ProviderAddBody(provider: id, apiKey: apiKey))
    }

    func replaceCredential(provider: String, apiKey: String) async throws -> ProvidersResponse {
        try await post(
            "/dsh-link/mobile/providers/credential", ProviderCredentialBody(provider: provider, apiKey: apiKey))
    }

    func discover(provider: String) async throws -> DiscoverModelsResponse {
        try await post("/dsh-link/mobile/providers/discover", ProviderIDBody(provider: provider))
    }

    func saveModels(provider: String, add: [DiscoveredModel], remove: [String]) async throws -> ProvidersResponse {
        try await post(
            "/dsh-link/mobile/providers/models", ProviderModelsBody(provider: provider, add: add, remove: remove))
    }

    private func get<T: Decodable>(_ path: String, query: [String: String] = [:]) async throws -> T {
        let http = try await connect()
        do { return try await http.get(T.self, path: path, query: query) } catch { throw Self.map(error) }
    }

    private func post<T: Decodable>(_ path: String, _ body: some Encodable) async throws -> T {
        let http = try await connect()
        do { return try await http.post(T.self, path: path, json: body) } catch { throw Self.map(error) }
    }

    private func connect() async throws -> HostClient {
        if let client { return client }
        guard let host = await store.get(hostId: hostID) else { throw SettingsModelsError.missingHost }
        guard let token = await store.token(for: hostID), !token.isEmpty else {
            throw SettingsModelsError.missingHost
        }
        // §15.2：直连优先，不可达且有远程能力时走远程。
        guard let connection = await HostConnectionFactory.open(host: host, token: token, routes: routes) else {
            throw SettingsModelsError.offline
        }
        let http = connection.client
        client = http
        if let address = connection.directAddress {
            await routes.noteSuccess(key: hostID, address: address)
        }
        return http
    }

    /// 401 只上报。这里不删除凭据。
    private static func map(_ error: any Error) -> SettingsModelsError {
        guard let error = error as? HostClientError else { return .offline }
        switch error {
        case .unauthorized: return .unauthorized
        case .certificateChanged: return .certificate
        case .transport: return .offline
        case .conflict(let code): return .conflict(code)
        case .server(_, let code): return .rejected(code)
        case .forbidden, .sessionBusy, .capabilityMissing, .decoding: return .rejected(nil)
        }
    }

    private static func probe(address: String, fingerprint: String) async -> Bool {
        guard let url = URL(string: address) else { return false }
        let delegate = PinnedSessionDelegate(expectedFingerprint: fingerprint)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 1.2
        configuration.timeoutIntervalForResource = 1.2
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 1.2
        let before = delegate.pinFailureCount
        do {
            let (_, response) = try await session.data(for: request)
            session.finishTasksAndInvalidate()
            return response is HTTPURLResponse && delegate.pinFailureCount == before
        } catch {
            session.invalidateAndCancel()
            return false
        }
    }
}

private struct SettingsUpdateBody: Encodable {
    var ns: String
    var patch: [String: String]
    var expectedRevision: Int?

    func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(ns, forKey: .ns)
        try container.encode(patch, forKey: .patch)
        if let expectedRevision { try container.encode(expectedRevision, forKey: .expectedRevision) }
    }

    private enum CodingKeys: String, CodingKey { case ns, patch, expectedRevision }
}

private struct ProviderAddBody: Encodable {
    var provider: String
    var apiKey: String?

    func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(provider, forKey: .provider)
        if let apiKey, !apiKey.isEmpty { try container.encode(apiKey, forKey: .apiKey) }
    }

    private enum CodingKeys: String, CodingKey { case provider, apiKey }
}

private struct ProviderCredentialBody: Encodable {
    var provider: String
    var apiKey: String
}

private struct ProviderIDBody: Encodable { var provider: String }

private struct ProviderModelsBody: Encodable {
    var provider: String
    var add: [DiscoveredModel]
    var remove: [String]
}

struct BalanceAlertPreference: Equatable, Sendable {
    var enabled: Bool
    /// 十进制原样字符串。关闭或空白时不比较。
    var threshold: String?

    init(enabled: Bool = false, threshold: String? = nil) {
        self.enabled = enabled
        self.threshold = threshold
    }
}

enum BalanceAlert {
    static let fetchInterval: Duration = .seconds(5 * 60)

    static func parseAmount(_ raw: String) -> String? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, text.allSatisfy({ $0.isNumber || $0 == "." }) else { return nil }
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count <= 2, parts.allSatisfy({ !$0.isEmpty && $0.allSatisfy(\.isNumber) }) else { return nil }
        return text
    }

    /// ready 且充值余额严格小于阈值才提醒。读不出金额、未登录、失败或旧主机都不提醒。
    static func belowThreshold(status: BalanceStatus?, topUp: String?, preference: BalanceAlertPreference) -> Bool {
        guard preference.enabled, let threshold = preference.threshold.flatMap(parseAmount) else { return false }
        guard status == .ready, let topUp, let amount = parseAmount(topUp) else { return false }
        return compareDecimal(amount, threshold) == .orderedAscending
    }

    static func shouldFetch(lastAt: Date?, now: Date, interval: Duration = fetchInterval) -> Bool {
        guard let lastAt else { return true }
        return now.timeIntervalSince(lastAt) >= interval.seconds
    }

    private static func compareDecimal(_ lhs: String, _ rhs: String) -> ComparisonResult {
        let left = decimalParts(lhs)
        let right = decimalParts(rhs)
        let scale = max(left.fraction.count, right.fraction.count)
        let leftNumber = left.whole + left.fraction.padding(toLength: scale, withPad: "0", startingAt: 0)
        let rightNumber = right.whole + right.fraction.padding(toLength: scale, withPad: "0", startingAt: 0)
        let width = max(leftNumber.count, rightNumber.count)
        let leftPadded = String(repeating: "0", count: width - leftNumber.count) + leftNumber
        let rightPadded = String(repeating: "0", count: width - rightNumber.count) + rightNumber
        if leftPadded == rightPadded { return .orderedSame }
        return leftPadded < rightPadded ? .orderedAscending : .orderedDescending
    }

    private static func decimalParts(_ value: String) -> (whole: String, fraction: String) {
        let parts = value.split(separator: ".", omittingEmptySubsequences: false)
        let whole = String(parts.first ?? "0").replacing(/^0+/, with: "")
        return (whole.isEmpty ? "0" : whole, parts.count > 1 ? String(parts[1]) : "")
    }
}

enum ProviderInput {
    static func apiKeyProblem(_ raw: String) -> String? {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.isEmpty { return "empty" }
        if value.count > 1024 { return "too-long" }
        guard value.unicodeScalars.allSatisfy({ (0x21...0x7E).contains($0.value) }) else { return "characters" }
        if value.range(of: #"^[A-Z][A-Z0-9_]*=[^=]"#, options: .regularExpression) != nil { return "characters" }
        if let first = value.first, first == "\"" || first == "'" || first == "`", value.count > 1, value.last == first
        {
            return "characters"
        }
        return nil
    }
}

extension Duration {
    fileprivate var seconds: TimeInterval {
        TimeInterval(components.seconds) + TimeInterval(components.attoseconds) / 1e18
    }
}
