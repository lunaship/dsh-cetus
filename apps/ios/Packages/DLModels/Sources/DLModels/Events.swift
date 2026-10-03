import Foundation

/// `GET /dsh-link/mobile/events`（设备 token，SSE）的 `session/state` 帧。
/// 只有会话级状态，不含消息正文和工具参数；`id` 为单调 seq（`Last-Event-ID` 续传）。
public struct HostSessionStateEvent: Codable, Equatable, Sendable {
    /// 恒为 "session/state"。
    public var type: String?
    public var sessionId: String?
    public var state: HostSessionState?
    public var title: String?
    public var origin: HostEventOrigin?
    public var seq: Int?

    public init(
        type: String? = nil,
        sessionId: String? = nil,
        state: HostSessionState? = nil,
        title: String? = nil,
        origin: HostEventOrigin? = nil,
        seq: Int? = nil
    ) {
        self.type = type
        self.sessionId = sessionId
        self.state = state
        self.title = title
        self.origin = origin
        self.seq = seq
    }
}

public enum HostSessionState: DLStringEnum {
    case running
    case awaitingApproval
    case awaitingInput
    case completed
    case failed
    case stopped
    case unknown(String)

    public static func decoding(_ rawValue: String) -> Self {
        switch rawValue {
        case "running": .running
        case "awaitingApproval": .awaitingApproval
        case "awaitingInput": .awaitingInput
        case "completed": .completed
        case "failed": .failed
        case "stopped": .stopped
        default: .unknown(rawValue)
        }
    }

    public var encodedValue: String {
        switch self {
        case .running: "running"
        case .awaitingApproval: "awaitingApproval"
        case .awaitingInput: "awaitingInput"
        case .completed: "completed"
        case .failed: "failed"
        case .stopped: "stopped"
        case .unknown(let raw): raw
        }
    }
}

public enum HostEventOrigin: DLStringEnum {
    case user
    case subagent
    case schedule
    case unknown(String)

    public static func decoding(_ rawValue: String) -> Self {
        switch rawValue {
        case "user": .user
        case "subagent": .subagent
        case "schedule": .schedule
        default: .unknown(rawValue)
        }
    }

    public var encodedValue: String {
        switch self {
        case .user: "user"
        case .subagent: "subagent"
        case .schedule: "schedule"
        case .unknown(let raw): raw
        }
    }
}

/// 主机事件流接不上 `Last-Event-ID` 时下发的 `resync-required`（形状为 `src/host-events.js` 的
/// `{ reason, afterSeq }`）。与单会话流的 `ResyncRequiredEvent`（合同完整五字段）不同。
public struct HostEventResync: Codable, Equatable, Sendable {
    public var reason: String?
    public var afterSeq: Int?

    public init(reason: String? = nil, afterSeq: Int? = nil) {
        self.reason = reason
        self.afterSeq = afterSeq
    }
}

/// 单会话 SSE 的 `ready` 帧（订阅建立时下发，含能力与续传起点）。
public struct StreamReadyEvent: Codable, Equatable, Sendable {
    public var resumeSeq: Int?
    public var protocolVersion: Int?
    public var capabilities: PluginCapabilities?

    public init(
        resumeSeq: Int? = nil,
        protocolVersion: Int? = nil,
        capabilities: PluginCapabilities? = nil
    ) {
        self.resumeSeq = resumeSeq
        self.protocolVersion = protocolVersion
        self.capabilities = capabilities
    }

    private enum CodingKeys: String, CodingKey {
        case resumeSeq
        case protocolVersion = "protocol"
        case capabilities
    }
}

/// 单会话 SSE 的 `message` 帧：DSH 原始事件 `{ seq, type, time, data }`。
/// `data` 形状随 `type` 变化，I3.1 只保封套；分类与投影由 DLCore / 上层完成。
public struct SessionEventEnvelope: Codable, Equatable, Sendable {
    public var seq: Int?
    public var type: String?
    public var time: Int?
    public var data: JSONValue?

    public init(seq: Int? = nil, type: String? = nil, time: Int? = nil, data: JSONValue? = nil) {
        self.seq = seq
        self.type = type
        self.time = time
        self.data = data
    }
}

/// 单会话 SSE 的 `resync-required` 帧（合同「快照与增量」）：App 丢弃本轮不可证增量，
/// 拉快照后从快照游标继续；禁止靠无限断开重连修补缺口。
public struct ResyncRequiredEvent: Codable, Equatable, Sendable {
    public var sessionId: String?
    public var reason: String?
    public var afterSeq: Int?
    public var oldestAvailableSeq: Int?
    public var nextCursor: Int?

    public init(
        sessionId: String? = nil,
        reason: String? = nil,
        afterSeq: Int? = nil,
        oldestAvailableSeq: Int? = nil,
        nextCursor: Int? = nil
    ) {
        self.sessionId = sessionId
        self.reason = reason
        self.afterSeq = afterSeq
        self.oldestAvailableSeq = oldestAvailableSeq
        self.nextCursor = nextCursor
    }
}

/// 单会话 SSE 的 `stats` 帧：与 history 的 stats 同源的投影值（不含 estimatedCost）。
public struct StatsEvent: Codable, Equatable, Sendable {
    public var tokenUsage: TokenUsage?
    public var sessionStats: SessionStats?
    public var contextPressure: ContextPressure?
    public var contextBreakdown: ContextBreakdown?
    public var todos: JSONValue?

    public init(
        tokenUsage: TokenUsage? = nil,
        sessionStats: SessionStats? = nil,
        contextPressure: ContextPressure? = nil,
        contextBreakdown: ContextBreakdown? = nil,
        todos: JSONValue? = nil
    ) {
        self.tokenUsage = tokenUsage
        self.sessionStats = sessionStats
        self.contextPressure = contextPressure
        self.contextBreakdown = contextBreakdown
        self.todos = todos
    }
}
