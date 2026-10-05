import DLModels
import Foundation

public enum TrajectoryFilter: String, CaseIterable, Sendable {
    case all
    case user
    case assistant
    case tool
}

public struct TrajectoryLine: Equatable, Identifiable, Sendable {
    public var id: String
    public var title: String
    public var detail: String

    public init(id: String, title: String, detail: String) {
        self.id = id
        self.title = title
        self.detail = detail
    }
}

public struct TrajectoryTurn: Equatable, Identifiable, Sendable {
    public var id: Int
    public var time: Int?
    public var lines: [TrajectoryLine]

    public init(id: Int, time: Int?, lines: [TrajectoryLine]) {
        self.id = id
        self.time = time
        self.lines = lines
    }
}

public func trajectoryTurns(
    _ messages: [HistoryMessage],
    filter: TrajectoryFilter = .all,
    query: String = ""
) -> [TrajectoryTurn] {
    let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    var groups: [Int: [TrajectoryLine]] = [:]
    var times: [Int: Int] = [:]
    var order: [Int] = []
    for (index, message) in messages.enumerated() {
        guard let line = trajectoryLine(message, index: index, filter: filter) else { continue }
        if !needle.isEmpty, !line.title.lowercased().contains(needle), !line.detail.lowercased().contains(needle) {
            continue
        }
        let turn = message.turn ?? 0
        if groups[turn] == nil { order.append(turn) }
        groups[turn, default: []].append(line)
        if let time = message.time {
            times[turn] = max(times[turn] ?? time, time)
        }
    }
    return order.compactMap { turn in
        guard let lines = groups[turn], !lines.isEmpty else { return nil }
        return TrajectoryTurn(id: turn, time: times[turn], lines: lines)
    }
}

private func trajectoryLine(_ message: HistoryMessage, index: Int, filter: TrajectoryFilter) -> TrajectoryLine? {
    let role = message.role ?? ""
    let kind = trajectoryKind(role)
    switch filter {
    case .all: break
    case .user where kind != .user: return nil
    case .assistant where kind != .assistant: return nil
    case .tool where kind != .tool: return nil
    default: break
    }
    let title = trajectoryTitle(message, kind: kind)
    let detail = (message.text ?? message.args ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    if title.isEmpty, detail.isEmpty { return nil }
    let id = message.id ?? "line-\(index)"
    return TrajectoryLine(id: id, title: title, detail: detail)
}

private enum TrajectoryKind {
    case user
    case assistant
    case tool
    case other
}

private func trajectoryKind(_ role: String) -> TrajectoryKind {
    switch role {
    case "user": .user
    case "assistant": .assistant
    case "tool_call", "tool_result", "tool": .tool
    default: .other
    }
}

private func trajectoryTitle(_ message: HistoryMessage, kind: TrajectoryKind) -> String {
    switch kind {
    case .user: return "user"
    case .assistant: return "assistant"
    case .tool: return message.name ?? message.toolName ?? "tool"
    case .other: return message.role ?? ""
    }
}
