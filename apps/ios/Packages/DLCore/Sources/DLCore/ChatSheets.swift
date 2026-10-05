import DLModels
import Foundation

public let modelSearchThreshold = 8

public struct ModelRow: Equatable, Sendable, Identifiable {
    public var provider: String
    public var providerName: String
    public var id: String
    public var name: String
    public var contextWindow: Int?
    public var efforts: [String]
    public var defaultEffort: String?
    public var key: String { "\(provider)/\(id)" }

    public init(
        provider: String, providerName: String, id: String, name: String, contextWindow: Int? = nil,
        efforts: [String] = [], defaultEffort: String? = nil
    ) {
        self.provider = provider
        self.providerName = providerName
        self.id = id
        self.name = name
        self.contextWindow = contextWindow
        self.efforts = efforts
        self.defaultEffort = defaultEffort
    }
}

public func modelRows(_ response: SessionModelsResponse) -> [ModelRow] {
    (response.groups ?? []).flatMap { group in
        let provider = group.provider ?? ""
        let providerName = group.providerName ?? provider
        return (group.models ?? []).map { model in
            ModelRow(
                provider: provider,
                providerName: providerName,
                id: model.id,
                name: (model.name?.isEmpty == false ? model.name! : model.id),
                contextWindow: model.contextWindow,
                efforts: model.reasoningEfforts ?? [],
                defaultEffort: model.defaultEffort)
        }
    }
}

public func filterModelRows(_ rows: [ModelRow], query: String) -> [ModelRow] {
    let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !needle.isEmpty else { return rows }
    return rows.filter { row in
        row.id.range(of: needle, options: .caseInsensitive) != nil
            || row.name.range(of: needle, options: .caseInsensitive) != nil
            || row.providerName.range(of: needle, options: .caseInsensitive) != nil
    }
}

public func showsModelSearch(_ count: Int) -> Bool { count > modelSearchThreshold }

public enum PermissionPreset: String, CaseIterable, Sendable, Equatable {
    case readOnly = "read-only"
    case workspaceWrite = "workspace-write"
    case fullAccess = "danger-full-access"

    public var needsConfirmation: Bool { self == .fullAccess }
}

public struct UsageSlice: Equatable, Sendable {
    public var key: String
    public var tokens: Int

    public init(key: String, tokens: Int) {
        self.key = key
        self.tokens = tokens
    }
}

public struct UsageFigures: Equatable, Sendable {
    public var uncachedInputTokens: Int
    public var cacheReadTokens: Int
    public var outputTokens: Int
    public var totalTokens: Int
    public var cacheHitRate: Double?
    public var turns: Int
    public var steps: Int
    public var llmMs: Int
    public var toolMs: Int
    public var avgTtftMs: Double?
    public var outputTokensPerSec: Double?
    public var contextUsedTokens: Int
    public var contextWindowTokens: Int
    public var breakdown: [UsageSlice]

    public init(
        uncachedInputTokens: Int, cacheReadTokens: Int, outputTokens: Int, totalTokens: Int,
        cacheHitRate: Double?, turns: Int, steps: Int, llmMs: Int, toolMs: Int, avgTtftMs: Double?,
        outputTokensPerSec: Double?, contextUsedTokens: Int, contextWindowTokens: Int, breakdown: [UsageSlice]
    ) {
        self.uncachedInputTokens = uncachedInputTokens
        self.cacheReadTokens = cacheReadTokens
        self.outputTokens = outputTokens
        self.totalTokens = totalTokens
        self.cacheHitRate = cacheHitRate
        self.turns = turns
        self.steps = steps
        self.llmMs = llmMs
        self.toolMs = toolMs
        self.avgTtftMs = avgTtftMs
        self.outputTokensPerSec = outputTokensPerSec
        self.contextUsedTokens = contextUsedTokens
        self.contextWindowTokens = contextWindowTokens
        self.breakdown = breakdown
    }
}

public func usageFigures(
    usage: TokenUsage?, stats: SessionStats?, pressure: ContextPressure?, breakdown: ContextBreakdown?
) -> UsageFigures? {
    if usage == nil, stats == nil, pressure == nil, breakdown == nil { return nil }
    let uncached = max(0, usage?.uncachedInputTokens ?? 0)
    let cache = max(0, usage?.cacheReadTokens ?? 0)
    let output = max(0, usage?.outputTokens ?? 0)
    let slices = [
        UsageSlice(key: "system", tokens: max(0, breakdown?.systemTokens ?? 0)),
        UsageSlice(key: "tools", tokens: max(0, breakdown?.toolsTokens ?? 0)),
        UsageSlice(key: "messages", tokens: max(0, breakdown?.messageTokens ?? 0)),
    ].filter { $0.tokens > 0 }
    let used = max(0, pressure?.projectedTokens ?? pressure?.pressureTokens ?? 0)
    return UsageFigures(
        uncachedInputTokens: uncached,
        cacheReadTokens: cache,
        outputTokens: output,
        totalTokens: saturatingTokenSum(uncached, cache, output),
        cacheHitRate: cacheHitRate(cacheRead: cache, uncachedInput: uncached),
        turns: max(0, stats?.turns ?? 0),
        steps: max(0, stats?.steps ?? 0),
        llmMs: max(0, stats?.llmMs ?? 0),
        toolMs: max(0, stats?.toolMs ?? 0),
        avgTtftMs: averageTtftMs(ttftMs: stats?.ttftMs ?? 0, ttftSteps: stats?.ttftSteps ?? 0),
        outputTokensPerSec: outputTokensPerSec(decodeTokens: stats?.decodeTokens ?? 0, decodeMs: stats?.decodeMs ?? 0),
        contextUsedTokens: used,
        contextWindowTokens: max(0, pressure?.contextWindow ?? 0),
        breakdown: slices)
}

