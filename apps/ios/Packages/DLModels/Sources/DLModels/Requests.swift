import Foundation

/// `GET /dsh-link/mobile/sessions/:id/requests`。实时流、重连快照、本响应、提交响应
/// 走同一个归并：终态不得被 pending 回滚（I3.6）。空白 id 由 App 忽略。
public struct RequestsSnapshotResponse: Codable, Equatable, Sendable {
    public var version: Int?
    public var approvals: [PendingApproval]?
    public var questions: [PendingQuestion]?
    /// SSE 断开后的重连宽限是否生效。
    public var graceActive: Bool?

    public init(
        version: Int? = nil,
        approvals: [PendingApproval]? = nil,
        questions: [PendingQuestion]? = nil,
        graceActive: Bool? = nil
    ) {
        self.version = version
        self.approvals = approvals
        self.questions = questions
        self.graceActive = graceActive
    }
}

public struct PendingApproval: Codable, Equatable, Sendable {
    public var approvalId: String?
    public var status: RequestStatus?
    public var outcome: String?
    public var sessionId: String?
    public var createdAt: Int?
    public var deadlineAt: Int?
    public var callId: String?
    public var toolName: String?

    public init(
        approvalId: String? = nil,
        status: RequestStatus? = nil,
        outcome: String? = nil,
        sessionId: String? = nil,
        createdAt: Int? = nil,
        deadlineAt: Int? = nil,
        callId: String? = nil,
        toolName: String? = nil
    ) {
        self.approvalId = approvalId
        self.status = status
        self.outcome = outcome
        self.sessionId = sessionId
        self.createdAt = createdAt
        self.deadlineAt = deadlineAt
        self.callId = callId
        self.toolName = toolName
    }
}

public struct PendingQuestion: Codable, Equatable, Sendable {
    public var rpcId: String?
    public var status: RequestStatus?
    public var sessionId: String?
    public var createdAt: Int?
    public var deadlineAt: Int?
    /// pending 澄清带原始 questions 数组（DSH 形状）；已终态的记录不带。
    public var questions: [ClarifyingQuestion]?
    /// 解码时截下的 questions 数组原文。回填答案时原样使用，不经模型重编码。
    public var questionsJSON: String?

    public init(
        rpcId: String? = nil,
        status: RequestStatus? = nil,
        sessionId: String? = nil,
        createdAt: Int? = nil,
        deadlineAt: Int? = nil,
        questions: [ClarifyingQuestion]? = nil,
        questionsJSON: String? = nil
    ) {
        self.rpcId = rpcId
        self.status = status
        self.sessionId = sessionId
        self.createdAt = createdAt
        self.deadlineAt = deadlineAt
        self.questions = questions
        self.questionsJSON = questionsJSON
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(rpcId, forKey: .rpcId)
        try container.encodeIfPresent(status, forKey: .status)
        try container.encodeIfPresent(sessionId, forKey: .sessionId)
        try container.encodeIfPresent(createdAt, forKey: .createdAt)
        try container.encodeIfPresent(deadlineAt, forKey: .deadlineAt)
        try container.encodeIfPresent(questions, forKey: .questions)
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        rpcId = try container.decodeIfPresent(String.self, forKey: .rpcId)
        status = try container.decodeIfPresent(RequestStatus.self, forKey: .status)
        sessionId = try container.decodeIfPresent(String.self, forKey: .sessionId)
        createdAt = try container.decodeIfPresent(Int.self, forKey: .createdAt)
        deadlineAt = try container.decodeIfPresent(Int.self, forKey: .deadlineAt)
        questions = try container.decodeIfPresent([ClarifyingQuestion].self, forKey: .questions)
        questionsJSON = try? Self.rawJSON(in: container, forKey: .questions)
    }

    private enum CodingKeys: String, CodingKey {
        case rpcId
        case status
        case sessionId
        case createdAt
        case deadlineAt
        case questions
    }

    private static func rawJSON(
        in container: KeyedDecodingContainer<CodingKeys>, forKey key: CodingKeys
    ) throws -> String? {
        guard container.contains(key), try !container.decodeNil(forKey: key) else { return nil }
        let raw = try container.decode(RawJSON.self, forKey: key)
        return raw.text
    }
}

/// 澄清题（DSH 原始形状，见 `src/question-answers.js` 的 normalize 输入）。
/// 提交答案时原样回传 `answers`，App 不得构造未展示题目的答案。
public struct ClarifyingQuestion: Codable, Equatable, Sendable {
    public var id: String?
    public var header: String?
    public var question: String?
    /// 选项可以是裸字符串或对象；两种都落这里。
    public var options: [QuestionOption]?
    public var kind: String?
    public var multiple: Bool?
    public var optional: Bool?

