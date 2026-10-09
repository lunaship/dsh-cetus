import DLCore
import Foundation

enum InboxText: String, Equatable, Sendable {
    case brand
    case settings
    case filter
    case filterAll
    case filterAwaiting
    case filterRunning
    case filterRecent
    case newTask
    case searchPrompt
    case searchRecent
    case titleMatches
    case contentMatches
    case searchEmpty
    case searchFailed
    case searchDegraded
    case searching
    case waitingApproval
    case waitingAnswer
    case waiting
    case running
    case done
    case unknownStatus
    case interrupted
    case stopped
    case errorStopped
    case maxTokens
    case aborted
    case timeout
    case runningTool
    case step
    case thinking
    case writing
    case question
    case files
    case lastSeen
    case justNow
    case minutes
    case hours
    case days
    case yesterday
    case subagents
    case untitled
    case reject
    case allowOnce
    case answer
    case retry
    case diagnostics
    case offlineTitle
    case offlineHint
    case offlineHintPlain
    /// C14：拉取失败 ≠ 没有任务。这两条是失败态专用文案。
    case loadFailedTitle
    case loadFailedHint
    case approveBlocked
    case emptyTitle
    case emptyHint
    case pickSession
    case startFrom
    case starterOrganize
    case starterTest
    case starterDiff
    case workspaceEmptyTitle
    case workspaceEmptyHint
    case showAll
    case showAllCount
    case collapseAll
    case newHere
    case ungrouped
    case more
    case deleteWorkspace
    case deleteWorkspaceTitle
    case deleteWorkspaceMessage
    case computers
    case workspaces
    case allWorkspaces
    case addWorkspace
    case archived
    case archivedEmpty
    case online
    case remote
    case offline
    case connecting
    case subtitleOnline
    case subtitleOffline
    case subtitleRemote
    case subtitleChecking
    case rename
    case fork
    case share
    case archive
    case delete
    case deleteTitle
    case deleteMessage
    case cancel
    case renameHint
    case loading
    case unauthorized
    case certificate
    case laterSession
    case laterSettings
    case laterComputer
    case laterDiagnostics
    case laterNewTask
    case laterAddWorkspace
    case archiveFailed
    case forkFailed
    case renameFailed
    case approvalFailed
    case deleteFailed
    case pushMissing
    case shareBody

    var fallback: String {
        switch self {
        case .brand: "cetus"
        case .settings: "Settings"
        case .filter: "Filter"
        case .filterAll: "All"
        case .filterAwaiting: "Waiting for you"
        case .filterRunning: "Running"
        case .filterRecent: "Recent"
        case .newTask: "New task"
        case .searchPrompt: "Search"
        case .searchRecent: "Recent searches"
        case .titleMatches: "Title matches"
        case .contentMatches: "Content matches"
        case .searchEmpty: "No matching sessions"
        case .searchFailed: "Search failed"
        case .searchDegraded: "Full-text search is unavailable. Matched titles instead."
        case .searching: "Searching…"
        case .waitingApproval: "Waiting for approval"
        case .waitingAnswer: "Waiting for your answer"
        case .waiting: "Waiting for you"
        case .running: "Running"
        case .done: "Done"
        case .unknownStatus: "Unknown — updating"
        case .interrupted: "Interrupted"
        case .stopped: "Stopped"
        case .errorStopped: "Stopped by error"
        case .maxTokens: "Token limit reached"
        case .aborted: "Cancelled"
        case .timeout: "Timed out"
        case .runningTool: "Running %@"
        case .step: "step %d"
        case .thinking: "Thinking"
        case .writing: "Writing a reply"
        case .question: "Q: %@"
        case .files: "%d files changed"
        case .lastSeen: "Last seen: "
        case .justNow: "Just now"
        case .minutes: "%dm"
        case .hours: "%dh"
        case .days: "%dd"
        case .yesterday: "Yesterday"
        case .subagents: "%d subagents running"
        case .untitled: "Untitled session"
        case .reject: "Reject"
        case .allowOnce: "Allow once"
        case .answer: "Answer"
        case .retry: "Retry"
        case .diagnostics: "Diagnostics"
        case .offlineTitle: "Can't reach %@"
        case .offlineHint: "This is the state from %@. It reconnects once the computer is back online."
        case .offlineHintPlain: "This is the last saved state. It reconnects once the computer is back online."
        case .loadFailedTitle: "Pairing is no longer valid"
        case .loadFailedHint: "Pair with this computer again to see your tasks. Retrying will not help."
        case .approveBlocked: "You can approve once the computer is back online"
        case .emptyTitle: "Nothing needs you right now"
        case .emptyHint: "Tasks you start on the computer show up here too."
        case .pickSession: "Choose a conversation"
        case .startFrom: "Start with one thing"
        case .starterOrganize: "See how this project is organised"
        case .starterTest: "Run the tests and fix what fails"
        case .starterDiff: "Review the changes not committed yet"
        case .workspaceEmptyTitle: "No tasks in this workspace yet"
        case .workspaceEmptyHint: "Start one here, or switch back to all workspaces"
        case .showAll: "View all"
        case .showAllCount: "Show all %d"
        case .collapseAll: "Collapse"
        case .newHere: "New task here"
        case .ungrouped: "Ungrouped"
        case .more: "More"
        case .deleteWorkspace: "Delete workspace"
        case .deleteWorkspaceTitle: "Delete workspace?"
        case .deleteWorkspaceMessage: "Remove %@ from this phone. Sessions stay on the computer."
        case .computers: "Computer"
        case .workspaces: "Workspaces"
        case .allWorkspaces: "All workspaces"
        case .addWorkspace: "Add workspace"
        case .archived: "Archived"
        case .archivedEmpty: "No archived sessions"
        case .online: "Online"
        case .remote: "Remote"
        case .offline: "Offline"
        case .connecting: "Connecting"
        case .subtitleOnline: "● %@ · Online"
        case .subtitleOffline: "● %@ · Offline"
        case .subtitleRemote: "● %@ · Remote"
        case .subtitleChecking: "● %@"
        case .rename: "Rename"
        case .fork: "Fork as new session"
        case .share: "Share conversation"
        case .archive: "Archive"
        case .delete: "Delete"
        case .deleteTitle: "Delete session?"
        case .deleteMessage:
            "Remove \"%@\" from this phone and archive it on the computer. View it in Settings → Sessions."
        case .cancel: "Cancel"
        case .renameHint: "Set a name that is easy to recognize."
        case .loading: "Connecting…"
        case .unauthorized: "This phone is no longer authorized. Pair again."
        case .certificate: "The computer certificate changed. Pair again."
        case .laterSession: "The conversation opens in a later update."
        case .laterSettings: "Settings opens in a later update."
        case .laterComputer: "Computer and pairing opens in a later update."
        case .laterDiagnostics: "Diagnostics opens in a later update."
        case .laterNewTask: "New task opens in a later update."
        case .laterAddWorkspace: "Add workspace opens in a later update."
        case .archiveFailed: "Couldn't archive"
        case .forkFailed: "Couldn't fork"
        case .renameFailed: "Couldn't rename"
        case .approvalFailed: "Couldn't send the decision"
        case .deleteFailed: "Couldn't delete"
        case .pushMissing: "That task is no longer on this computer."
        case .shareBody: "%@\n%@"
        }
    }
}

