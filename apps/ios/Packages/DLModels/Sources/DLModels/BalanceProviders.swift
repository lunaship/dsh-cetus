import Foundation

/// `GET /dsh-link/mobile/balance?locale=`：恒回 200，状态用 `status` 区分。
/// `balance` 是平台原样十进制字符串，App 不做数值运算。
public struct BalanceResponse: Codable, Equatable, Sendable {
    public var version: Int?
    public var status: BalanceStatus?
    public var wallets: [Wallet]?
    public var bonusWallets: [Wallet]?

    public init(
        version: Int? = nil,
        status: BalanceStatus? = nil,
        wallets: [Wallet]? = nil,
        bonusWallets: [Wallet]? = nil
    ) {
        self.version = version
        self.status = status
        self.wallets = wallets
        self.bonusWallets = bonusWallets
    }
}

public enum BalanceStatus: DLStringEnum {
    /// 主机已登录 DeepSeek 账户，钱包有值。
    case ready
    /// 主机未登录 DeepSeek 账户。
    case signedOut
    /// 平台查询失败。
    case failed
    /// 旧 DSH 没有 account/getBalance。
    case unavailable
    case unknown(String)

    public static func decoding(_ rawValue: String) -> Self {
        switch rawValue {
        case "ready": .ready
        case "signed-out": .signedOut
        case "failed": .failed
        case "unavailable": .unavailable
        default: .unknown(rawValue)
        }
    }

    public var encodedValue: String {
        switch self {
        case .ready: "ready"
        case .signedOut: "signed-out"
        case .failed: "failed"
        case .unavailable: "unavailable"
        case .unknown(let raw): raw
        }
    }
}

public struct Wallet: Codable, Equatable, Sendable {
    public var currency: String?
    public var balance: String?

    public init(currency: String? = nil, balance: String? = nil) {
        self.currency = currency
        self.balance = balance
    }
}

/// `GET /dsh-link/mobile/providers`（以及各写接口的刷新响应，写接口额外带 `ok`）。
/// 行序与桌面一致；不下发 baseURL / api 等路由字段，更不下发密钥值。
public struct ProvidersResponse: Codable, Equatable, Sendable {
    public var version: Int?
    /// 仅写接口为 true。
    public var ok: Bool?
    public var writable: Bool?
    public var providers: [ProviderRow]?
    public var addable: [AddableProvider]?

    public init(
        version: Int? = nil,
        ok: Bool? = nil,
        writable: Bool? = nil,
        providers: [ProviderRow]? = nil,
        addable: [AddableProvider]? = nil
    ) {
        self.version = version
        self.ok = ok
        self.writable = writable
        self.providers = providers
        self.addable = addable
    }
}

public struct ProviderRow: Codable, Equatable, Sendable {
    public var provider: String?
    public var displayName: String?
    public var kind: ProviderKind?
    public var active: Bool?
    /// true = 目录声明并已配置；false = 账户型或纯注册路由。
    public var custom: Bool?
    /// 密钥写入的凭据 ref（如 `OPENAI_API_KEY`）。
    public var keyRef: String?
    public var credential: CredentialStatus?
    public var models: [ProviderModel]?
    /// 仅 profile 显式带 models 数组时为 true，否则写接口返回 409 `models-inherited`。
    public var modelsEditable: Bool?
    public var canDiscover: Bool?

    public init(
        provider: String? = nil,
        displayName: String? = nil,
        kind: ProviderKind? = nil,
        active: Bool? = nil,
        custom: Bool? = nil,
        keyRef: String? = nil,
        credential: CredentialStatus? = nil,
        models: [ProviderModel]? = nil,
        modelsEditable: Bool? = nil,
        canDiscover: Bool? = nil
    ) {
        self.provider = provider
        self.displayName = displayName
        self.kind = kind
        self.active = active
        self.custom = custom
        self.keyRef = keyRef
        self.credential = credential
        self.models = models
        self.modelsEditable = modelsEditable
        self.canDiscover = canDiscover
    }
}

public enum ProviderKind: DLStringEnum {
    case account
    case api
    case unknown(String)

    public static func decoding(_ rawValue: String) -> Self {
        switch rawValue {
        case "account": .account
        case "api": .api
        default: .unknown(rawValue)
        }
    }

    public var encodedValue: String {
        switch self {
        case .account: "account"
        case .api: "api"
        case .unknown(let raw): raw
        }
    }
}

public struct CredentialStatus: Codable, Equatable, Sendable {
    public var configured: Bool?
    public var writable: Bool?
    /// 来源短名（settings / environment 等，只读来源不可在手机改密钥）。
    public var source: String?

    public init(configured: Bool? = nil, writable: Bool? = nil, source: String? = nil) {
        self.configured = configured
        self.writable = writable
        self.source = source
    }
}

public struct ProviderModel: Codable, Equatable, Sendable {
    public var id: String?
    public var name: String?
    public var contextWindow: Int?
    public var maxTokens: Int?

    public init(id: String? = nil, name: String? = nil, contextWindow: Int? = nil, maxTokens: Int? = nil) {
        self.id = id
        self.name = name
        self.contextWindow = contextWindow
        self.maxTokens = maxTokens
    }
}

public struct AddableProvider: Codable, Equatable, Sendable {
    public var provider: String?
    public var displayName: String?

    public init(provider: String? = nil, displayName: String? = nil) {
        self.provider = provider
        self.displayName = displayName
    }
}

/// `POST /dsh-link/mobile/providers/discover`：候选模型列表，由用户勾选后再走 providers/models 写入。
public struct DiscoverModelsResponse: Codable, Equatable, Sendable {
    public var version: Int?
    public var provider: String?
    public var models: [DiscoveredModel]?

    public init(version: Int? = nil, provider: String? = nil, models: [DiscoveredModel]? = nil) {
        self.version = version
        self.provider = provider
        self.models = models
    }
}

public struct DiscoveredModel: Codable, Equatable, Sendable {
    public var id: String?
    public var name: String?
    public var contextWindow: Int?
    public var maxTokens: Int?
    /// 仅 text / image。
    public var inputModalities: [String]?

    public init(
        id: String? = nil,
        name: String? = nil,
        contextWindow: Int? = nil,
        maxTokens: Int? = nil,
        inputModalities: [String]? = nil
    ) {
        self.id = id
        self.name = name
        self.contextWindow = contextWindow
        self.maxTokens = maxTokens
        self.inputModalities = inputModalities
    }
}