public func cacheHitRate(cacheRead: Int, uncachedInput: Int) -> Double? {
    let read = Double(max(0, cacheRead))
    let miss = Double(max(0, uncachedInput))
    let denom = read + miss
    guard denom > 0, denom.isFinite else { return nil }
    return min(1, max(0, read / denom))
}

public func averageTtftMs(ttftMs: Int, ttftSteps: Int) -> Double? {
    guard ttftSteps > 0, ttftMs >= 0 else { return nil }
    let value = Double(ttftMs) / Double(ttftSteps)
    return value.isFinite ? value : nil
}

public func outputTokensPerSec(decodeTokens: Int, decodeMs: Int) -> Double? {
    guard decodeMs > 0, decodeTokens >= 0 else { return nil }
    let value = Double(decodeTokens) / Double(decodeMs) * 1000
    return value.isFinite ? value : nil
}

public func contextUsedPercent(used: Int, window: Int) -> Int? {
    guard window > 0 else { return nil }
    let value = Double(max(0, used)) * 100 / Double(window)
    guard value.isFinite else { return nil }
    return min(100, max(0, Int(value)))
}

public func saturatingTokenSum(_ parts: Int...) -> Int {
    var acc = 0.0
    for part in parts where part > 0 {
        acc += Double(part)
    }
    guard acc.isFinite, acc > 0 else { return 0 }
    if acc >= Double(Int.max) { return Int.max }
    return Int(acc)
}

public struct SubagentNode: Equatable, Sendable, Identifiable {
    public var id: String
    public var title: String
    public var running: Bool
    public var depth: Int

    public init(id: String, title: String, running: Bool, depth: Int) {
        self.id = id
        self.title = title
        self.running = running
        self.depth = depth
    }
}

public func hostExposesSubagentParent(_ sessions: [SessionSummary]) -> Bool {
    sessions.isEmpty || sessions.contains { $0.origin == "subagent" || !($0.parentSessionId ?? "").isEmpty }
}

public func flattenSubagents(sessions: [SessionSummary], rootID: String) -> [SubagentNode] {
    guard hostExposesSubagentParent(sessions) else { return [] }
    var seen: Set<String> = []
    var rows: [SubagentNode] = []
    func walk(_ parent: String, depth: Int) {
        guard !parent.isEmpty, seen.insert(parent).inserted else { return }
        let children =
            sessions
            .filter { $0.origin == "subagent" && $0.parentSessionId == parent }
            .sorted { ($0.updatedAt ?? 0) > ($1.updatedAt ?? 0) }
        for child in children {
            let id = child.sessionId ?? ""
            guard !id.isEmpty else { continue }
            rows.append(
                SubagentNode(
                    id: id, title: child.title ?? "", running: child.running == true, depth: depth))
            walk(id, depth: depth + 1)
        }
    }
    walk(rootID, depth: 0)
    return rows
}

public enum ScheduleScope: String, Sendable, Equatable {
    case session
    case all
}

public func visibleSchedules(_ items: [ScheduleTask], scope: ScheduleScope, sessionID: String) -> [ScheduleTask] {
    switch scope {
    case .all:
        return items
    case .session:
        return items.filter { ($0.sessionId ?? sessionID) == sessionID }
    }
}

public enum ScheduleRule: Equatable, Sendable {
    case daily(time: String, zone: String)
    case weekly(days: [Int], time: String, zone: String)
    case every(seconds: Int)
    case cron(expression: String, zone: String)
    case once
    case raw(String)
}

public func scheduleRule(_ task: ScheduleTask) -> ScheduleRule {
    let zone = task.timeZone ?? ""
    switch task.kind {
    case "daily":
        return .daily(time: task.time ?? "", zone: zone)
    case "weekly":
        return .weekly(days: task.weekdays ?? [], time: task.time ?? "", zone: zone)
    case "every":
        return .every(seconds: max(0, task.everySeconds ?? 0))
    case "cron":
        return .cron(expression: task.expression ?? "", zone: zone)
    case "after", "at":
        return .once
    default:
        return .raw(task.kind ?? "")
    }
}

public func shareTranscript(title: String, lines: [(speaker: String, text: String)]) -> String {
    var parts = [title]
    for line in lines where !line.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        parts.append("\(line.speaker): \(line.text)")
    }
    return parts.joined(separator: "\n\n")
}
