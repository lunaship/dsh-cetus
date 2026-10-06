import DLModels
import Foundation

/// 4.3 / 4.4：只挑手机能处理的最新一条。审批必须已被手机接管且仍是 pending；
/// 提问要有 rpc id、还没终态，并且之后用户没有再发过消息。
public enum PhoneDecision: Equatable, Sendable {
    case approval(RequestMessage)
    case question(RequestMessage)
}

public func actionablePhoneApproval(_ message: RequestMessage) -> Bool {
    message.role == "approval"
        && !blank(message.approvalId)
        && message.requestStatus == .pending
        && message.takenOverByPhone
}

public func actionablePhoneQuestion(_ message: RequestMessage) -> Bool {
    message.role == "question"
        && !blank(message.questionRpcId)
        && !isTerminalRequestStatus(message.requestStatus)
}

public func pendingPhoneDecision(
    _ messages: [RequestMessage],
    userFollowedQuestion: (RequestMessage) -> Bool = { _ in false }
) -> PhoneDecision? {
    for message in messages.reversed() {
        if actionablePhoneApproval(message) { return .approval(message) }
        if actionablePhoneQuestion(message), !userFollowedQuestion(message) {
            return .question(message)
        }
    }
    return nil
}

/// 历史里这条提问之后出现过用户消息时，不再继续问。对齐 Android 的 later user message 规则。
public func pendingPhoneDecision(
    _ history: [HistoryMessage],
    requests: [RequestMessage]
) -> PhoneDecision? {
    pendingPhoneDecision(requests) { question in
        guard let id = question.id.isEmpty ? question.questionRpcId : question.id else { return false }
        let needles = [id, "question-\(question.questionRpcId ?? "")"]
        guard
            let index = history.lastIndex(where: { message in
                message.role == "question" && needles.contains { $0 == message.id }
            })
        else { return false }
        return history[history.index(after: index)...].contains { $0.role == "user" }
    }
}

/// 审批参数里的命令。拿不到时返回 nil，决策栏不画空命令块。
public func approvalCommand(from args: String?) -> String? {
    guard let args, let data = args.data(using: .utf8),
        let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    else { return nil }
    for key in ["command", "cmd", "script"] {
        guard let value = object[key] as? String else { continue }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { return trimmed }
    }
    return nil
}

private func blank(_ value: String?) -> Bool {
    (value ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
}
