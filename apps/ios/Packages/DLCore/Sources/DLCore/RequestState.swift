import DLModels
import Foundation

/// 请求状态归并（I3.6，PLAN 阶段 3）。与 Android `RequestState.kt` / `QuestionAnswers.kt`
/// 一一对应：同 `approvalId` 的审批、同 `rpcId` 的提问各归并为一条；终态（resolved /
/// cancelled / expired）不得被 pending 回滚。实时流、重连快照、`GET .../requests`、
/// 提交响应四种输入都经 `RequestStateReducer` 走同一组合并函数。
///
/// 状态用 DLModels 的 `RequestStatus`（unknown 是插件对无法识别取值的正式状态）。
/// 本文件不改动 DLModels 现有类型；消息载体是 DLCore 自己的 `RequestMessage`。

private func isBlank(_ value: String?) -> Bool {
    (value ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
}

private func nonBlank(_ value: String?) -> String? {
    isBlank(value) ? nil : value
}

// MARK: - RequestMessage

/// 请求状态归并所需的消息字段子集，字段名与 Android `MobileMessage` 属性一一对应
/// （DLModels 尚无统一消息类型，这里自带一份纯值类型）。
public struct RequestMessage: Equatable, Sendable {
    public var id: String
    public var role: String
    public var text: String
    public var type: String
    public var toolName: String?
    /// 工具参数 JSON。只用来抽出命令块，没有就不画。
    public var toolArgs: String?
    public var approvalId: String?
    public var callId: String?
    /// 这条审批 / 提问来自「手机接管」的请求快照；只有它才能显示批准按钮。
    public var takenOverByPhone: Bool
    public var questionRpcId: String?
    public var questionOptions: [String]
    public var questionHeader: String?
    /// 完整 questions 数组 JSON，提交答案时回传。
    public var questionPayloadJson: String?
    public var requestStatus: RequestStatus?
    public var outcome: String?

    public init(
        id: String,
        role: String,
        text: String = "",
        type: String = "text",
        toolName: String? = nil,
        toolArgs: String? = nil,
        approvalId: String? = nil,
        callId: String? = nil,
        takenOverByPhone: Bool = false,
        questionRpcId: String? = nil,
        questionOptions: [String] = [],
        questionHeader: String? = nil,
        questionPayloadJson: String? = nil,
        requestStatus: RequestStatus? = nil,
        outcome: String? = nil
    ) {
        self.id = id
        self.role = role
        self.text = text
        self.type = type
        self.toolName = toolName
        self.toolArgs = toolArgs
        self.approvalId = approvalId
        self.callId = callId
        self.takenOverByPhone = takenOverByPhone
        self.questionRpcId = questionRpcId
        self.questionOptions = questionOptions
        self.questionHeader = questionHeader
        self.questionPayloadJson = questionPayloadJson
        self.requestStatus = requestStatus
        self.outcome = outcome
    }
}

// MARK: - 状态归并原语

/// DSH outcome → 请求状态（Android `approvalUiStatus`）：allowed-once / rejected → resolved、
/// cancelled → cancelled、unavailable → expired、其余 → unknown，缺 outcome 视为 pending。
public func approvalUiStatus(outcome: String?) -> RequestStatus {
    switch outcome {
    case nil, "": return .pending
    case "allowed-once", "rejected": return .resolved
    case "cancelled": return .cancelled
    case "unavailable": return .expired
    default: return .unknown("unknown")
    }
}

func isTerminalRequestStatus(_ status: RequestStatus?) -> Bool {
    status == .resolved || status == .cancelled || status == .expired
}

/// 终态 4、unknown 2、pending 1、无状态 0（Android `requestStatusRank`；
/// `.unknown` 载荷非 "unknown" 时按无状态计，对齐 Android 对任意串的 else 分支）。
private func requestStatusRank(_ status: RequestStatus?) -> Int {
    guard let status else { return 0 }
    switch status {
    case .resolved, .cancelled, .expired: return 4
    case .unknown(let raw): return raw == "unknown" ? 2 : 0
    case .pending: return 1
    }
}

/// 状态归并（Android `mergeRequestStatus`）：incoming 缺失保留 current，current 缺失采用
/// incoming，current 已终态时 pending 不回滚，其余按 rank 高者（平级取 incoming）。
public func mergeStatus(_ current: RequestStatus?, _ incoming: RequestStatus?) -> RequestStatus? {
    guard let incoming else { return current }
    guard let current else { return incoming }
    if isTerminalRequestStatus(current), incoming == .pending { return current }
    return requestStatusRank(incoming) >= requestStatusRank(current) ? incoming : current
}

/// outcome 归并（Android `mergeRequestOutcome`）：status 已终态时优先取非空的 incoming /
/// current；非终态时取非空的 incoming，否则保留 current。
public func mergeOutcome(_ current: String?, _ incoming: String?, status: RequestStatus?) -> String? {
    if isTerminalRequestStatus(status) {
        if !isBlank(incoming) { return incoming }
        if !isBlank(current) { return current }
    }
    return !isBlank(incoming) ? incoming : current
}

/// 单条卡片的 status / outcome 更新（Android `applyRequestState`）。
public func applyRequestState(
    _ message: RequestMessage,
    status: RequestStatus?,
    outcome: String?
) -> RequestMessage {
    let mergedStatus = mergeStatus(message.requestStatus, status)
    var updated = message
    updated.requestStatus = mergedStatus
    updated.outcome = mergeOutcome(message.outcome, outcome, status: mergedStatus)
    return updated
}

// MARK: - 消息列表归并

extension RequestMessage {
    /// Android `coalesceKeyedRequestMessages` 的合并语义：首现卡片胜出（保 id / 位置），
    /// 其余字段按「已有非空优先」补齐。
    func merged(with incoming: RequestMessage, status: RequestStatus?) -> RequestMessage {
        var merged = self
        merged.requestStatus = status
        merged.outcome = mergeOutcome(outcome, incoming.outcome, status: status)
        merged.text = isBlank(text) ? incoming.text : text
        merged.toolName = toolName ?? incoming.toolName
        merged.toolArgs = toolArgs ?? incoming.toolArgs
        merged.callId = callId ?? incoming.callId
        merged.approvalId = approvalId ?? incoming.approvalId
        merged.takenOverByPhone = takenOverByPhone || incoming.takenOverByPhone
        merged.questionRpcId = questionRpcId ?? incoming.questionRpcId
        merged.questionPayloadJson = questionPayloadJson ?? incoming.questionPayloadJson
        merged.questionOptions = questionOptions.isEmpty ? incoming.questionOptions : questionOptions
        merged.questionHeader = questionHeader ?? incoming.questionHeader
        return merged
    }
}

/// 同 approvalId 的审批卡、同 rpcId 的提问卡各归并为一条（Android `coalesceRequestMessages`）：
/// 不带 key 的消息原位保留，归并卡保持首次出现的位置。
public func coalesce(_ messages: [RequestMessage]) -> [RequestMessage] {
    let afterApprovals = coalesceKeyed(messages, role: "approval") { $0.approvalId }
    return coalesceKeyed(afterApprovals, role: "question") { $0.questionRpcId }
}

private func coalesceKeyed(
    _ messages: [RequestMessage],
    role: String,
    keyOf: (RequestMessage) -> String?
) -> [RequestMessage] {
    guard messages.contains(where: { $0.role == role && !isBlank(keyOf($0)) }) else { return messages }
    var winners: [String: (index: Int, message: RequestMessage)] = [:]
    for (index, message) in messages.enumerated() {
        guard message.role == role, let key = nonBlank(keyOf(message)) else { continue }
        guard let existing = winners[key] else {
            winners[key] = (index, message)
            continue
        }
        let mergedStatus = mergeStatus(existing.message.requestStatus, message.requestStatus)
        winners[key] = (existing.index, existing.message.merged(with: message, status: mergedStatus))
    }
    if winners.isEmpty { return messages }
    var usedKeys: Set<String> = []
    var result: [RequestMessage] = []
    result.reserveCapacity(messages.count)
    for (index, message) in messages.enumerated() {
        guard message.role == role, let key = nonBlank(keyOf(message)), let winner = winners[key] else {
            result.append(message)
            continue
        }
        if winner.index != index { continue }
        if !usedKeys.insert(key).inserted { continue }
        result.append(winner.message)
    }
    return result
}

// MARK: - 请求快照

/// `GET .../requests` 快照里的一条请求记录（对应 Android `SessionRequestState`）。
/// 线上 question 的 `questions` 是原始 DSH 数组；iOS 侧保留 DLModels 解码后的形状
/// （Android 存序列化字符串 `questionsJson`，内容等价）。
public struct SessionRequestState: Equatable, Sendable {
    public var id: String
    public var kind: String
    public var status: RequestStatus
    public var outcome: String?
    public var toolName: String?
    public var callId: String?
    public var questions: [ClarifyingQuestion]?
    /// 快照里 questions 数组的原文。提交时优先用它，避免重编码丢掉未知字段。
    public var questionsJSON: String?

    public init(
        id: String,
        kind: String,
        status: RequestStatus,
        outcome: String? = nil,
        toolName: String? = nil,
        callId: String? = nil,
        questions: [ClarifyingQuestion]? = nil,
        questionsJSON: String? = nil
    ) {
        self.id = id
        self.kind = kind
        self.status = status
        self.outcome = outcome
        self.toolName = toolName
        self.callId = callId
        self.questions = questions
        self.questionsJSON = questionsJSON
    }
}

/// 对应 Android `SessionRequestSnapshot`。
public struct SessionRequestSnapshot: Equatable, Sendable {
    public var approvals: [SessionRequestState]
    public var questions: [SessionRequestState]

    public init(approvals: [SessionRequestState] = [], questions: [SessionRequestState] = []) {
        self.approvals = approvals
        self.questions = questions
    }
}

/// 把线上快照响应归一成记录列表（Android `parseSessionRequestSnapshot`）：审批取
/// `approvalId`、提问取 `rpcId`，空白 id 丢弃，status 缺省按 pending。
public func parseSessionRequestSnapshot(_ response: RequestsSnapshotResponse) -> SessionRequestSnapshot {
    let approvals = (response.approvals ?? []).compactMap { approval -> SessionRequestState? in
        guard let id = nonBlank(approval.approvalId) else { return nil }
        return SessionRequestState(
            id: id,
            kind: "approval",
            status: approval.status ?? .pending,
            outcome: approval.outcome,
            toolName: approval.toolName,
            callId: approval.callId
        )
    }
    let questions = (response.questions ?? []).compactMap { question -> SessionRequestState? in
        guard let id = nonBlank(question.rpcId) else { return nil }
        return SessionRequestState(
            id: id,
            kind: "question",
            status: question.status ?? .pending,
            outcome: nil,
            questions: question.questions,
            questionsJSON: question.questionsJSON
        )
    }
    return SessionRequestSnapshot(approvals: approvals, questions: questions)
}

// MARK: - 题目内容回填

/// 澄清题摘要所需的最小投影（Android `parseClarifyingQuestions` 的等价结果；
/// 题型 / 多选标记属于答案构造，不在 I3.6 范围）。
private struct ParsedQuestion: Equatable, Sendable {
    var id: String
    var header: String
    var prompt: String
    var optionLabels: [String]
}

/// DLModels 已解码的澄清题 → 摘要投影。Android 直接解析原始 JSON（prompt 还接受
/// `prompt` / `text` 键）；DLModels 的 `ClarifyingQuestion` 只保留 `question`，见执行记录偏差。
private func parseClarifyingQuestions(_ questions: [ClarifyingQuestion]?) -> [ParsedQuestion] {
    guard let questions else { return [] }
    return questions.enumerated().map { index, question in
        ParsedQuestion(
            id: nonBlank(question.id) ?? "q\(index)",
            header: question.header ?? "",
            prompt: nonBlank(question.question) ?? "",
            optionLabels: (question.options ?? []).enumerated().map { optionIndex, option in
                nonBlank(option.label) ?? nonBlank(option.id) ?? nonBlank(option.value) ?? "opt\(optionIndex)"
            }
        )
    }
}

/// 题目摘要文字（Android `questionSummaryText`）：单题取题面，多题逐行拼接。
private func questionSummaryText(_ questions: [ParsedQuestion], _ fallback: String) -> String {
    if questions.isEmpty { return fallback }
    if questions.count == 1 { return questions[0].prompt.isEmpty ? fallback : questions[0].prompt }
    return questions.map { question -> String in
        if !question.prompt.isEmpty { return question.prompt }
        if !question.header.isEmpty { return question.header }
        return question.id
    }
    .joined(separator: "\n")
}

/// 回传用的 questions JSON。有原文就原样保留；只有模型构造的题目时才编码，未知字段不会因此出现。
private func questionPayloadJSON(_ questions: [ClarifyingQuestion]?, raw: String? = nil) -> String? {
    if let raw, !isBlank(raw) { return raw }
    guard let questions, let data = try? JSONEncoder().encode(questions) else { return nil }
    return String(data: data, encoding: .utf8)
}

// MARK: - 快照合并

/// 用快照刷新已有卡片的状态 / 内容 / 接管标记（Android `applyRequestSnapshotToMessages`）。
/// 空快照原样返回；不在快照里的卡片不动、不打接管标记。
public func applyRequestSnapshotToMessages(
    _ messages: [RequestMessage],
    snapshot: SessionRequestSnapshot
) -> [RequestMessage] {
    if snapshot.approvals.isEmpty && snapshot.questions.isEmpty { return messages }
    return messages.map { message -> RequestMessage in
        if message.role == "approval", let approvalId = nonBlank(message.approvalId) {
            guard let record = snapshot.approvals.first(where: { $0.id == approvalId }) else { return message }
            var updated = applyRequestState(message, status: record.status, outcome: record.outcome)
            updated.toolName = updated.toolName ?? record.toolName
            updated.callId = updated.callId ?? record.callId
            updated.text = isBlank(updated.text) ? (record.toolName ?? "") : updated.text
            updated.takenOverByPhone = true
            return updated
        }
        if message.role == "question", let rpcId = nonBlank(message.questionRpcId) {
            guard let record = snapshot.questions.first(where: { $0.id == rpcId }) else { return message }
            var updated = applyRequestState(message, status: record.status, outcome: record.outcome)
            // 已有题目内容（或快照没带 questions 数组）时只补接管标记，不覆盖。
            if !isBlank(updated.questionPayloadJson) || record.questions == nil {
                updated.takenOverByPhone = true
                return updated
            }
            let parsed = parseClarifyingQuestions(record.questions)
            updated.takenOverByPhone = true
            updated.questionPayloadJson = questionPayloadJSON(record.questions, raw: record.questionsJSON)
            updated.questionOptions = parsed.first?.optionLabels ?? []
            updated.questionHeader = parsed.first.flatMap { nonBlank($0.header) }
            updated.text = questionSummaryText(parsed, isBlank(updated.text) ? record.id : updated.text)
            return updated
        }
        return message
    }
}

/// 快照合并：刷新已有卡片之外，把 history 没有的 pending 审批 / 提问卡补回尾页
/// （Android `mergeMessagesWithRequestSnapshot`）。空白 id 与非 pending 记录不补。
public func mergeMessagesWithRequestSnapshot(
    _ messages: [RequestMessage],
    snapshot: SessionRequestSnapshot
) -> [RequestMessage] {
    let updated = applyRequestSnapshotToMessages(messages, snapshot: snapshot)
    let approvalIds = Set(updated.compactMap(\.approvalId))
    let questionIds = Set(updated.compactMap(\.questionRpcId))
    var extras: [RequestMessage] = []
    for record in snapshot.approvals {
        if isBlank(record.id) || record.status != .pending || approvalIds.contains(record.id) { continue }
        var extra = RequestMessage(
            id: "approval-\(record.id)",
            role: "approval",
            text: record.toolName ?? "",
            type: "approval",
            toolName: record.toolName,
            approvalId: record.id,
            callId: record.callId,
            takenOverByPhone: true
        )
        extra.requestStatus = record.status
        extra.outcome = record.outcome
        extras.append(extra)
    }
    for record in snapshot.questions {
        if isBlank(record.id) || record.status != .pending || questionIds.contains(record.id) { continue }
        let parsed = parseClarifyingQuestions(record.questions)
        var extra = RequestMessage(
            id: "question-\(record.id)",
            role: "question",
            text: questionSummaryText(parsed, record.id),
            type: "question",
            questionRpcId: record.id,
            questionOptions: parsed.first?.optionLabels ?? [],
            questionHeader: parsed.first.flatMap { nonBlank($0.header) },
            questionPayloadJson: questionPayloadJSON(record.questions, raw: record.questionsJSON)
        )
        extra.requestStatus = record.status
        extras.append(extra)
    }
    return extras.isEmpty ? updated : coalesce(updated + extras)
}

// MARK: - 统一入口

extension RequestMessage {
    /// Android 各调用点的并集：按 approvalId / questionRpcId / 合成 id（approval-<id> 等）匹配。
    fileprivate func matches(requestId: String) -> Bool {
        approvalId == requestId
            || questionRpcId == requestId
            || id == requestId
            || id == "approval-\(requestId)"
            || id == "question-\(requestId)"
    }
}

/// I3.6 统一入口：实时流事件、重连快照、`GET .../requests` 快照、提交响应四种输入
/// 归约到同一份消息状态。所有输入最终都调用同一组合并函数
/// （mergeStatus / mergeOutcome / applyRequestState / coalesce），不各写一套。
public struct RequestStateReducer: Equatable, Sendable {
    public private(set) var messages: [RequestMessage]

    public init(messages: [RequestMessage] = []) {
        self.messages = coalesce(messages)
    }

    /// ① 实时流：新到的审批 / 提问卡（approval/asked、question 帧的投影）进列表后统一归并；
    /// 迟到的重复 / 终态卡按同 key 合并，终态不被 pending 回滚。
    public mutating func absorb(_ incoming: [RequestMessage]) {
        guard !incoming.isEmpty else { return }
        messages = coalesce(messages + incoming)
    }

    /// ①/④ 实时流 approval/decided、question-resolved 与提交成功后的本地更新共用：
    /// 按 approvalId / questionRpcId / 合成 id 找到卡片，套 applyRequestState。
    public mutating func update(requestId: String, status: RequestStatus?, outcome: String?) {
        guard !isBlank(requestId) else { return }
        messages = messages.map { message in
            message.matches(requestId: requestId)
                ? applyRequestState(message, status: status, outcome: outcome)
                : message
        }
    }

    /// 实时流 `approval/decided`：outcome 经 approvalUiStatus 映射（Android applyApprovalDecision）。
    public mutating func resolveApproval(approvalId: String, outcome: String?) {
        update(requestId: approvalId, status: approvalUiStatus(outcome: outcome), outcome: outcome)
    }

    /// ②/③ 重连快照与 `GET .../requests` 快照：只刷新已有卡片的状态 / 内容 / 接管标记。
    public mutating func apply(snapshot response: RequestsSnapshotResponse) {
        apply(snapshot: parseSessionRequestSnapshot(response))
    }

    public mutating func apply(snapshot: SessionRequestSnapshot) {
        messages = applyRequestSnapshotToMessages(messages, snapshot: snapshot)
    }

    /// ②/③ 快照合并：刷新之外再补回 history 没有的 pending 卡（resync / 进程重启后的兜底）。
    public mutating func merge(snapshot response: RequestsSnapshotResponse) {
        merge(snapshot: parseSessionRequestSnapshot(response))
    }

    public mutating func merge(snapshot: SessionRequestSnapshot) {
        messages = mergeMessagesWithRequestSnapshot(messages, snapshot: snapshot)
    }

    /// ④ 提交响应（`approval-submit.json` / `question-submit.json` → `RequestSubmitResponse`）。
    /// 提问响应不带 status / outcome，与 Android answerQuestion 一致按 resolved 处理；
    /// 响应明确失败（ok / accepted 为 false）时不改本地状态，等快照或 SSE 纠偏。
    public mutating func apply(submit: RequestSubmitResponse, requestId: String) {
        if submit.ok == false || submit.accepted == false { return }
        let status: RequestStatus
        if let explicit = submit.status {
            status = explicit
        } else if let outcome = submit.outcome {
            status = approvalUiStatus(outcome: outcome)
        } else {
            status = .resolved
        }
        update(requestId: requestId, status: status, outcome: submit.outcome)
    }
}
