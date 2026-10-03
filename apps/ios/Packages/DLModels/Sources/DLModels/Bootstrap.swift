import Foundation

/// `GET /dsh-link/mobile/bootstrap`。`remote` 是三态（缺失 / null / 对象），见 `RemoteAvailability`。
/// 响应里的 `relay` 恒为 `null`（旧版 DLR/1 已下线），对 iOS 无语义，作为未知字段忽略。
public struct BootstrapResponse: Codable, Equatable, Sendable {
    public var version: Int?
    public var protocolVersion: Int?
    public var capabilities: PluginCapabilities?
    public var host: BootstrapHost?
    public var device: BootstrapDevice?
    public var sessions: [SessionSummary]?
    public var archivedSessionIds: [String]?
    public var webPath: String?
    public var remote: RemoteAvailability

    public init(
        version: Int? = nil,
        protocolVersion: Int? = nil,
        capabilities: PluginCapabilities? = nil,
        host: BootstrapHost? = nil,
        device: BootstrapDevice? = nil,
        sessions: [SessionSummary]? = nil,
        archivedSessionIds: [String]? = nil,
        webPath: String? = nil,
        remote: RemoteAvailability = .absent
    ) {
        self.version = version
        self.protocolVersion = protocolVersion
        self.capabilities = capabilities
        self.host = host
        self.device = device
        self.sessions = sessions
        self.archivedSessionIds = archivedSessionIds
        self.webPath = webPath
        self.remote = remote
    }

    private enum CodingKeys: String, CodingKey {
        case version
        case protocolVersion = "protocol"
        case capabilities
        case host
        case device
        case sessions
        case archivedSessionIds
        case webPath
        case remote
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        version = try container.decodeIfPresent(Int.self, forKey: .version)
        protocolVersion = try container.decodeIfPresent(Int.self, forKey: .protocolVersion)
        capabilities = try container.decodeIfPresent(PluginCapabilities.self, forKey: .capabilities)
        host = try container.decodeIfPresent(BootstrapHost.self, forKey: .host)
        device = try container.decodeIfPresent(BootstrapDevice.self, forKey: .device)
        sessions = try container.decodeIfPresent([SessionSummary].self, forKey: .sessions)
        archivedSessionIds = try container.decodeIfPresent([String].self, forKey: .archivedSessionIds)
        webPath = try container.decodeIfPresent(String.self, forKey: .webPath)
        if container.contains(.remote) {
            if try container.decodeNil(forKey: .remote) {
                remote = .disabled
            } else {
                remote = .enabled(try container.decode(DeviceRemoteInfo.self, forKey: .remote))
            }
        } else {
            remote = .absent
        }
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(version, forKey: .version)
        try container.encodeIfPresent(protocolVersion, forKey: .protocolVersion)
        try container.encodeIfPresent(capabilities, forKey: .capabilities)
        try container.encodeIfPresent(host, forKey: .host)
        try container.encodeIfPresent(device, forKey: .device)
        try container.encodeIfPresent(sessions, forKey: .sessions)
        try container.encodeIfPresent(archivedSessionIds, forKey: .archivedSessionIds)
        try container.encodeIfPresent(webPath, forKey: .webPath)
        switch remote {
        case .absent: break
        case .disabled: try container.encodeNil(forKey: .remote)
        case .enabled(let info): try container.encode(info, forKey: .remote)
        }
    }
}

/// 能力协商（`src/protocol-caps.js` 的 `pluginCapabilities()`）。App 只在声明时显示入口。
public struct PluginCapabilities: Codable, Equatable, Sendable {
    public var protocolVersion: Int?
    public var sync: SyncCapabilities?
    public var questions: QuestionCapabilities?
    public var requests: RequestCapabilities?
    public var control: ControlCapabilities?
    public var files: FileCapabilities?
    public var diagnostics: DiagnosticsCapabilities?
    public var events: EventsCapabilities?
    public var preview: PreviewCapabilities?

