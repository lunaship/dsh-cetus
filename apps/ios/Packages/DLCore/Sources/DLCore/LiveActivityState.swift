import Foundation

public enum LiveActivityPhase: String, Codable, Equatable, Sendable {
    case running
    case approval
    case question
    case ended
}

public struct LiveActivityContent: Equatable, Sendable {
    public var hostRef: String
    public var sessionRef: String
    public var phase: LiveActivityPhase
    public var step: Int
    public var startedAt: Date
    public var waitingCount: Int
    /// Task title the app looked up locally for `sessionRef` (user decision 2026-10-10:
    /// the lock screen and Dynamic Island show it). It travels only in the locally
    /// requested activity's attributes, never in `content-state` or any push payload
    /// (RFC 0002 §5.6 is unchanged). `nil` falls back to the generic status copy.
    public var title: String?

    public init(
        hostRef: String, sessionRef: String, phase: LiveActivityPhase, step: Int, startedAt: Date,
        waitingCount: Int, title: String? = nil
    ) {
        self.hostRef = hostRef
        self.sessionRef = sessionRef
        self.phase = phase
        self.step = max(1, step)
        self.startedAt = startedAt
        self.waitingCount = max(0, waitingCount)
        self.title = LiveActivityTitle.clean(title)
    }

    /// Generic status line. Shown under the title, or alone when there is no title.
    /// Never a command or a file name.
    public var lockScreenStatus: String {
        switch phase {
        case .approval: return "有一项任务需要处理"
        case .question: return "有一项任务需要处理"
        case .running: return "任务进行中"
        case .ended: return "任务已结束"
        }
    }
}

/// Normalises a session title for the lock screen: one line, no surrounding
/// whitespace, bounded length (ActivityKit caps attributes + state at 4 KB; the
/// widget still truncates to one line with an ellipsis).
public enum LiveActivityTitle {
    public static let maxCharacters = 80

    public static func clean(_ title: String?) -> String? {
        guard let title else { return nil }
        let oneLine = title.components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        guard !oneLine.isEmpty else { return nil }
        guard oneLine.count > maxCharacters else { return oneLine }
        return String(oneLine.prefix(maxCharacters - 1)) + "…"
    }
}

public enum LiveActivityPolicy {
    public static func content(
        enabled: Bool, hostRef: String, sessionRef: String, phase: LiveActivityPhase, step: Int,
        startedAt: Date, waitingCount: Int, title: String? = nil
    ) -> LiveActivityContent? {
        guard enabled, phase != .ended, !hostRef.isEmpty, !sessionRef.isEmpty else { return nil }
        return LiveActivityContent(
            hostRef: hostRef, sessionRef: sessionRef, phase: phase,
            step: step, startedAt: startedAt, waitingCount: waitingCount, title: title)
    }

    /// Approval wins over a question. Ordinary pending work stays running.
    public static func phase(pendingApprovals: Int, pendingQuestions: Int, running: Bool) -> LiveActivityPhase {
        if pendingApprovals > 0 { return .approval }
        if pendingQuestions > 0 { return .question }
        return running ? .running : .ended
    }
}