struct InboxCopy {
    var locale: Locale

    func text(_ key: InboxText) -> String {
        L10n.string("inbox.\(key.rawValue)", fallback: key.fallback, locale: locale)
    }

    func format(_ key: InboxText, _ arguments: CVarArg...) -> String {
        String(format: text(key), locale: locale, arguments: arguments)
    }

    func section(_ section: InboxSection) -> String {
        switch section {
        case .awaiting: text(.filterAwaiting)
        case .running: text(.filterRunning)
        case .recent: text(.filterRecent)
        }
    }

    func filter(_ filter: InboxListFilter) -> String {
        switch filter {
        case .all: text(.filterAll)
        case .awaiting: text(.filterAwaiting)
        case .running: text(.filterRunning)
        case .recent: text(.filterRecent)
        }
    }

    func status(_ status: InboxStatusKind) -> String {
        switch status {
        case .waitingApproval: text(.waitingApproval)
        case .waitingAnswer: text(.waitingAnswer)
        case .waiting: text(.waiting)
        case .done: text(.done)
        case .stopped(let reason): stop(reason)
        case .unknown: text(.unknownStatus)
        }
    }

    func stop(_ reason: InboxStopKind) -> String {
        switch reason {
        case .interrupted: text(.interrupted)
        case .stopped: text(.stopped)
        case .error: text(.errorStopped)
        case .maxTokens: text(.maxTokens)
        case .aborted: text(.aborted)
        case .timeout: text(.timeout)
        case .other(let raw): raw
        }
    }

    func preview(_ kind: InboxPreviewKind) -> String {
        switch kind {
        case .plain(let value): value
        case .files(let count): format(.files, count)
        case .tool(let label, let step): tool(label, step: step)
        case .thinking: text(.thinking)
        case .writing: text(.writing)
        case .running: text(.running)
        case .question(let prompt): format(.question, prompt)
        case .lastSeenTool(let label, let step): text(.lastSeen) + tool(label, step: step)
        case .lastSeenThinking: text(.lastSeen) + text(.thinking)
        case .lastSeenWriting: text(.lastSeen) + text(.writing)
        case .lastSeenRunning: text(.lastSeen) + text(.running)
        }
    }

    func meta(workspace: String?, subagents: Int, status: InboxStatusKind?) -> String {
        var parts: [String] = []
        if let workspace, !workspace.isEmpty { parts.append(workspace) }
        if subagents > 0 { parts.append(format(.subagents, subagents)) }
        if let status { parts.append(self.status(status)) }
        return parts.joined(separator: " · ")
    }

    func time(_ time: InboxTime) -> String {
        switch time {
        case .none: ""
        case .justNow: text(.justNow)
        case .minutes(let count): format(.minutes, count)
        case .hours(let count): format(.hours, count)
        case .yesterday: text(.yesterday)
        case .weekday(let date):
            date.formatted(Date.FormatStyle().weekday(.abbreviated).locale(locale))
        case .days(let count): format(.days, count)
        }
    }

    func subtitle(name: String, link: InboxLink) -> String {
        switch link {
        case .checking(nil): format(.subtitleChecking, name)
        case .checking(.local), .online(.local): format(.subtitleOnline, name)
        case .checking(.remote), .online(.remote): format(.subtitleRemote, name)
        case .offline: format(.subtitleOffline, name)
        }
    }

    func linkWord(_ link: InboxLink) -> String {
        switch link {
        case .checking: text(.connecting)
        case .online(.local): text(.online)
        case .online(.remote): text(.remote)
        case .offline: text(.offline)
        }
    }

    private func tool(_ label: String, step: Int?) -> String {
        var line = format(.runningTool, label)
        if let step { line += " · " + format(.step, step) }
        return line
    }
}
