import DLModels
import Foundation

/// 首页收件箱的纯逻辑（PLAN I4.2）。规则对齐 Android `HomeSections`、`homeInboxTexts`、
/// `filterSidebarSessions`、`sessionsInWorkspace` 和 `homeTimeLabel`。不含文案和网络。

public enum InboxSection: Hashable, Sendable, CaseIterable {
    case awaiting
    case running
    case recent
}

public struct InboxSectionGroup: Equatable, Sendable {
    public var section: InboxSection
    public var sessions: [SessionSummary]

    public init(section: InboxSection, sessions: [SessionSummary]) {
        self.section = section
        self.sessions = sessions
    }
}

public enum InboxListFilter: Hashable, Sendable, CaseIterable {
    case all
    case awaiting
    case running
    case recent
}

public enum InboxDotKind: Equatable, Sendable {
    case wait
    case accent
}

public enum InboxPendingKind: Equatable, Sendable {
    case none
    case approval
    case question
}

public enum InboxStatusKind: Equatable, Sendable {
    case waitingApproval
    case waitingAnswer
    case waiting
    case done
    case stopped(InboxStopKind)
}

public enum InboxStopKind: Equatable, Sendable {
    case interrupted
    case stopped
    case error
    case maxTokens
    case aborted
    case timeout
    case other(String)
}

public enum InboxPreviewKind: Equatable, Sendable {
    case plain(String)
    case files(Int)
    case tool(label: String, step: Int?)
    case thinking
    case writing
    case running
    case question(String)
    case lastSeenTool(label: String, step: Int?)
    case lastSeenThinking
    case lastSeenWriting
    case lastSeenRunning
}

public struct InboxRowContent: Equatable, Sendable {
    public var dot: InboxDotKind?
    public var status: InboxStatusKind?
    public var workspace: String?
    public var subagentCount: Int
    public var preview: InboxPreviewKind?
    public var command: String?
    public var pending: InboxPendingKind
    public var allowsSwipe: Bool

    public init(
        dot: InboxDotKind?,
        status: InboxStatusKind?,
        workspace: String?,
        subagentCount: Int,
        preview: InboxPreviewKind?,
        command: String?,
        pending: InboxPendingKind,
        allowsSwipe: Bool
    ) {
        self.dot = dot
        self.status = status
        self.workspace = workspace
        self.subagentCount = subagentCount
        self.preview = preview
        self.command = command
        self.pending = pending
        self.allowsSwipe = allowsSwipe
    }
}

public enum InboxPhoneAction: Equatable, Sendable {
    case approval(sessionID: String, approvalID: String, toolName: String?)
    case question(sessionID: String, rpcID: String, prompt: String?)

    public var sessionID: String {
        switch self {
        case .approval(let sessionID, _, _), .question(let sessionID, _, _): sessionID
        }
    }
}

public enum InboxTime: Equatable, Sendable {
    case none
    case justNow
    case minutes(Int)
    case hours(Int)
    case yesterday
    case weekday(Date)
    case days(Int)
}

public struct InboxSearchHit: Equatable, Sendable {
    public var session: SessionSummary
    public var snippet: String?

    public init(session: SessionSummary, snippet: String?) {
        self.session = session
        self.snippet = snippet
    }
}

public struct InboxSearchGroups: Equatable, Sendable {
    public var titleMatches: [SessionSummary]
    public var contentMatches: [InboxSearchHit]

    public init(titleMatches: [SessionSummary], contentMatches: [InboxSearchHit]) {
        self.titleMatches = titleMatches
        self.contentMatches = contentMatches
    }
}

public struct InboxWorkspaceAccount: Equatable, Sendable {
    public var path: String
    public var sessionIDs: [String]

    public init(path: String, sessionIDs: [String]) {
        self.path = path
        self.sessionIDs = sessionIDs
    }
}

private let staleBlankInterval: TimeInterval = 24 * 60 * 60
private let millisPerSecond = 1000

public func inboxSessionMillis(_ timestamp: Int?) -> Int? {
    guard let timestamp, timestamp > 0 else { return nil }
    if timestamp < 1_000_000_000_000 { return timestamp * millisPerSecond }
    return timestamp
}

public func inboxNormalizeWorkspacePath(_ path: String) -> String {
    var value = path
    while value.count > 1 && value.hasSuffix("/") { value.removeLast() }
    return value
}

