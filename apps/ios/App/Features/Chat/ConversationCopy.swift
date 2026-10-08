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
    case menuView
    case menuActions
    case menuChanges
    case menuFiles
    case menuTrajectory
    case menuAgents
    case menuUsage
    case menuPreview
    case menuGoal
    case menuSchedule
    case menuRename
    case menuFork
    case menuShare
    case trajectoryTitle
    case trajectorySearch
    case trajectoryAll
    case trajectoryUser
    case trajectoryAssistant
    case trajectoryTool
    case trajectoryEmpty
    case trajectoryTurn
    case paletteGroupAgent
    case paletteGroupSession
    case paletteGroupApp
    case palettePlan
    case palettePlanDetail
    case paletteGoal
    case paletteGoalDetail
    case paletteSubagent
    case paletteSubagentDetail
    case paletteSkills
    case paletteSkillsDetail
    case palettePause
    case palettePauseDetail
    case paletteResume
    case paletteResumeDetail
    case paletteClear
    case paletteClearDetail
    case paletteFeedback
    case paletteFeedbackDetail
    case paletteNew
    case paletteNewDetail
    case paletteSearch
    case paletteSearchDetail
    case paletteModel
    case paletteModelDetail
    case palettePermission
    case palettePermissionDetail
    case paletteChat
    case paletteChatDetail
    case paletteTrace
    case paletteTraceDetail
    case paletteSettings
    case paletteSettingsDetail
    case modelTitle
    case modelSection
    case effortSection
    case contextUsed
    case modelScope
    case searchModels
    case noModels
    case permTitle
    case permRead
    case permReadDetail
    case permWrite
    case permWriteDetail
    case permFull
    case permFullDetail
    case permNote
    case permConfirmTitle
    case permConfirmBody
    case permEnable
    case attachTitle
    case attachCamera
    case attachPhotos
    case attachFiles
    case attachUnsupported
    case attachTooLarge
    case attachLimit
    case attachUnreadable
    case attachNoCrop
    case usageTitle
    case usageTokens
    case usageEmpty
    case usageCache
    case usageIo
    case usageTurns
    case usageTime
    case usageContext
    case agentsTitle
    case agentsEmpty
    case agentsDone
    case shareTitle
    case shareImage
    case shareText
    case renameTitle
    case renameField
    case save
    case cancel
    case deleteTitle
    case deleteBody
    case deleteConfirm
    case scheduleTitle
    case scheduleSession
    case scheduleAll
    case scheduleEmpty
    case scheduleDaily
    case scheduleWeekly
    case scheduleEvery
    case scheduleOnce
    case goalTitle
    case goalField
    case goalRounds
    case goalClear
    case questionPrevious
    case questionSkip
    case questionNext
    case questionProgress
    case statusRetry
    case loadOlder
    case loadOlderFailed
    // C04：上翻读历史时，新消息到达的提示入口
    case newMessages
    case backToLatest
    // C06：请求已被其他设备处理的决策面板状态
    case decisionHandledStatus
    case decisionHandledPrimary
    case selectTitle
    case selectText
    // C05：输入栏按状态的 placeholder
    case composerIdlePrompt
    case composerRunningPrompt
    // MARK: - C03 提交状态与失败文案
    /// 发送失败但输入已保留。
    case sendFailedKeepDraft
    /// 结果未知（超时/断连）：服务端可能已接受，不自动重发。
    case sendOutcomeUnknown
    case sendFailedTargetGone
    case sendFailedUnauthorized
    case sendFailedPending
    case sendFailedForbidden
    case sendFailedBusy
    case sendFailedTooLarge
    case sendFailedCertificate
    /// 审批/问题已在别处处理完。
    case decisionAlreadyHandled
    case retry
    case sending
    /// C06：审批 / 回答提交失败，内容保留。
    case decisionFailed
    /// C06 10.2.2：问卷含未知题型，不能提交，也不能静默丢弃。
    case questionUnsupported
    /// C06 10.2.8：服务器校验打回且**能定位到题**（参数是 1 基题号）。
    case questionInvalidIndexed
    /// C06 10.2.8：服务器校验打回但**无法定位到题**，退回通用文案。
    case questionInvalidGeneric
    /// C06 10.1.6：多个待处理请求的位置（参数：当前序号、总数）。
    case decisionPosition
    /// C06：问题自由回答的提示，与普通消息草稿分开。
    case questionAnswerPlaceholder
    /// C02：草稿写盘失败。内存副本还在，但不能让用户以为已保存。
    case draftNotSaved

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
        case .menuView: "View"
        case .menuActions: "Actions"
        case .menuChanges: "Changes"
        case .menuFiles: "Files"
        case .menuTrajectory: "Trajectory"
        case .menuAgents: "Sub-agents"
        case .menuUsage: "Usage"
        case .menuPreview: "Preview"
        case .menuGoal: "Goal"
        case .menuSchedule: "Schedules"
        case .menuRename: "Rename"
        case .menuFork: "Fork"
        case .menuShare: "Share"
        case .trajectoryTitle: "Trajectory"
        case .trajectorySearch: "Search"
        case .trajectoryAll: "All"
        case .trajectoryUser: "You"
        case .trajectoryAssistant: "Assistant"
        case .trajectoryTool: "Tools"
        case .trajectoryEmpty: "Nothing matches"
        case .trajectoryTurn: "Turn %d"
        case .paletteGroupAgent: "Agent"
        case .paletteGroupSession: "Session"
        case .paletteGroupApp: "App"
        case .palettePlan: "Plan"
        case .palettePlanDetail: "Ask for a plan first"
        case .paletteGoal: "Goal"
        case .paletteGoalDetail: "Set a goal for this session"
        case .paletteSubagent: "Sub-agent"
        case .paletteSubagentDetail: "Hand part of the work to another agent"
        case .paletteSkills: "Skills"
        case .paletteSkillsDetail: "List available skills"
        case .palettePause: "Pause"
        case .palettePauseDetail: "Pause this session"
        case .paletteResume: "Resume"
        case .paletteResumeDetail: "Resume this session"
        case .paletteClear: "Clear"
        case .paletteClearDetail: "Clear the conversation"
        case .paletteFeedback: "Feedback"
        case .paletteFeedbackDetail: "Send feedback about this turn"
        case .paletteNew: "New session"
        case .paletteNewDetail: "Start a new task"
        case .paletteSearch: "Search"
        case .paletteSearchDetail: "Search sessions"
        case .paletteModel: "Model"
        case .paletteModelDetail: "Choose a model and effort"
        case .palettePermission: "Permission"
        case .palettePermissionDetail: "Choose what this session can do"
        case .paletteChat: "Chat"
        case .paletteChatDetail: "Back to the conversation"
        case .paletteTrace: "Trajectory"
        case .paletteTraceDetail: "Open the trajectory"
        case .paletteSettings: "Settings"
        case .paletteSettingsDetail: "Open settings"
        case .modelTitle: "Model and effort"
        case .modelSection: "Model"
        case .effortSection: "Effort"
        case .contextUsed: "Context used %d%% · this session only"
        case .modelScope: "This choice only affects this session"
        case .searchModels: "Search models"
        case .noModels: "No models"
        case .permTitle: "Access"
        case .permRead: "Read only"
        case .permReadDetail: "Can read the workspace, not change it"
        case .permWrite: "Workspace write"
        case .permWriteDetail: "Can change files in this workspace"
        case .permFull: "Full access"
        case .permFullDetail: "Can act outside the workspace"
        case .permNote: "This only affects this session"
        case .permConfirmTitle: "Allow full access?"
        case .permConfirmBody: "The agent will be able to act outside this workspace."
        case .permEnable: "Allow"
        case .attachTitle: "Add"
        case .attachCamera: "Camera"
        case .attachPhotos: "Photos"
        case .attachFiles: "Files"
        case .attachUnsupported: "Only PNG, JPEG, WebP, and GIF images can be attached."
        case .attachTooLarge: "That image is too large to attach."
        case .attachLimit: "Only 4 images can be attached."
        case .attachUnreadable: "Couldn't read that file."
        case .attachNoCrop: "Images are sent as chosen. Cropping isn't available."
        case .usageTitle: "Usage"
        case .usageTokens: "tokens"
        case .usageEmpty: "No usage yet"
        case .usageCache: "Cache hit"
        case .usageIo: "In / cache / out"
        case .usageTurns: "Turns / steps"
        case .usageTime: "Model / tools"
        case .usageContext: "Context"
        case .agentsTitle: "Sub-agents"
        case .agentsEmpty: "No sub-agents"
        case .agentsDone: "Done"
        case .shareTitle: "Share"
        case .shareImage: "Share as image"
        case .shareText: "Export as text"
        case .renameTitle: "Rename"
        case .renameField: "Name"
        case .save: "Save"
        case .cancel: "Cancel"
        case .deleteTitle: "Delete this schedule?"
        case .deleteBody: "This schedule will stop. The conversation stays."
        case .deleteConfirm: "Delete"
        case .scheduleTitle: "Schedules"
        case .scheduleSession: "This session"
        case .scheduleAll: "All sessions"
        case .scheduleEmpty: "No schedules"
        case .scheduleDaily: "Every day %@"
        case .scheduleWeekly: "Weekly %@ %@"
        case .scheduleEvery: "Every %@ min"
        case .scheduleOnce: "Once"
        case .goalTitle: "Edit goal"
        case .goalField: "Goal"
        case .goalRounds: "Round limit %d"
        case .goalClear: "Clear goal"
        case .questionPrevious: "Previous"
        case .questionSkip: "Skip"
        case .questionNext: "Next"
        case .questionProgress: "Question %d of %d"
        case .statusRetry: "Retry"
        case .loadOlder: "Load earlier messages"
        case .loadOlderFailed: "Couldn't load earlier messages"
        case .newMessages: "New messages"
        case .backToLatest: "Back to latest"
        case .decisionHandledStatus: "Handled elsewhere"
        case .decisionHandledPrimary: "Dismiss"
        case .selectTitle: "Select text"
        case .selectText: "Select text"
        case .composerIdlePrompt: "Message this conversation"
        case .composerRunningPrompt: "Add more details"
        // C03 提交状态与失败文案
        case .sendFailedKeepDraft:
            "Message not sent. Your text is kept — tap send to retry."
        case .sendOutcomeUnknown:
            "Unknown result — the computer may already have received it. Not resending automatically."
        case .sendFailedTargetGone:
            "This conversation is gone on the computer. Your text is kept — copy it to a new task."
        case .sendFailedUnauthorized: "This phone is no longer authorized. Your text is kept — pair again to send."
        case .sendFailedPending: "Waiting for approval on the computer. Your text is kept — approve, then send again."
        case .sendFailedForbidden: "Not allowed on this computer. Your text is kept."
        case .sendFailedBusy: "The conversation is busy. Your text is kept — try again in a moment."
        case .sendFailedTooLarge: "Too large to send. Remove an attachment or shorten the text, then retry."
        case .sendFailedCertificate: "The computer's certificate changed. Your text is kept — re-pair to continue."
        case .decisionAlreadyHandled: "Already handled elsewhere."
        case .retry: "Retry"
        case .sending: "Sending…"
        case .decisionFailed: "Couldn't submit. Your answer is kept — try again."
        case .questionUnsupported:
            "This question uses a type this app can't answer yet. Submitted nothing — answer it on the computer."
        case .questionInvalidIndexed: "The computer rejected question %d. Fix it and try again."
        case .questionInvalidGeneric: "The computer rejected the answers. Check them and try again."
        case .decisionPosition: "Request %d of %d"
        case .questionAnswerPlaceholder: "Your answer"
        case .draftNotSaved: "Draft not saved — it stays on screen. Check storage and try again."
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
