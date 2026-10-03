import Foundation

/// `GET /dsh-link/mobile/sessions`。
public struct SessionListResponse: Codable, Equatable, Sendable {
    public var version: Int?
    public var sessions: [SessionSummary]?
    public var archivedSessionIds: [String]?

    public init(
        version: Int? = nil,
        sessions: [SessionSummary]? = nil,
        archivedSessionIds: [String]? = nil
    ) {
        self.version = version
        self.sessions = sessions
        self.archivedSessionIds = archivedSessionIds
    }
}

/// `GET /dsh-link/mobile/sessions/search`。`degraded` 为真表示全文检索不可用、只按标题匹配。
public struct SessionSearchResponse: Codable, Equatable, Sendable {
    public var version: Int?
    public var items: [SessionSearchItem]?
    public var hasMore: Bool?
    public var degraded: Bool?

    public init(
        version: Int? = nil,
        items: [SessionSearchItem]? = nil,
        hasMore: Bool? = nil,
        degraded: Bool? = nil
    ) {
        self.version = version
        self.items = items
        self.hasMore = hasMore
        self.degraded = degraded
    }
}

public struct SessionSearchItem: Codable, Equatable, Sendable {
    public var sessionId: String?
    public var snippet: String?
    public var title: String?

    public init(sessionId: String? = nil, snippet: String? = nil, title: String? = nil) {
        self.sessionId = sessionId
        self.snippet = snippet
        self.title = title
    }
}

/// 会话行（`bootstrap.sessions` 与 `GET /dsh-link/mobile/sessions`；`src/mobile-session-summary.js`）。
/// 插件用 omitNullFields 下发，可选字段只在有意义时出现；回退语义见合同：
/// `activity` / `lastResult` / `stoppedReason` 缺失时按「运行中」/「已完成」展示。
public struct SessionSummary: Codable, Equatable, Sendable {
    public var sessionId: String?
    public var title: String?
    public var updatedAt: Int?
    public var running: Bool?
    public var blank: Bool?
    public var cwd: String?
    public var agentPreset: String?
    public var origin: String?
    public var parentSessionId: String?
    public var subagentCount: Int?
    /// 有尚未结束的审批或澄清问题；只在为真时下发，缺省即 false。
    public var awaitingInput: Bool?
    /// 只在 `running` 时下发。
    public var activity: SessionActivity?
    /// 只在非 `running` 时下发。
    public var lastResult: SessionLastResult?
    /// 这一轮怎么结束的（`turn/end` 的 `reason.kind`）；`completed` 或取不到时不下发。
    public var stoppedReason: String?

    public init(
        sessionId: String? = nil,
        title: String? = nil,
        updatedAt: Int? = nil,
        running: Bool? = nil,
        blank: Bool? = nil,
        cwd: String? = nil,
        agentPreset: String? = nil,
        origin: String? = nil,
        parentSessionId: String? = nil,
        subagentCount: Int? = nil,
        awaitingInput: Bool? = nil,
        activity: SessionActivity? = nil,
        lastResult: SessionLastResult? = nil,
        stoppedReason: String? = nil
    ) {
        self.sessionId = sessionId
        self.title = title
        self.updatedAt = updatedAt
        self.running = running
        self.blank = blank
        self.cwd = cwd
        self.agentPreset = agentPreset
        self.origin = origin
        self.parentSessionId = parentSessionId
        self.subagentCount = subagentCount
        self.awaitingInput = awaitingInput
        self.activity = activity
        self.lastResult = lastResult
        self.stoppedReason = stoppedReason
    }
}

/// 会话当前步骤（2026-09-28 重设计）。`kind` 决定文案；`tool` 时 `label` 是命令或参数摘要。
public struct SessionActivity: Codable, Equatable, Sendable {
    public var kind: SessionActivityKind?
    public var label: String?
    public var step: Int?
    public var startedAt: Int?

    public init(kind: SessionActivityKind? = nil, label: String? = nil, step: Int? = nil, startedAt: Int? = nil) {
        self.kind = kind
        self.label = label
        self.step = step
        self.startedAt = startedAt
    }
}

public enum SessionActivityKind: DLStringEnum {
    case tool
    case thinking
    case writing
    case unknown(String)

    public static func decoding(_ rawValue: String) -> Self {
        switch rawValue {
        case "tool": .tool
        case "thinking": .thinking
        case "writing": .writing
        default: .unknown(rawValue)
        }
    }

    public var encodedValue: String {
        switch self {
        case .tool: "tool"
        case .thinking: "thinking"
        case .writing: "writing"
        case .unknown(let raw): raw
        }
    }
}

/// 结果一句话：最后一条回复摘要 + 本轮改动统计，两者都可缺。
public struct SessionLastResult: Codable, Equatable, Sendable {
    public var text: String?
    public var files: Int?
    public var added: Int?
    public var deleted: Int?

    public init(text: String? = nil, files: Int? = nil, added: Int? = nil, deleted: Int? = nil) {
        self.text = text
        self.files = files
        self.added = added
        self.deleted = deleted
    }
}