public func inboxIsUserWorkspace(_ cwd: String?) -> Bool {
    guard let cwd, !cwd.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return true }
    let parts = cwd.split(separator: "/").map(String.init).filter { !$0.isEmpty }
    if parts.isEmpty { return false }
    let blocked: Set<String> = ["node_modules", ".npm", ".bin", "Library", "Applications", "System", "tmp", "private"]
    return parts.allSatisfy { part in
        !part.hasPrefix(".") && !blocked.contains(part)
    }
}

/// 同一屏末级同名时逐级补父目录，直到这批名字唯一。
public func inboxWorkspaceLabels(_ paths: [String]) -> [String: String] {
    let segments = paths.map { path in
        inboxNormalizeWorkspacePath(path).split(separator: "/").map(String.init)
    }
    var labels: [String: String] = [:]
    for (index, parts) in segments.enumerated() {
        guard !parts.isEmpty else {
            labels[paths[index]] = paths[index]
            continue
        }
        var depth = 1
        var label = parts.last ?? paths[index]
        while depth <= parts.count {
            let candidate = parts.suffix(depth).joined(separator: "/")
            let duplicated = segments.filter { $0.suffix(depth).joined(separator: "/") == candidate }.count > 1
            label = candidate
            if !duplicated { break }
            depth += 1
        }
        labels[paths[index]] = label
    }
    return labels
}

public func inboxWorkspaceName(_ cwd: String?) -> String? {
    guard let cwd else { return nil }
    let trimmed = inboxNormalizeWorkspacePath(cwd)
    let leaf = trimmed.split(separator: "/").last.map(String.init)
    guard let leaf, !leaf.isEmpty else { return nil }
    return leaf
}

public func inboxDisplayTitle(_ title: String?) -> String {
    let title = title ?? ""
    let trimmed = title.drop(while: \.isWhitespace)
    guard trimmed.hasPrefix("@/") || trimmed.hasPrefix("@~/") else { return title }
    let end = trimmed.firstIndex(where: \.isWhitespace) ?? trimmed.endIndex
    var path = trimmed[trimmed.index(after: trimmed.startIndex)..<end]
    while path.last == "/" { path = path.dropLast() }
    let leaf = path.split(separator: "/").last.map(String.init) ?? ""
    if leaf.isEmpty || leaf == "~" { return title }
    return leaf + trimmed[end...]
}

public func inboxVisibleSessions(
    _ sessions: [SessionSummary],
    archivedIDs: Set<String>,
    deletedIDs: Set<String>,
    now: Date
) -> [SessionSummary] {
    // 秒级 updatedAt 先换成毫秒。Android 拿原始值和「现在的毫秒」比，秒级戳会全部算成过期。
    let cutoff = now.addingTimeInterval(-staleBlankInterval).timeIntervalSince1970 * 1000
    return sessions.filter { session in
        guard let id = nonBlank(session.sessionId) else { return false }
        if archivedIDs.contains(id) || deletedIDs.contains(id) { return false }
        if session.origin == "subagent" { return false }
        if session.blank == true, let millis = inboxSessionMillis(session.updatedAt), Double(millis) < cutoff {
            return false
        }
        return true
    }
}

/// 2.1：注册表里的工作区都保留，会话只出现一次。注册表未就绪时才按 cwd 回退。
public struct InboxWorkspaceFolder: Equatable, Sendable {
    public var path: String?
    public var sessions: [SessionSummary]

    public init(path: String?, sessions: [SessionSummary]) {
        self.path = path
        self.sessions = sessions
    }

    public var key: String { path.map { "workspace:\($0)" } ?? "ungrouped" }
    public var awaitingCount: Int { sessions.filter { $0.awaitingInput == true }.count }
    public var runningCount: Int { sessions.filter { $0.running == true && $0.awaitingInput != true }.count }
}

public func inboxWorkspaceFolders(
    sessions: [SessionSummary],
    workspaces: [String],
    accounts: [InboxWorkspaceAccount],
    registryReady: Bool
) -> [InboxWorkspaceFolder] {
    var paths: [String] = []
    var seen = Set<String>()
    for raw in workspaces {
        let path = inboxNormalizeWorkspacePath(raw)
        guard !path.isEmpty, seen.insert(path).inserted else { continue }
        paths.append(path)
    }
    var buckets = Dictionary(uniqueKeysWithValues: paths.map { ($0, [SessionSummary]()) })
    var ungrouped: [SessionSummary] = []
    let ordered = sessions.sorted {
        (inboxSessionMillis($0.updatedAt) ?? 0) > (inboxSessionMillis($1.updatedAt) ?? 0)
    }
    var used = Set<String>()
    for session in ordered {
        guard let id = nonBlank(session.sessionId), used.insert(id).inserted else { continue }
        let owner = accounts.first { $0.sessionIDs.contains(id) }.map { inboxNormalizeWorkspacePath($0.path) }
        let fallback = registryReady ? nil : session.cwd.map(inboxNormalizeWorkspacePath)
        let key = (owner?.isEmpty == false ? owner : nil) ?? (fallback?.isEmpty == false ? fallback : nil)
        if let key, buckets[key] != nil {
            buckets[key, default: []].append(session)
        } else {
            ungrouped.append(session)
        }
    }
    var folders = paths.map { InboxWorkspaceFolder(path: $0, sessions: buckets[$0] ?? []) }
    if !ungrouped.isEmpty { folders.append(InboxWorkspaceFolder(path: nil, sessions: ungrouped)) }
    return folders
}

