import Foundation

/// 排队 / 引导中的消息（history `queue` 数组；DSH durable inbox 投影）。
public struct QueueItem: Codable, Equatable, Sendable {
    public var id: String?
    public var placement: QueuePlacement?
    public var text: String?
    public var images: Int?

    public init(id: String? = nil, placement: QueuePlacement? = nil, text: String? = nil, images: Int? = nil) {
        self.id = id
        self.placement = placement
        self.text = text
        self.images = images
    }
}

public enum QueuePlacement: DLStringEnum {
    /// next-turn：下一轮整条发送。
    case queued
    /// next-step 且来源为用户：引导（steering）。
    case steering
    /// next-step 的其余来源：上下文。
    case context
    case unknown(String)

    public static func decoding(_ rawValue: String) -> Self {
        switch rawValue {
        case "queued": .queued
        case "steering": .steering
        case "context": .context
        default: .unknown(rawValue)
        }
    }

    public var encodedValue: String {
        switch self {
        case .queued: "queued"
        case .steering: "steering"
        case .context: "context"
        case .unknown(let raw): raw
        }
    }
}

/// `POST /dsh-link/mobile/sessions/:id/prompt`。`result` 是 DSH session.prompt 的原样返回。
/// 409 `session_busy` 表示会话被其他写方占用：提示换会话，不得自动重试或转排队。
public struct PromptResponse: Codable, Equatable, Sendable {
    public var ok: Bool?
    public var result: JSONValue?

    public init(ok: Bool? = nil, result: JSONValue? = nil) {
        self.ok = ok
        self.result = result
    }
}

/// `POST /dsh-link/mobile/sessions/:id/cancel`。
public struct CancelResponse: Codable, Equatable, Sendable {
    public var ok: Bool?
    public var sessionId: String?

    public init(ok: Bool? = nil, sessionId: String? = nil) {
        self.ok = ok
        self.sessionId = sessionId
    }
}

/// `POST /dsh-link/mobile/sessions/:id/queue/:itemId`（action = edit / remove / steer）。
public struct QueueActionResponse: Codable, Equatable, Sendable {
    public var ok: Bool?
    public var sessionId: String?
    public var itemId: String?
    public var action: String?

    public init(ok: Bool? = nil, sessionId: String? = nil, itemId: String? = nil, action: String? = nil) {
        self.ok = ok
        self.sessionId = sessionId
        self.itemId = itemId
        self.action = action
    }
}
