import Foundation

/// `GET /dsh-link/mobile/models?sessionId=`。当前项在 `current`，列表按供应商分组。
public struct SessionModelsResponse: Codable, Equatable, Sendable {
    public var version: Int?
    public var current: SessionModelCurrent?
    public var groups: [SessionModelGroup]?

    public init(version: Int? = nil, current: SessionModelCurrent? = nil, groups: [SessionModelGroup]? = nil) {
        self.version = version
        self.current = current
        self.groups = groups
    }
}

public struct SessionModelCurrent: Codable, Equatable, Sendable {
    public var provider: String?
    public var model: String?
    public var reasoningEffort: String?
    public var effort: String?

    public init(
        provider: String? = nil, model: String? = nil, reasoningEffort: String? = nil, effort: String? = nil
    ) {
        self.provider = provider
        self.model = model
        self.reasoningEffort = reasoningEffort
        self.effort = effort
    }

    public var effortValue: String? { reasoningEffort ?? effort }
}

public struct SessionModelGroup: Codable, Equatable, Sendable {
    public var provider: String?
    public var providerName: String?
    public var models: [SessionModelOption]?

    public init(provider: String? = nil, providerName: String? = nil, models: [SessionModelOption]? = nil) {
        self.provider = provider
        self.providerName = providerName
        self.models = models
    }
}

public struct SessionModelOption: Codable, Equatable, Sendable {
    public var id: String
    public var name: String?
    public var contextWindow: Int?
    public var maxTokens: Int?
    public var reasoningEfforts: [String]?
    public var defaultEffort: String?

    public init(
        id: String,
        name: String? = nil,
        contextWindow: Int? = nil,
        maxTokens: Int? = nil,
        reasoningEfforts: [String]? = nil,
        defaultEffort: String? = nil
    ) {
        self.id = id
        self.name = name
        self.contextWindow = contextWindow
        self.maxTokens = maxTokens
        self.reasoningEfforts = reasoningEfforts
        self.defaultEffort = defaultEffort
    }
}