public func inboxSections(_ sessions: [SessionSummary]) -> [InboxSectionGroup] {
    let ordered = sessions.sorted { lhs, rhs in
        (inboxSessionMillis(lhs.updatedAt) ?? 0) > (inboxSessionMillis(rhs.updatedAt) ?? 0)
    }
    var seen = Set<String>()
    var buckets: [InboxSection: [SessionSummary]] = [:]
    for session in ordered {
        guard let id = nonBlank(session.sessionId), seen.insert(id).inserted else { continue }
        let section: InboxSection
        if session.awaitingInput == true {
            section = .awaiting
        } else if session.running == true {
            section = .running
        } else {
            section = .recent
        }
        buckets[section, default: []].append(session)
    }
    return InboxSection.allCases.compactMap { section in
        guard let rows = buckets[section], !rows.isEmpty else { return nil }
        return InboxSectionGroup(section: section, sessions: rows)
    }
}

public func inboxFiltered(_ groups: [InboxSectionGroup], filter: InboxListFilter) -> [InboxSectionGroup] {
    switch filter {
    case .all: groups
    case .awaiting: groups.filter { $0.section == .awaiting }
    case .running: groups.filter { $0.section == .running }
    case .recent: groups.filter { $0.section == .recent }
    }
}

public func inboxActionableSessionID(_ sessions: [SessionSummary]) -> String? {
    inboxSections(sessions).first { $0.section == .awaiting }?.sessions.first?.sessionId
}

public func inboxPhoneAction(sessionID: String, response: RequestsSnapshotResponse) -> InboxPhoneAction? {
    struct Candidate {
        var at: Int
        var approval: Bool
        var action: InboxPhoneAction
    }
    var candidates: [Candidate] = []
    for approval in response.approvals ?? [] {
        guard approval.status == nil || approval.status == .pending else { continue }
        guard let id = nonBlank(approval.approvalId) else { continue }
        candidates.append(
            Candidate(
                at: approval.createdAt ?? 0,
                approval: true,
                action: .approval(sessionID: sessionID, approvalID: id, toolName: nonBlank(approval.toolName))))
    }
    for question in response.questions ?? [] {
        guard question.status == nil || question.status == .pending else { continue }
        guard let id = nonBlank(question.rpcId) else { continue }
        let prompt = question.questions?.lazy.compactMap { nonBlank($0.question) ?? nonBlank($0.header) }.first
        candidates.append(
            Candidate(
                at: question.createdAt ?? 0,
                approval: false,
                action: .question(sessionID: sessionID, rpcID: id, prompt: prompt)))
    }
    return candidates.max { lhs, rhs in
        if lhs.at != rhs.at { return lhs.at < rhs.at }
        return !lhs.approval && rhs.approval
    }?.action
}

public func inboxRowContent(session: SessionSummary, action: InboxPhoneAction?, offline: Bool) -> InboxRowContent {
    let workspace = inboxWorkspaceName(session.cwd)
    let subagents = max(0, session.subagentCount ?? 0)
    let matched = action?.sessionID == session.sessionId ? action : nil
    if case .approval(_, _, let toolName) = matched {
        return InboxRowContent(
            dot: .wait,
            status: .waitingApproval,
            workspace: workspace,
            subagentCount: subagents,
            preview: nil,
            command: toolName,
            pending: .approval,
            allowsSwipe: false)
    }
    if case .question(_, _, let prompt) = matched {
        return InboxRowContent(
            dot: .wait,
            status: .waitingAnswer,
            workspace: workspace,
            subagentCount: subagents,
            preview: prompt.map(InboxPreviewKind.question),
            command: nil,
            pending: .question,
            allowsSwipe: true)
    }
    if session.awaitingInput == true {
        // No request snapshot. A host event names approval or a question;
        // a list row only carries awaitingInput, so an unknown kind stays generic.
        let status: InboxStatusKind =
            switch session.hostWait {
            case .awaitingApproval?: .waitingApproval
            case .awaitingInput?: .waitingAnswer
            default: .waiting
            }
        return InboxRowContent(
            dot: .wait,
            status: status,
            workspace: workspace,
            subagentCount: subagents,
            preview: nil,
            command: nil,
            pending: .none,
            allowsSwipe: true)
    }
    if session.running == true {
        let body = inboxRunningPreview(session)
        return InboxRowContent(
            dot: offline ? nil : .accent,
            status: nil,
            workspace: workspace,
            subagentCount: subagents,
            preview: offline ? inboxLastSeen(body) : body,
            command: nil,
            pending: .none,
            allowsSwipe: true)
    }
    let status: InboxStatusKind
    if let reason = nonBlank(session.stoppedReason) {
        status = .stopped(inboxStopKind(reason))
    } else {
        status = .done
    }
    return InboxRowContent(
        dot: nil,
        status: status,
        workspace: workspace,
        subagentCount: subagents,
        preview: inboxResultPreview(session),
        command: nil,
        pending: .none,
        allowsSwipe: true)
}

