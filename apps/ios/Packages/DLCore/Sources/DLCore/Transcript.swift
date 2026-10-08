import DLModels
import Foundation

/// 对话消息流的纯投影（I4.3a）。文案留在 App；这里只给结构和计数。

public enum ProcessOutcome: Equatable, Sendable {
    case ok
    case failed
    case running
}

public enum ProcessStepKind: Equatable, Sendable {
    case reasoning
    case tool(name: String)
    case result
}

public struct ProcessStep: Equatable, Sendable, Identifiable {
    public var id: String
    public var kind: ProcessStepKind
    public var detail: String?
    public var outcome: ProcessOutcome

    public init(id: String, kind: ProcessStepKind, detail: String?, outcome: ProcessOutcome) {
        self.id = id
        self.kind = kind
        self.detail = detail
        self.outcome = outcome
    }
}

public enum ActivityPart: Equatable, Sendable {
    case thinking(Int)
    case commands(Int)
    case reads(Int)
    case edits(Int)
    case searches(Int)
    case fetches(Int)
    case tools(Int)
}

public struct ActivitySummary: Equatable, Sendable {
    public var parts: [ActivityPart]
    public var more: Int

    public init(parts: [ActivityPart], more: Int) {
        self.parts = parts
        self.more = more
    }
}

public struct ProcessBlock: Equatable, Sendable, Identifiable {
    public var id: String
    public var summary: ActivitySummary
    public var running: Bool
    public var command: String?
    public var expanded: Bool
    public var steps: [ProcessStep]

    public init(
        id: String, summary: ActivitySummary, running: Bool, command: String?, expanded: Bool, steps: [ProcessStep]
    ) {
        self.id = id
        self.summary = summary
        self.running = running
        self.command = command
        self.expanded = expanded
        self.steps = steps
    }
}

public struct ChangeLine: Equatable, Sendable, Identifiable {
    public var id: String
    public var path: String
    public var added: Int
    public var deleted: Int

    public init(id: String, path: String, added: Int, deleted: Int) {
        self.id = id
        self.path = path
        self.added = added
        self.deleted = deleted
    }
}

public struct TurnMeta: Equatable, Sendable {
    public var model: String?
    public var thinkingSeconds: Int?
    public var tokens: Int?
    public var elapsedSeconds: Int?

    public init(model: String?, thinkingSeconds: Int?, tokens: Int?, elapsedSeconds: Int?) {
        self.model = model
        self.thinkingSeconds = thinkingSeconds
        self.tokens = tokens
        self.elapsedSeconds = elapsedSeconds
    }
}

public struct TurnTail: Equatable, Sendable, Identifiable {
    public var id: String
    public var lines: [ChangeLine]
    public var added: Int
    public var deleted: Int
    public var total: Int
    public var meta: TurnMeta
    public var copyText: String
    public var showsSuggestions: Bool
    public var changesSeq: Int?

    public init(
        id: String, lines: [ChangeLine], added: Int, deleted: Int, total: Int, meta: TurnMeta, copyText: String,
        showsSuggestions: Bool, changesSeq: Int?
    ) {
        self.id = id
        self.lines = lines
        self.added = added
        self.deleted = deleted
        self.total = total
        self.meta = meta
        self.copyText = copyText
        self.showsSuggestions = showsSuggestions
        self.changesSeq = changesSeq
    }

    public var showsCard: Bool { total > 0 || !lines.isEmpty }
}

public struct UserBubble: Equatable, Sendable, Identifiable {
    public var id: String
    public var text: String

    public init(id: String, text: String) {
        self.id = id
        self.text = text
    }
}

public struct AssistantBlock: Equatable, Sendable, Identifiable {
    public var id: String
    public var markdown: String
    public var streaming: Bool
    public var fade: Bool

    public init(id: String, markdown: String, streaming: Bool, fade: Bool) {
        self.id = id
        self.markdown = markdown
        self.streaming = streaming
        self.fade = fade
    }
}

public enum TranscriptNotice: Equatable, Sendable {
    case injection(labels: [String])
    case goal(round: String?, objective: String?)
    case modelChanged(String?)
    case approval(String)
    case compaction(String)
    case files([String])
    case todo(done: Int, total: Int)
    case plain(String)
}

public struct NoticeBlock: Equatable, Sendable, Identifiable {
    public var id: String
    public var notice: TranscriptNotice

    public init(id: String, notice: TranscriptNotice) {
        self.id = id
        self.notice = notice
    }
}