    public init(
        id: String? = nil,
        header: String? = nil,
        question: String? = nil,
        options: [QuestionOption]? = nil,
        kind: String? = nil,
        multiple: Bool? = nil,
        optional: Bool? = nil
    ) {
        self.id = id
        self.header = header
        self.question = question
        self.options = options
        self.kind = kind
        self.multiple = multiple
        self.optional = optional
    }
}

/// 澄清选项：线上是裸字符串或 `{ id?, label?, value? }` 对象。
public struct QuestionOption: Codable, Equatable, Sendable {
    public var id: String?
    public var label: String?
    public var value: String?

    public init(id: String? = nil, label: String? = nil, value: String? = nil) {
        self.id = id
        self.label = label
        self.value = value
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let text = try? container.decode(String.self) {
            label = text
            return
        }
        let object = try decoder.container(keyedBy: CodingKeys.self)
        id = try object.decodeIfPresent(String.self, forKey: .id)
        label = try object.decodeIfPresent(String.self, forKey: .label)
        value = try object.decodeIfPresent(String.self, forKey: .value)
    }

    public func encode(to encoder: any Encoder) throws {
        guard id != nil || label != nil || value != nil else {
            var container = encoder.singleValueContainer()
            try container.encode("")
            return
        }
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(id, forKey: .id)
        try container.encodeIfPresent(label, forKey: .label)
        try container.encodeIfPresent(value, forKey: .value)
    }

    private enum CodingKeys: String, CodingKey {
        case id
        case label
        case value
    }
}

/// 请求状态。终态不得被 pending 回滚；`unknown` 是插件对无法识别 outcome 的正式取值。
public enum RequestStatus: DLStringEnum {
    case pending
    case resolved
    case cancelled
    case expired
    case unknown(String)

    public static func decoding(_ rawValue: String) -> Self {
        switch rawValue {
        case "pending": .pending
        case "resolved": .resolved
        case "cancelled": .cancelled
        case "expired": .expired
        default: .unknown(rawValue)
        }
    }

    public var encodedValue: String {
        switch self {
        case .pending: "pending"
        case .resolved: "resolved"
        case .cancelled: "cancelled"
        case .expired: "expired"
        case .unknown(let raw): raw
        }
    }
}

/// 审批 / 澄清提交响应。重复提交已有终态时返回 `alreadySettled` 且不再次 settle。
public struct RequestSubmitResponse: Codable, Equatable, Sendable {
    public var ok: Bool?
    public var accepted: Bool?
    public var alreadySettled: Bool?
    public var outcome: String?
    public var status: RequestStatus?
    public var handledBy: String?

    public init(
        ok: Bool? = nil,
        accepted: Bool? = nil,
        alreadySettled: Bool? = nil,
        outcome: String? = nil,
        status: RequestStatus? = nil,
        handledBy: String? = nil
    ) {
        self.ok = ok
        self.accepted = accepted
        self.alreadySettled = alreadySettled
        self.outcome = outcome
        self.status = status
        self.handledBy = handledBy
    }
}

/// 会话 SSE `question` 帧：澄清请求进手机（`src/question-bridge.js`）。
public struct QuestionRequestEvent: Codable, Equatable, Sendable {
    public var rpcId: String?
    public var sessionId: String?
    public var questions: [ClarifyingQuestion]?
    /// 解码时截下的 questions 数组原文。回填答案时原样使用。
    public var questionsJSON: String?

    public init(
        rpcId: String? = nil, sessionId: String? = nil, questions: [ClarifyingQuestion]? = nil,
        questionsJSON: String? = nil
    ) {
        self.rpcId = rpcId
        self.sessionId = sessionId
        self.questions = questions
        self.questionsJSON = questionsJSON
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(rpcId, forKey: .rpcId)
        try container.encodeIfPresent(sessionId, forKey: .sessionId)
        try container.encodeIfPresent(questions, forKey: .questions)
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        rpcId = try container.decodeIfPresent(String.self, forKey: .rpcId)
        sessionId = try container.decodeIfPresent(String.self, forKey: .sessionId)
        questions = try container.decodeIfPresent([ClarifyingQuestion].self, forKey: .questions)
        if container.contains(.questions), (try? container.decodeNil(forKey: .questions)) == false {
            questionsJSON = try? container.decode(RawJSON.self, forKey: .questions).text
        }
    }

    private enum CodingKeys: String, CodingKey {
        case rpcId
        case sessionId
        case questions
    }
}

/// 会话 SSE `question-resolved` 帧：澄清在别处（电脑网页）已被回答或取消。
public struct QuestionResolvedEvent: Codable, Equatable, Sendable {
    public var rpcId: String?
    public var sessionId: String?
    public var outcome: String?

    public init(rpcId: String? = nil, sessionId: String? = nil, outcome: String? = nil) {
        self.rpcId = rpcId
        self.sessionId = sessionId
        self.outcome = outcome
    }
}