public func inboxTime(_ updatedAt: Int?, now: Date, calendar: Calendar) -> InboxTime {
    guard let millis = inboxSessionMillis(updatedAt) else { return .none }
    let date = Date(timeIntervalSince1970: Double(millis) / 1000)
    let diff = now.timeIntervalSince(date)
    if diff < 60 { return .justNow }
    if diff < 3_600 { return .minutes(max(1, Int(diff / 60))) }
    let startNow = calendar.startOfDay(for: now)
    let startThen = calendar.startOfDay(for: date)
    let days = calendar.dateComponents([.day], from: startThen, to: startNow).day ?? 0
    if days <= 0 { return .hours(max(1, Int(diff / 3_600))) }
    if days == 1 { return .yesterday }
    if (2...6).contains(days) { return .weekday(date) }
    return .days(days)
}

public func inboxSearchCandidates(
    _ sessions: [SessionSummary],
    needle: String,
    serverIDs: [String]
) -> [SessionSummary] {
    let query = needle.trimmingCharacters(in: .whitespacesAndNewlines)
    if query.isEmpty { return sessions }
    let server = Set(serverIDs)
    return sessions.filter { session in
        if let id = session.sessionId, server.contains(id) { return true }
        if containsFold(session.title, query) { return true }
        if containsFold(session.cwd, query) { return true }
        return false
    }
}

public func inboxSearchGroups(
    sessions: [SessionSummary],
    needle: String,
    snippets: [String: String]
) -> InboxSearchGroups {
    let ordered = inboxSections(sessions).flatMap(\.sessions)
    let query = needle.trimmingCharacters(in: .whitespacesAndNewlines)
    if query.isEmpty { return InboxSearchGroups(titleMatches: ordered, contentMatches: []) }
    let title = ordered.filter { containsFold($0.title, query) }
    let titleIDs = Set(title.compactMap(\.sessionId))
    let content = ordered.filter { session in
        guard let id = session.sessionId else { return false }
        return !titleIDs.contains(id)
    }
    .map { session in
        InboxSearchHit(session: session, snippet: session.sessionId.flatMap { nonBlank(snippets[$0]) })
    }
    return InboxSearchGroups(titleMatches: title, contentMatches: content)
}

public func inboxSessions(
    inWorkspace workspace: String,
    sessions: [SessionSummary],
    accounts: [InboxWorkspaceAccount],
    deletedWorkspaces: Set<String> = []
) -> [SessionSummary] {
    let target = inboxNormalizeWorkspacePath(workspace)
    let deleted = Set(deletedWorkspaces.map(inboxNormalizeWorkspacePath))
    if target.isEmpty || deleted.contains(target) || !inboxIsUserWorkspace(target) { return [] }
    return sessions.filter { session in
        let owner = accounts.compactMap { account -> String? in
            guard let id = session.sessionId, account.sessionIDs.contains(id) else { return nil }
            let path = inboxNormalizeWorkspacePath(account.path)
            guard !path.isEmpty, !deleted.contains(path), inboxIsUserWorkspace(path) else { return nil }
            return path
        }
        .first
        if let owner { return owner == target }
        return session.cwd.map(inboxNormalizeWorkspacePath) == target
    }
}

