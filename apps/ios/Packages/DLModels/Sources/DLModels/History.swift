import Foundation

/// `GET /dsh-link/mobile/sessions/:id/history`（快照；SSE `message` 是增量）。
public struct HistoryResponse: Codable, Equatable, Sendable {
    public var ok: Bool?
    public var sessionId: String?
    public var messages: [HistoryMessage]?
    public var hasMore: Bool?
    /// 翻页游标：本页最早事件的 seq（下一次请求 `beforeSeq=` 该值）。
    public var nextBeforeSeq: Int?
    /// 本页最新事件的 seq（SSE 去重基线，只随 tail 页有意义）。
    public var maxSeq: Int?
    /// 会话被停止 / 失败 / 截断时最后一条 `turn/end` 的 reason.kind；正常完成时为 null。
    public var stoppedReason: String?
    public var stats: HistoryStats?
    /// 排队 / 引导中的消息（DSH durable inbox）；旧 Host 无此投影时为空。
    public var queue: [QueueItem]?
    /// 结构化目标；null 表示没有目标（旧插件 / 已清除）。
    public var goal: SessionGoal?

    public init(
        ok: Bool? = nil,
        sessionId: String? = nil,
        messages: [HistoryMessage]? = nil,
        hasMore: Bool? = nil,
        nextBeforeSeq: Int? = nil,
        maxSeq: Int? = nil,
        stoppedReason: String? = nil,
        stats: HistoryStats? = nil,
        queue: [QueueItem]? = nil,
        goal: SessionGoal? = nil
    ) {
        self.ok = ok
        self.sessionId = sessionId
        self.messages = messages
        self.hasMore = hasMore
        self.nextBeforeSeq = nextBeforeSeq
        self.maxSeq = maxSeq
        self.stoppedReason = stoppedReason
        self.stats = stats
        self.queue = queue
        self.goal = goal
    }
}

/// 消息展示类别（合同 v4 R2.3）：user / injection / goal_round / model_changed，其余与 role 相同。
/// `kind` 缺失（旧插件、实时流原始事件）时由 DLCore 的文本兜底给出。
public enum MessageKind: DLStringEnum {
    case user
    case injection
    case goalRound
    case modelChanged
    /// 其余取值等于投影的 `role`（assistant / tool_call / approval / reasoning / …）。
    case role(String)

    public static func decoding(_ rawValue: String) -> Self {
        switch rawValue {
        case "user": .user
        case "injection": .injection
        case "goal_round": .goalRound
        case "model_changed": .modelChanged
        default: .role(rawValue)
        }
    }

    public var encodedValue: String {
        switch self {
        case .user: "user"
        case .injection: "injection"
        case .goalRound: "goal_round"
        case .modelChanged: "model_changed"
        case .role(let raw): raw
        }
    }
}

/// 历史页消息（`src/history.js` 的 `projectHistoryPage`）。各 role 专有字段都可缺；
/// `role` 保持旧值（injection / goal_round 仍是 context_injection），App 以 `kind` 为准。
public struct HistoryMessage: Codable, Equatable, Sendable {
    public var id: String?
    public var seq: Int?
    public var role: String?
    public var kind: MessageKind?
    public var text: String?
    public var time: Int?
    public var type: String?
    // kind = injection
    public var labels: [String]?
    // kind = goal_round
    public var goal: MessageGoalRound?
    // approval
    public var toolName: String?
    public var approvalId: String?
    public var callId: String?
    public var requestStatus: RequestStatus?
    public var outcome: String?
    // tool_call（name 与 approval 的 toolName 同键不同义，工具名放这里）
    public var name: String?
    public var args: String?
    public var turn: Int?
    public var step: Int?
    // tool_result
    public var durationMs: Int?
    // reasoning / compaction
    public var running: Bool?
    // todo
    public var todos: [TodoItem]?
    // workspace_changes
    public var changes: ChangesSummary?
    // produced_files
    public var files: [String]?