public enum TranscriptRow: Equatable, Sendable, Identifiable {
    case user(UserBubble)
    case assistant(AssistantBlock)
    case process(ProcessBlock)
    case tail(TurnTail)
    case notice(NoticeBlock)
    case unconfirmed(id: String)

    public var id: String {
        switch self {
        case .user(let row): row.id
        case .assistant(let row): row.id
        case .process(let row): row.id
        case .tail(let row): row.id
        case .notice(let row): row.id
        case .unconfirmed(let id): id
        }
    }
}

/// C08：改动页的轮次导航。只在已加载的对话里有改动的轮次之间切换，不猜不存在的轮。
public enum ChangesTurnNavigator {
    /// 按显示顺序列出有改动卡的 seq（去重）。
    public static func seqs(in rows: [TranscriptRow]) -> [Int] {
        var seen = Set<Int>()
        return rows.compactMap { row -> Int? in
            guard case .tail(let tail) = row, tail.showsCard, let seq = tail.changesSeq, seen.insert(seq).inserted
            else { return nil }
            return seq
        }
    }

    public static func previous(of seq: Int?, in seqs: [Int]) -> Int? {
        guard let seq, let index = seqs.firstIndex(of: seq), index > 0 else { return nil }
        return seqs[index - 1]
    }

    public static func next(of seq: Int?, in seqs: [Int]) -> Int? {
        guard let seq, let index = seqs.firstIndex(of: seq), index + 1 < seqs.count else { return nil }
        return seqs[index + 1]
    }

    /// “就这些改动提问”：生成引用插入草稿，不自动发送。路径用 `@"..."` 引用形式。
    public static func askReference(paths: [String]) -> String {
        paths.filter { !$0.isEmpty }.map { "@\"\($0)\"" }.joined(separator: " ")
    }

    /// 把引用追加到已有草稿后面，绝不覆盖用户已写的内容。
    public static func appending(_ reference: String, to draft: String) -> String {
        guard !reference.isEmpty else { return draft }
        return draft.isEmpty ? reference : draft + "\n" + reference
    }
}

public struct TranscriptSnapshotRecord: Codable, Equatable, Sendable {
    public var version: Int
    public var messages: [HistoryMessage]
    public var stats: HistoryStats?
    public var maxSeq: Int
    public var title: String
    public var workspace: String
    public var running: Bool
    public var step: Int?

    public init(
        version: Int = 1, messages: [HistoryMessage], stats: HistoryStats?, maxSeq: Int, title: String,
        workspace: String, running: Bool, step: Int?
    ) {
        self.version = version
        self.messages = messages
        self.stats = stats
        self.maxSeq = maxSeq
        self.title = title
        self.workspace = workspace
        self.running = running
        self.step = step
    }
}

public enum TranscriptSnapshotCoding {
    public static func encode(_ record: TranscriptSnapshotRecord) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(record)
    }

    public static func decode(_ data: Data) throws -> TranscriptSnapshotRecord {
        try JSONDecoder().decode(TranscriptSnapshotRecord.self, from: data)
    }
}

/// 快照里尚未结束的审批。缺状态按未结束处理，打开后只显示「状态待确认」。
public func isUnconfirmedApproval(_ message: HistoryMessage) -> Bool {
    guard transcriptBucket(message) == .approval else { return false }
    switch message.requestStatus {
    case .pending, .none: return true
    case .unknown(let raw): return raw == "pending"
    case .resolved, .cancelled, .expired: return false
    }
}