public func inboxVisibleWorkspaces(_ workspaces: [WorkspaceInfo], deleted: Set<String> = []) -> [String] {
    let hidden = Set(deleted.map(inboxNormalizeWorkspacePath))
    var seen = Set<String>()
    var paths: [String] = []
    for workspace in workspaces {
        guard let raw = workspace.path else { continue }
        let path = inboxNormalizeWorkspacePath(raw)
        guard !path.isEmpty, inboxIsUserWorkspace(path), !hidden.contains(path), seen.insert(path).inserted else {
            continue
        }
        paths.append(path)
    }
    return paths.sorted {
        (inboxWorkspaceName($0) ?? $0).localizedCaseInsensitiveCompare(inboxWorkspaceName($1) ?? $1)
            == .orderedAscending
    }
}

public func inboxMatchRanges(in text: String, needle: String) -> [Range<String.Index>] {
    let query = needle.trimmingCharacters(in: .whitespacesAndNewlines)
    if query.isEmpty { return [] }
    var ranges: [Range<String.Index>] = []
    var cursor = text.startIndex
    while cursor < text.endIndex,
        let found = text.range(
            of: query, options: [.caseInsensitive, .diacriticInsensitive], range: cursor..<text.endIndex)
    {
        ranges.append(found)
        cursor = found.upperBound
    }
    return ranges
}

public func inboxApplying(
    _ event: HostSessionStateEvent,
    to sessions: [SessionSummary],
    nowMillis: Int
) -> [SessionSummary] {
    guard let id = nonBlank(event.sessionId) else { return sessions }
    var next = sessions
    if let index = next.firstIndex(where: { $0.sessionId == id }) {
        next[index] = inboxUpdated(next[index], event: event, nowMillis: nowMillis)
    } else {
        var created = SessionSummary(sessionId: id, title: event.title, updatedAt: nowMillis)
        created = inboxUpdated(created, event: event, nowMillis: nowMillis)
        next.append(created)
    }
    return next
}

private func inboxUpdated(_ session: SessionSummary, event: HostSessionStateEvent, nowMillis: Int) -> SessionSummary {
    var next = session
    next.updatedAt = nowMillis
    if let title = nonBlank(event.title) { next.title = title }
    if let origin = event.origin { next.origin = origin.encodedValue }
    switch event.state {
    case .running?:
        next.running = true
        next.awaitingInput = false
        next.hostWait = nil
    case .awaitingApproval?:
        next.running = true
        next.awaitingInput = true
        next.hostWait = .awaitingApproval
    case .awaitingInput?:
        next.running = true
        next.awaitingInput = true
        next.hostWait = .awaitingInput
    case .completed?:
        next.running = false
        next.awaitingInput = false
        next.hostWait = nil
        next.activity = nil
        next.stoppedReason = nil
    case .failed?:
        next.running = false
        next.awaitingInput = false
        next.hostWait = nil
        next.activity = nil
        next.stoppedReason = "error"
    case .stopped?:
        next.running = false
        next.awaitingInput = false
        next.hostWait = nil
        next.activity = nil
        if nonBlank(next.stoppedReason) == nil { next.stoppedReason = "stopped" }
    case .unknown(_)?, nil:
        break
    }
    return next
}

private func inboxRunningPreview(_ session: SessionSummary) -> InboxPreviewKind {
    switch session.activity?.kind {
    case .tool?:
        if let label = nonBlank(session.activity?.label) {
            return .tool(label: label, step: session.activity?.step)
        }
        return .running
    case .thinking?: return .thinking
    case .writing?: return .writing
    default: return .running
    }
}

private func inboxLastSeen(_ preview: InboxPreviewKind) -> InboxPreviewKind {
    switch preview {
    case .tool(let label, let step): .lastSeenTool(label: label, step: step)
    case .thinking: .lastSeenThinking
    case .writing: .lastSeenWriting
    case .running: .lastSeenRunning
    default: preview
    }
}

private func inboxResultPreview(_ session: SessionSummary) -> InboxPreviewKind? {
    if let text = nonBlank(session.lastResult?.text) { return .plain(text) }
    if let files = session.lastResult?.files, files > 0 { return .files(files) }
    return nil
}

private func inboxStopKind(_ reason: String) -> InboxStopKind {
    switch reason.lowercased() {
    case "interrupted": .interrupted
    case "stopped": .stopped
    case "error": .error
    case "maxtokens", "max_tokens": .maxTokens
    case "aborted": .aborted
    case "timeout": .timeout
    default: .other(reason)
    }
}

private func containsFold(_ text: String?, _ needle: String) -> Bool {
    guard let text else { return false }
    return text.range(of: needle, options: [.caseInsensitive, .diacriticInsensitive]) != nil
}

private func nonBlank(_ value: String?) -> String? {
    guard let value else { return nil }
    let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed.isEmpty ? nil : trimmed
}