    public init(
        id: String? = nil,
        seq: Int? = nil,
        role: String? = nil,
        kind: MessageKind? = nil,
        text: String? = nil,
        time: Int? = nil,
        type: String? = nil,
        labels: [String]? = nil,
        goal: MessageGoalRound? = nil,
        toolName: String? = nil,
        approvalId: String? = nil,
        callId: String? = nil,
        requestStatus: RequestStatus? = nil,
        outcome: String? = nil,
        name: String? = nil,
        args: String? = nil,
        turn: Int? = nil,
        step: Int? = nil,
        durationMs: Int? = nil,
        running: Bool? = nil,
        todos: [TodoItem]? = nil,
        changes: ChangesSummary? = nil,
        files: [String]? = nil
    ) {
        self.id = id
        self.seq = seq
        self.role = role
        self.kind = kind
        self.text = text
        self.time = time
        self.type = type
        self.labels = labels
        self.goal = goal
        self.toolName = toolName
        self.approvalId = approvalId
        self.callId = callId
        self.requestStatus = requestStatus
        self.outcome = outcome
        self.name = name
        self.args = args
        self.turn = turn
        self.step = step
        self.durationMs = durationMs
        self.running = running
        self.todos = todos
        self.changes = changes
        self.files = files
    }
}

/// `kind = goal_round` 时插件解析好的目标轮次；解析不到的字段为 null。
public struct MessageGoalRound: Codable, Equatable, Sendable {
    public var round: Int?
    public var maxRounds: Int?
    public var objective: String?

    public init(round: Int? = nil, maxRounds: Int? = nil, objective: String? = nil) {
        self.round = round
        self.maxRounds = maxRounds
        self.objective = objective
    }
}

public struct TodoItem: Codable, Equatable, Sendable {
    public var content: String?
    public var status: String?

    public init(content: String? = nil, status: String? = nil) {
        self.content = content
        self.status = status
    }
}

/// 会话统计投影（tokenUsage / sessionStats / contextPressure / contextBreakdown 为 DSH 定义，
/// 字段面与 Android `parseMobileSessionStats` 一致；`todos` 未建模，原样保留）。
public struct HistoryStats: Codable, Equatable, Sendable {
    public var tokenUsage: TokenUsage?
    public var sessionStats: SessionStats?
    public var contextPressure: ContextPressure?
    public var contextBreakdown: ContextBreakdown?
    public var todos: JSONValue?
    /// 能计价时的预估花费；未知模型且没有价格时省略，App 只显示 token。
    public var estimatedCost: EstimatedCost?

    public init(
        tokenUsage: TokenUsage? = nil,
        sessionStats: SessionStats? = nil,
        contextPressure: ContextPressure? = nil,
        contextBreakdown: ContextBreakdown? = nil,
        todos: JSONValue? = nil,
        estimatedCost: EstimatedCost? = nil
    ) {
        self.tokenUsage = tokenUsage
        self.sessionStats = sessionStats
        self.contextPressure = contextPressure
        self.contextBreakdown = contextBreakdown
        self.todos = todos
        self.estimatedCost = estimatedCost
    }
}

public struct TokenUsage: Codable, Equatable, Sendable {
    public var uncachedInputTokens: Int?
    public var cacheReadTokens: Int?
    public var outputTokens: Int?
    public var model: String?

    public init(
        uncachedInputTokens: Int? = nil,
        cacheReadTokens: Int? = nil,
        outputTokens: Int? = nil,
        model: String? = nil
    ) {
        self.uncachedInputTokens = uncachedInputTokens
        self.cacheReadTokens = cacheReadTokens
        self.outputTokens = outputTokens
        self.model = model
    }
}

public struct SessionStats: Codable, Equatable, Sendable {
    public var turns: Int?
    public var steps: Int?
    public var llmMs: Int?
    public var toolMs: Int?
    public var ttftMs: Int?
    public var ttftSteps: Int?
    public var decodeMs: Int?
    public var decodeTokens: Int?