public func projectTranscript(
    messages: [HistoryMessage],
    running: Bool,
    stats: HistoryStats?,
    expandedProcessIDs: Set<String>,
    confirmingSnapshot: Bool
) -> [TranscriptRow] {
    let ends = turnEndAssistantIDs(messages, running: running)
    let lastEnd = messages.enumerated().compactMap { index, message -> String? in
        let id = transcriptStableID(message, index)
        return ends.contains(id) ? id : nil
    }.last
    var rows: [TranscriptRow] = []
    var index = 0
    var thinkingMs = 0
    var toolMs = 0
    var changes: ChangesSummary?
    var changesSeq: Int?
    var assistantID: String?
    var copyText = ""

    func flushTail() {
        guard let assistantID, ends.contains(assistantID) else { return }
        let last = assistantID == lastEnd
        let card = changeCard(changes)
        rows.append(
            .tail(
                TurnTail(
                    id: assistantID + "-tail",
                    lines: card.lines,
                    added: changes?.added ?? 0,
                    deleted: changes?.deleted ?? 0,
                    total: card.total,
                    meta: TurnMeta(
                        model: last ? nonEmpty(stats?.tokenUsage?.model) : nil,
                        thinkingSeconds: wholeSeconds(thinkingMs),
                        tokens: last ? tokenTotal(stats?.tokenUsage) : nil,
                        elapsedSeconds: wholeSeconds(thinkingMs + toolMs)),
                    copyText: copyText,
                    showsSuggestions: last && !running,
                    changesSeq: changesSeq)))
    }

    func closeTurn() {
        flushTail()
        thinkingMs = 0
        toolMs = 0
        changes = nil
        changesSeq = nil
        assistantID = nil
        copyText = ""
    }

    while index < messages.count {
        let message = messages[index]
        let id = transcriptStableID(message, index)
        if confirmingSnapshot, isUnconfirmedApproval(message) {
            closeTurn()
            rows.append(.unconfirmed(id: id))
            index += 1
            continue
        }
        switch transcriptBucket(message) {
        case .user:
            closeTurn()
            let text = message.text ?? ""
            if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                rows.append(.user(UserBubble(id: id, text: text)))
            }
            index += 1
        case .assistant:
            let text = message.text ?? ""
            let parts = splitMarkdownForLazyLayout(text)
            if parts.isEmpty {
                index += 1
                continue
            }
            for offset in parts.indices {
                let partID = parts.count == 1 ? id : "\(id)#\(offset)"
                rows.append(
                    .assistant(
                        AssistantBlock(
                            id: partID, markdown: parts[offset], streaming: message.running == true, fade: false)))
            }
            assistantID = id
            copyText = text
            index += 1
        case .process:
            var batch: [HistoryMessage] = []
            var batchIDs: [String] = []
            while index < messages.count, transcriptBucket(messages[index]) == .process {
                batchIDs.append(transcriptStableID(messages[index], index))
                batch.append(messages[index])
                index += 1
            }
            let blockID = (batchIDs.first ?? id) + "-process"
            let counts = activityCounts(batch)
            thinkingMs += counts.thinkingMs
            toolMs += counts.toolMs
            let groupRunning = batch.contains { $0.running == true }
            rows.append(
                .process(
                    ProcessBlock(
                        id: blockID,
                        summary: activitySummary(counts),
                        running: groupRunning,
                        command: groupRunning ? runningCommand(batch) : nil,
                        expanded: expandedProcessIDs.contains(blockID),
                        steps: processSteps(batch, ids: batchIDs, sessionRunning: running))))
        case .changes:
            if let summary = message.changes {
                changes = summary
                changesSeq = message.seq
            }
            index += 1
        case .injection:
            rows.append(
                .notice(
                    NoticeBlock(
                        id: id,
                        notice: .injection(labels: message.labels ?? contextInjectionLabels(message.text ?? "")))))
            index += 1
        case .goal:
            let round = message.goal?.round
            let max = message.goal?.maxRounds
            let label = round == nil ? nil : "\(round ?? 0)/\(max ?? 0)"
            rows.append(
                .notice(
                    NoticeBlock(
                        id: id, notice: .goal(round: label, objective: message.goal?.objective))))
            index += 1
        case .model:
            rows.append(.notice(NoticeBlock(id: id, notice: .modelChanged(nonEmpty(message.text)))))
            index += 1
        case .approval:
            rows.append(.notice(NoticeBlock(id: id, notice: .approval(message.text ?? ""))))
            index += 1
        case .compaction:
            rows.append(.notice(NoticeBlock(id: id, notice: .compaction(message.text ?? ""))))
            index += 1
        case .files:
            rows.append(.notice(NoticeBlock(id: id, notice: .files(message.files ?? []))))
            index += 1
        case .todo:
            let items = message.todos ?? []
            let done = items.filter { $0.status == "completed" || $0.status == "done" }.count
            rows.append(.notice(NoticeBlock(id: id, notice: .todo(done: done, total: items.count))))
            index += 1
        case .plain:
            rows.append(.notice(NoticeBlock(id: id, notice: .plain(message.text ?? ""))))
            index += 1
        }
    }
    flushTail()
    return rows
}

/// 新分段或变长的助手正文标记为淡入。整页替换不要走这里。
public func markFreshAssistants(_ rows: [TranscriptRow], seen: inout [String: String]) -> [TranscriptRow] {
    rows.map { row in
        guard case .assistant(var block) = row else { return row }
        let previous = seen[block.id]
        if previous == nil {
            block.fade = true
        } else if let previous, previous != block.markdown, block.markdown.hasPrefix(previous) {
            block.fade = true
        } else {
            block.fade = false
        }
        seen[block.id] = block.markdown
        return .assistant(block)
    }
}

