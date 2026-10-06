import DLModels
import Foundation

/// 历史分页（A3.8）。更旧的一页插到前面；重叠 id 保留先出现的旧副本。
/// 没有 id 的消息按页内位置保留，避免两条空白消息互相吃掉。
public func prependHistoryPage(_ older: [HistoryMessage], before current: [HistoryMessage]) -> [HistoryMessage] {
    var seen: Set<String> = []
    var merged: [HistoryMessage] = []
    merged.reserveCapacity(older.count + current.count)
    for (page, messages) in [older, current].enumerated() {
        for (offset, message) in messages.enumerated() {
            let key = historyIdentity(message) ?? "page\(page)-\(offset)"
            if seen.insert(key).inserted {
                merged.append(message)
            }
        }
    }
    return merged
}

/// 提问之后出现用户消息就不再问（A3.6，对齐 Android pendingDecision）。
/// 请求归并列表没有时间顺序，所以用历史流里的位置判断。
public func userFollowedQuestion(_ question: RequestMessage, in messages: [HistoryMessage]) -> Bool {
    let rpcID = question.questionRpcId?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    let id = question.id.trimmingCharacters(in: .whitespacesAndNewlines)
    let needles = [id, rpcID, rpcID.isEmpty ? "" : "question-\(rpcID)"].filter { !$0.isEmpty }
    guard !needles.isEmpty else { return false }
    guard let index = messages.lastIndex(where: { message in
        message.role == "question" && needles.contains { $0 == message.id }
    }) else {
        return false
    }
    return messages[(index + 1)...].contains { $0.role == "user" }
}

private func historyIdentity(_ message: HistoryMessage) -> String? {
    let id = message.id?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    return id.isEmpty ? nil : id
}