    public init(
        turns: Int? = nil,
        steps: Int? = nil,
        llmMs: Int? = nil,
        toolMs: Int? = nil,
        ttftMs: Int? = nil,
        ttftSteps: Int? = nil,
        decodeMs: Int? = nil,
        decodeTokens: Int? = nil
    ) {
        self.turns = turns
        self.steps = steps
        self.llmMs = llmMs
        self.toolMs = toolMs
        self.ttftMs = ttftMs
        self.ttftSteps = ttftSteps
        self.decodeMs = decodeMs
        self.decodeTokens = decodeTokens
    }
}

public struct ContextPressure: Codable, Equatable, Sendable {
    public var projectedTokens: Int?
    /// 旧字段名，优先用 `projectedTokens`。
    public var pressureTokens: Int?
    public var contextWindow: Int?

    public init(projectedTokens: Int? = nil, pressureTokens: Int? = nil, contextWindow: Int? = nil) {
        self.projectedTokens = projectedTokens
        self.pressureTokens = pressureTokens
        self.contextWindow = contextWindow
    }
}

public struct ContextBreakdown: Codable, Equatable, Sendable {
    public var systemTokens: Int?
    public var toolsTokens: Int?
    public var messageTokens: Int?

    public init(systemTokens: Int? = nil, toolsTokens: Int? = nil, messageTokens: Int? = nil) {
        self.systemTokens = systemTokens
        self.toolsTokens = toolsTokens
        self.messageTokens = messageTokens
    }
}

/// 预估花费（合同「预估花费」）。内置表计价时带峰谷区间；Host 自带价格时没有区间。
public struct EstimatedCost: Codable, Equatable, Sendable {
    public var amount: Double?
    public var currency: String?
    public var priceDate: String?
    public var source: CostSource?
    public var amountMin: Double?
    public var amountMax: Double?

    public init(
        amount: Double? = nil,
        currency: String? = nil,
        priceDate: String? = nil,
        source: CostSource? = nil,
        amountMin: Double? = nil,
        amountMax: Double? = nil
    ) {
        self.amount = amount
        self.currency = currency
        self.priceDate = priceDate
        self.source = source
        self.amountMin = amountMin
        self.amountMax = amountMax
    }
}

public enum CostSource: DLStringEnum {
    case host
    case builtin
    case unknown(String)

    public static func decoding(_ rawValue: String) -> Self {
        switch rawValue {
        case "host": .host
        case "builtin": .builtin
        default: .unknown(rawValue)
        }
    }

    public var encodedValue: String {
        switch self {
        case .host: "host"
        case .builtin: "builtin"
        case .unknown(let raw): raw
        }
    }
}

/// 结构化目标（含 CAS 引用与 phase），App 据此提供暂停 / 继续 / 编辑 / 清除。
/// 投影由 DSH 定义，字段面与 Android `SessionGoal` 一致。
public struct SessionGoal: Codable, Equatable, Sendable {
    public var ref: SessionGoalRef?
    public var objective: String?
    public var phase: GoalPhase?
    public var maxGoalRounds: Int?
    public var roundsStarted: Int?

    public init(
        ref: SessionGoalRef? = nil,
        objective: String? = nil,
        phase: GoalPhase? = nil,
        maxGoalRounds: Int? = nil,
        roundsStarted: Int? = nil
    ) {
        self.ref = ref
        self.objective = objective
        self.phase = phase
        self.maxGoalRounds = maxGoalRounds
        self.roundsStarted = roundsStarted
    }
}

public struct SessionGoalRef: Codable, Equatable, Sendable {
    public var id: String?
    public var revision: Int?

    public init(id: String? = nil, revision: Int? = nil) {
        self.id = id
        self.revision = revision
    }
}

public enum GoalPhase: DLStringEnum {
    case active
    case paused
    case blocked
    case complete
    case unknown(String)

    public static func decoding(_ rawValue: String) -> Self {
        switch rawValue {
        case "active": .active
        case "paused": .paused
        case "blocked": .blocked
        case "complete": .complete
        default: .unknown(rawValue)
        }
    }

    public var encodedValue: String {
        switch self {
        case .active: "active"
        case .paused: "paused"
        case .blocked: "blocked"
        case .complete: "complete"
        case .unknown(let raw): raw
        }
    }
}