public func rememberAssistants(_ rows: [TranscriptRow], seen: inout [String: String]) {
    for case .assistant(let block) in rows {
        seen[block.id] = block.markdown
    }
}

public func transcriptStableID(_ message: HistoryMessage, _ index: Int) -> String {
    if let id = message.id, !id.isEmpty { return id }
    if let seq = message.seq { return "seq-\(seq)-\(index)" }
    return "row-\(index)"
}

enum TranscriptBucket: Equatable {
    case user
    case assistant
    case process
    case changes
    case injection
    case goal
    case model
    case approval
    case compaction
    case files
    case todo
    case plain
}

func transcriptBucket(_ message: HistoryMessage) -> TranscriptBucket {
    let resolved = resolvedMessageKind(
        role: message.role ?? "", kind: message.kind?.encodedValue, text: message.text ?? "")
    switch resolved {
    case .user: return .user
    case .injection: return .injection
    case .goalRound: return .goal
    case .modelChanged: return .model
    case .role(let raw):
        switch raw {
        case "assistant": return .assistant
        case "tool_call", "tool_result", "reasoning": return .process
        case "workspace_changes": return .changes
        case "approval": return .approval
        case "compaction": return .compaction
        case "produced_files": return .files
        case "todo": return .todo
        case "context_injection": return .injection
        case "system_notice": return .model
        default: return .plain
        }
    }
}

func turnEndAssistantIDs(_ messages: [HistoryMessage], running: Bool) -> Set<String> {
    var ends: Set<String> = []
    var pending: String?
    for (index, message) in messages.enumerated()
    where transcriptBucket(message) == .user || transcriptBucket(message) == .assistant {
        if transcriptBucket(message) == .user {
            if let pending { ends.insert(pending) }
            pending = nil
        } else {
            pending = transcriptStableID(message, index)
        }
    }
    if !running, let pending { ends.insert(pending) }
    return ends
}

struct ActivityCounts: Equatable {
    var command = 0
    var search = 0
    var read = 0
    var edit = 0
    var fetch = 0
    var other = 0
    var thinkingMs = 0
    var toolMs = 0
    var hasThinking = false
}

func activityCounts(_ items: [HistoryMessage]) -> ActivityCounts {
    var counts = ActivityCounts()
    var readPaths: Set<String> = []
    var readWithoutPath = 0
    for message in items {
        switch message.role {
        case "tool_call":
            switch classifyTool(message.name ?? message.toolName) {
            case .command: counts.command += 1
            case .search: counts.search += 1
            case .read:
                if let path = readPath(message.args) {
                    readPaths.insert(path)
                } else {
                    readWithoutPath += 1
                }
            case .edit: counts.edit += 1
            case .fetch: counts.fetch += 1
            case .other: counts.other += 1
            }
        case "tool_result":
            counts.toolMs += message.durationMs ?? 0
        case "reasoning":
            counts.hasThinking = true
            counts.thinkingMs += message.durationMs ?? 0
        default:
            break
        }
    }
    counts.read = readPaths.count + readWithoutPath
    return counts
}

func activitySummary(_ counts: ActivityCounts, maxKinds: Int = 3) -> ActivitySummary {
    var parts: [ActivityPart] = []
    if counts.hasThinking, counts.thinkingMs > 0, let seconds = wholeSeconds(counts.thinkingMs) {
        parts.append(.thinking(seconds))
    }
    if counts.command > 0 { parts.append(.commands(counts.command)) }
    if counts.read > 0 { parts.append(.reads(counts.read)) }
    if counts.edit > 0 { parts.append(.edits(counts.edit)) }
    if counts.search > 0 { parts.append(.searches(counts.search)) }
    if counts.fetch > 0 { parts.append(.fetches(counts.fetch)) }
    if counts.other > 0 { parts.append(.tools(counts.other)) }
    let limit = max(1, maxKinds)
    if parts.count <= limit { return ActivitySummary(parts: parts, more: 0) }
    return ActivitySummary(parts: Array(parts.prefix(limit)), more: parts.count - limit)
}

enum ToolClass { case command, search, read, edit, fetch, other }

func classifyTool(_ name: String?) -> ToolClass {
    switch name?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
    case "bash", "shell", "exec", "exec_command", "run_code", "terminal": .command
    case "grep", "glob", "search", "ripgrep", "find": .search
    case "read", "read_file", "readfile", "list", "ls", "list_directory": .read
    case "write", "write_file", "edit", "edit_file", "apply_patch", "str_replace_editor": .edit
    case "web_fetch", "webfetch", "fetch": .fetch
    default: .other
    }
}

