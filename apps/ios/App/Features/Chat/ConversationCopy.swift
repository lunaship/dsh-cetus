import DLCore
import DLModels
import Foundation

enum ChatText: String {
    case running
    case completed
    case failed
    case stopped
    case awaiting
    case step
    case changes
    case more
    case menuLater
    case copy
    case regenerate
    case share
    case viewAll
    case filesChanged
    case suggestContinue
    case suggestReview
    case thinkingSeconds
    case tokens
    case elapsedSeconds
    case elapsedMinutes
    case activityThinking
    case activityCommands
    case activityReads
    case activityEdits
    case activitySearches
    case activityFetches
    case activityTools
    case activityMore
    case runningNow
    case loadImage
    case imageBlocked
    case imageFailed
    case unconfirmed
    case formula
    case diagram
    case workspaceUnknown
    case added
    case deleted
    case injection
    case goal
    case modelChanged
    case compaction
    case produced
    case approval
    case todo
    case loadFailed
    case empty
    case untitled
    case reasoning
    case result
    case process
    case expand
    case collapse
    case diffBadge

    case statusConnecting
    case statusReconnecting
    case statusFailed
    case statusPending
    case statusGoal
    case statusRounds
    case statusRound
    case statusPlan
    case statusPreview
    case statusExpand
    case statusCollapse
    case statusDone
    case statusNotDone
    case statusActive
    case statusPaused
    case statusBlocked
    case statusUnknown

    case statusDemoObjective
    case statusDemoFind
    case statusDemoBroadcast
    case statusDemoExpiry
    case statusDemoVerify
    case statusDemoTitle
    case statusDemoUser
    case statusDemoAssistant
    case send
    case reject
    case allowOnce
    case waitApproval
    case waitAnswer
    case question

    var fallback: String {
        switch self {
        case .running: "Running"
        case .completed: "Completed"
        case .failed: "Failed"
        case .stopped: "Stopped"
        case .awaiting: "Waiting for you"
        case .step: "Step %d"
        case .changes: "Changes"
        case .more: "More"
        case .menuLater: "More actions arrive in a later update."
        case .copy: "Copy"
        case .regenerate: "Regenerate"
        case .share: "Share"
        case .viewAll: "View all changes"
        case .filesChanged: "Changed %d files"
        case .suggestContinue: "Continue"
        case .suggestReview: "Review this turn's changes"
        case .thinkingSeconds: "Thought for %d s"
        case .tokens: "%@ tokens"
        case .elapsedSeconds: "%d s"
        case .elapsedMinutes: "%d min"
        case .activityThinking: "Thought for %d s"
        case .activityCommands: "Ran %d commands"
        case .activityReads: "Read %d files"
        case .activityEdits: "%d edits"
        case .activitySearches: "Searched %d times"
        case .activityFetches: "Fetched %d items"
        case .activityTools: "Used %d tools"
        case .activityMore: "+%d"
        case .runningNow: "Running"
        case .loadImage: "Load image"
        case .imageBlocked: "Only HTTPS images can be loaded"
        case .imageFailed: "Couldn't load the image"
        case .unconfirmed: "Status not confirmed yet"
        case .formula: "Formula"
        case .diagram: "Diagram"
        case .workspaceUnknown: "Workspace"
        case .added: "+%d"
        case .deleted: "−%d"
        case .injection: "Context · %@"
        case .goal: "Goal · %@"
        case .modelChanged: "Model changed"
        case .compaction: "Compacted the conversation"
        case .produced: "Produced files"
        case .approval: "Approval"
        case .todo: "Plan %d/%d"
        case .loadFailed: "Couldn't load this conversation"
        case .empty: "No messages yet"
        case .untitled: "Untitled session"
        case .reasoning: "Thinking"
        case .result: "Result"
        case .process: "Steps"
        case .expand: "Show steps"
        case .collapse: "Hide steps"
        case .diffBadge: "%d added, %d removed"
        case .statusConnecting: "Connecting…"
        case .statusReconnecting: "Connection lost · Reconnecting…"
        case .statusFailed: "Connection unavailable"
        case .statusPending: "%d pending requests"
        case .statusGoal: "Goal"
        case .statusRounds: "Round %d/%d"
        case .statusRound: "Round %d"
        case .statusPlan: "Plan %d/%d"
        case .statusPreview: "Preview detected · %@"
        case .statusExpand: "Show goal and plan"
        case .statusCollapse: "Hide goal and plan"
        case .statusDone: "Completed"
        case .statusNotDone: "Not completed"
        case .statusActive: "In progress"
        case .statusPaused: "Paused"
        case .statusBlocked: "Blocked"
        case .statusUnknown: "Status unknown"
        case .statusDemoObjective: "Keep approval status in sync"
        case .statusDemoFind: "Find the event subscribers"
        case .statusDemoBroadcast: "Broadcast the decision"
        case .statusDemoExpiry: "Add expiry regression tests"
        case .statusDemoVerify: "Verify both clients"
        case .statusDemoTitle: "Approval sync"
        case .statusDemoUser: "Keep approval status in sync."
        case .statusDemoAssistant: "I'll check the event subscribers first."
        case .send: "Send"
        case .reject: "Reject"
        case .allowOnce: "Allow once"
        case .waitApproval: "Waiting for approval"
        case .waitAnswer: "Waiting for an answer"
        case .question: "Question"
        }
    }
}

