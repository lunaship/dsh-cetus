import Foundation

/// `GET /dsh-link/mobile/settings`。值已由插件脱敏。
public struct MobileSettingsView: Codable, Equatable, Sendable {
    public var version: Int?
    public var writable: Bool?
    public var namespaces: [MobileSettingsNamespace]?

    public init(version: Int? = nil, writable: Bool? = nil, namespaces: [MobileSettingsNamespace]? = nil) {
        self.version = version
        self.writable = writable
        self.namespaces = namespaces
    }
}

/// `POST /dsh-link/mobile/settings/update` 的读回。
public struct MobileSettingsNamespace: Codable, Equatable, Sendable {
    public var ns: String?
    public var value: JSONValue?
    public var user: JSONValue?
    public var applies: String?
    public var secrets: [MobileSettingsSecret]?
    public var revision: Int?

    public init(
        ns: String? = nil,
        value: JSONValue? = nil,
        user: JSONValue? = nil,
        applies: String? = nil,
        secrets: [MobileSettingsSecret]? = nil,
        revision: Int? = nil
    ) {
        self.ns = ns
        self.value = value
        self.user = user
        self.applies = applies
        self.secrets = secrets
        self.revision = revision
    }
}

public struct MobileSettingsSecret: Codable, Equatable, Sendable {
    public var path: [String]?
    public var set: Bool?

    public init(path: [String]? = nil, set: Bool? = nil) {
        self.path = path
        self.set = set
    }
}

/// 7.6 对话默认。只保留插件实际返回的字符串，不在客户端补默认目录。
public struct ConversationDefaults: Equatable, Sendable {
    public var agentPreset: String?
    public var permissionPreset: String?
    public var busyEnter: String?
    public var modelProvider: String?
    public var model: String?
    public var reasoningEffort: String?
    public var revisions: [String: Int]

    public init(
        agentPreset: String? = nil,
        permissionPreset: String? = nil,
        busyEnter: String? = nil,
        modelProvider: String? = nil,
        model: String? = nil,
        reasoningEffort: String? = nil,
        revisions: [String: Int] = [:]
    ) {
        self.agentPreset = agentPreset
        self.permissionPreset = permissionPreset
        self.busyEnter = busyEnter
        self.modelProvider = modelProvider
        self.model = model
        self.reasoningEffort = reasoningEffort
        self.revisions = revisions
    }

    public static func from(namespaces: [MobileSettingsNamespace]) -> ConversationDefaults {
        var byName: [String: MobileSettingsNamespace] = [:]
        for item in namespaces {
            if let name = item.ns { byName[name] = item }
        }
        func text(_ ns: String, _ key: String) -> String? {
            guard case .object(let object) = byName[ns]?.value, case .string(let raw) = object[key] else {
                return nil
            }
            let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }
        var revisions: [String: Int] = [:]
        for name in ["agent-presets", "permission", "ui-conversation", "agent-default-model"] {
            if let revision = byName[name]?.revision { revisions[name] = revision }
        }
        return ConversationDefaults(
            agentPreset: text("agent-presets", "default"),
            permissionPreset: text("permission", "defaultPreset"),
            busyEnter: text("ui-conversation", "busyEnter"),
            modelProvider: text("agent-default-model", "provider"),
            model: text("agent-default-model", "model"),
            reasoningEffort: text("agent-default-model", "reasoningEffort"),
            revisions: revisions
        )
    }
}