func readPath(_ args: String?) -> String? {
    guard let object = jsonObject(args) else { return nil }
    for key in ["file_path", "path", "notebook_path"] {
        if case .string(let value) = object[key] {
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty { return trimmed }
        }
    }
    return nil
}

func runningCommand(_ items: [HistoryMessage]) -> String? {
    guard let call = items.last(where: { $0.role == "tool_call" }) else { return nil }
    if let command = commandText(call.args) { return command }
    return nonEmpty(call.name ?? call.toolName)
}

func commandText(_ args: String?) -> String? {
    if let object = jsonObject(args) {
        for key in ["command", "cmd", "input"] {
            if case .string(let value) = object[key], let text = nonEmpty(value) { return text }
        }
        return nil
    }
    return nonEmpty(args)
}

func processSteps(_ items: [HistoryMessage], ids: [String], sessionRunning: Bool) -> [ProcessStep] {
    items.enumerated().map { offset, message in
        let id = ids.indices.contains(offset) ? ids[offset] : "step-\(offset)"
        switch message.role {
        case "reasoning":
            return ProcessStep(
                id: id, kind: .reasoning, detail: firstLine(message.text),
                outcome: message.running == true ? .running : .ok)
        case "tool_result":
            let failed = (message.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                .hasPrefix("error")
            return ProcessStep(
                id: id, kind: .result, detail: firstLine(message.text), outcome: failed ? .failed : .ok)
        default:
            let waiting = message.running == true || (sessionRunning && message.role == "tool_call")
            return ProcessStep(
                id: id,
                kind: .tool(name: message.name ?? message.toolName ?? ""),
                detail: commandText(message.args) ?? firstLine(message.args),
                outcome: waiting && !items.contains(where: { $0.role == "tool_result" && $0.callId == message.callId })
                    ? .running : .ok)
        }
    }
}

func changeCard(_ summary: ChangesSummary?) -> (lines: [ChangeLine], total: Int) {
    let files = summary?.files ?? []
    let total = summary?.total ?? files.count
    let lines = files.prefix(3).enumerated().map { offset, file in
        ChangeLine(
            id: "file-\(offset)-\(file.path ?? file.display ?? "")",
            path: file.display ?? file.path ?? "",
            added: file.added ?? 0,
            deleted: file.deleted ?? 0)
    }
    return (Array(lines), total)
}

func tokenTotal(_ usage: TokenUsage?) -> Int? {
    guard let usage else { return nil }
    if usage.uncachedInputTokens == nil, usage.cacheReadTokens == nil, usage.outputTokens == nil { return nil }
    return (usage.uncachedInputTokens ?? 0) + (usage.cacheReadTokens ?? 0) + (usage.outputTokens ?? 0)
}

func wholeSeconds(_ milliseconds: Int) -> Int? {
    guard milliseconds > 0 else { return nil }
    return max(1, milliseconds / 1000)
}

func firstLine(_ text: String?) -> String? {
    guard let text else { return nil }
    let line = text.split(whereSeparator: \.isNewline).first.map(String.init) ?? text
    let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty { return nil }
    if trimmed.count <= 120 { return trimmed }
    return String(trimmed.prefix(120))
}

func nonEmpty(_ text: String?) -> String? {
    guard let text else { return nil }
    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed.isEmpty ? nil : trimmed
}

func jsonObject(_ text: String?) -> [String: JSONValue]? {
    guard let text, let data = text.data(using: .utf8),
        let value = try? JSONDecoder().decode(JSONValue.self, from: data),
        case .object(let object) = value
    else { return nil }
    return object
}

/// 同一进程内的行高缓存。键含宽度和内容修订，旋转或正文变化后重新测量。
public final class RowMeasureCache: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: Double] = [:]

    public init() {}

    public func store(_ height: Double, id: String, width: Int, revision: Int) {
        guard height > 0, width > 0 else { return }
        lock.lock()
        values[key(id, width, revision)] = height
        lock.unlock()
    }

    public func height(id: String, width: Int, revision: Int) -> Double? {
        lock.lock()
        defer { lock.unlock() }
        return values[key(id, width, revision)]
    }

    private func key(_ id: String, _ width: Int, _ revision: Int) -> String {
        "\(id)|\(width)|\(revision)"
    }
}

public func transcriptMeasureRevision(_ row: TranscriptRow) -> Int {
    var hasher = Hasher()
    hasher.combine(String(describing: row))
    return hasher.finalize()
}