struct ConversationCopy {
    var locale: Locale

    func text(_ key: ChatText) -> String {
        L10n.string("chat.\(key.rawValue)", fallback: key.fallback, locale: locale)
    }

    func format(_ key: ChatText, _ arguments: CVarArg...) -> String {
        String(format: text(key), locale: locale, arguments: arguments)
    }

    func subtitle(workspace: String, phase: ConversationPhase) -> String {
        let place = workspace.isEmpty ? text(.workspaceUnknown) : workspace
        switch phase {
        case .running(let step):
            if let step {
                return "\(place) · \(text(.running)) · \(format(.step, step))"
            }
            return "\(place) · \(text(.running))"
        case .completed:
            return "\(place) · \(text(.completed))"
        case .failed:
            return "\(place) · \(text(.failed))"
        case .stopped:
            return "\(place) · \(text(.stopped))"
        case .awaiting:
            return "\(place) · \(text(.awaiting))"
        }
    }

    func goalPhase(_ phase: GoalPhase) -> String {
        switch phase {
        case .active: text(.statusActive)
        case .paused: text(.statusPaused)
        case .blocked: text(.statusBlocked)
        case .complete: text(.statusDone)
        case .unknown: text(.statusUnknown)
        }
    }

    func activity(_ summary: ActivitySummary) -> String {
        if summary.parts.isEmpty { return text(.process) }
        var words = summary.parts.map { part -> String in
            switch part {
            case .thinking(let seconds): format(.activityThinking, seconds)
            case .commands(let count): format(.activityCommands, count)
            case .reads(let count): format(.activityReads, count)
            case .edits(let count): format(.activityEdits, count)
            case .searches(let count): format(.activitySearches, count)
            case .fetches(let count): format(.activityFetches, count)
            case .tools(let count): format(.activityTools, count)
            }
        }
        if summary.more > 0, var last = words.last {
            last += " " + format(.activityMore, summary.more)
            words[words.count - 1] = last
        }
        return words.joined(separator: " · ")
    }

    func meta(_ meta: TurnMeta) -> String {
        var parts: [String] = []
        if let model = meta.model, !model.isEmpty { parts.append(model) }
        if let seconds = meta.thinkingSeconds { parts.append(format(.thinkingSeconds, seconds)) }
        if let tokens = meta.tokens { parts.append(format(.tokens, compact(tokens))) }
        if let elapsed = meta.elapsedSeconds {
            if elapsed >= 60 {
                parts.append(format(.elapsedMinutes, max(1, elapsed / 60)))
            } else {
                parts.append(format(.elapsedSeconds, elapsed))
            }
        }
        return parts.joined(separator: " · ")
    }

    func compact(_ value: Int) -> String {
        let chinese = locale.language.languageCode?.identifier == "zh"
        if value >= 10_000 {
            if chinese {
                return String(format: "%.1f万", locale: locale, Double(value) / 10_000)
            }
            return String(format: "%.1fk", locale: locale, Double(value) / 1_000)
        }
        return value.formatted(.number.locale(locale))
    }
}

enum ConversationPhase: Equatable, Sendable {
    case running(step: Int?)
    case completed
    case failed
    case stopped
    case awaiting
}