    public init(
        protocolVersion: Int? = nil,
        sync: SyncCapabilities? = nil,
        questions: QuestionCapabilities? = nil,
        requests: RequestCapabilities? = nil,
        control: ControlCapabilities? = nil,
        files: FileCapabilities? = nil,
        diagnostics: DiagnosticsCapabilities? = nil,
        events: EventsCapabilities? = nil,
        preview: PreviewCapabilities? = nil
    ) {
        self.protocolVersion = protocolVersion
        self.sync = sync
        self.questions = questions
        self.requests = requests
        self.control = control
        self.files = files
        self.diagnostics = diagnostics
        self.events = events
        self.preview = preview
    }

    private enum CodingKeys: String, CodingKey {
        case protocolVersion = "protocol"
        case sync
        case questions
        case requests
        case control
        case files
        case diagnostics
        case events
        case preview
    }
}

public struct SyncCapabilities: Codable, Equatable, Sendable {
    public var resync: Bool?
    public var catchupIntegrity: Bool?

    public init(resync: Bool? = nil, catchupIntegrity: Bool? = nil) {
        self.resync = resync
        self.catchupIntegrity = catchupIntegrity
    }
}

public struct QuestionCapabilities: Codable, Equatable, Sendable {
    public var multi: Bool?
    public var serverValidation: Bool?

    public init(multi: Bool? = nil, serverValidation: Bool? = nil) {
        self.multi = multi
        self.serverValidation = serverValidation
    }
}

public struct RequestCapabilities: Codable, Equatable, Sendable {
    public var snapshot: Bool?
    public var reconnectGraceMs: Int?

    public init(snapshot: Bool? = nil, reconnectGraceMs: Int? = nil) {
        self.snapshot = snapshot
        self.reconnectGraceMs = reconnectGraceMs
    }
}

public struct ControlCapabilities: Codable, Equatable, Sendable {
    public var queue: Bool?
    public var goals: Bool?
    public var schedules: Bool?

    public init(queue: Bool? = nil, goals: Bool? = nil, schedules: Bool? = nil) {
        self.queue = queue
        self.goals = goals
        self.schedules = schedules
    }
}

public struct FileCapabilities: Codable, Equatable, Sendable {
    public var workspace: Bool?
    public var maxBytes: Int?
    public var tree: Bool?
    public var treeMaxEntries: Int?
    public var sha256: Bool?
    public var changes: Bool?
    public var diff: Bool?
    public var diffMaxLines: Int?

    public init(
        workspace: Bool? = nil,
        maxBytes: Int? = nil,
        tree: Bool? = nil,
        treeMaxEntries: Int? = nil,
        sha256: Bool? = nil,
        changes: Bool? = nil,
        diff: Bool? = nil,
        diffMaxLines: Int? = nil
    ) {
        self.workspace = workspace
        self.maxBytes = maxBytes
        self.tree = tree
        self.treeMaxEntries = treeMaxEntries
        self.sha256 = sha256
        self.changes = changes
        self.diff = diff
        self.diffMaxLines = diffMaxLines
    }
}

public struct DiagnosticsCapabilities: Codable, Equatable, Sendable {
    public var v: Int?

    public init(v: Int? = nil) {
        self.v = v
    }
}

public struct EventsCapabilities: Codable, Equatable, Sendable {
    public var host: Bool?

    public init(host: Bool? = nil) {
        self.host = host
    }
}

public struct PreviewCapabilities: Codable, Equatable, Sendable {
    public var v: Int?
    public var detect: Int?

    public init(v: Int? = nil, detect: Int? = nil) {
        self.v = v
        self.detect = detect
    }
}

public struct BootstrapHost: Codable, Equatable, Sendable {
    public var name: String?
    public var deviceId: String?

    public init(name: String? = nil, deviceId: String? = nil) {
        self.name = name
        self.deviceId = deviceId
    }
}

public struct BootstrapDevice: Codable, Equatable, Sendable {
    public var name: String?

    public init(name: String? = nil) {
        self.name = name
    }
}
