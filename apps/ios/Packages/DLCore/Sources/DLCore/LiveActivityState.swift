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

    public init(
        hostRef: String, sessionRef: String, phase: LiveActivityPhase, step: Int, startedAt: Date,
        waitingCount: Int
    ) {
        self.hostRef = hostRef
        self.sessionRef = sessionRef
        self.phase = phase
        self.step = max(1, step)
        self.startedAt = startedAt
        self.waitingCount = max(0, waitingCount)
    }

    /// What the widget may put on the lock screen. It never contains a task
    /// title, a command, or a file name: RFC 0002 §5.6 allows only the opaque
    /// `sessionRef`, so the title is looked up inside the app instead.
    public var lockScreenStatus: String {
        switch phase {
        case .approval: return "有一项任务需要处理"
        case .question: return "有一项任务需要处理"
        case .running: return "任务进行中"
        case .ended: return "任务已结束"
        }
    }
}

public enum LiveActivityPolicy {
    public static func content(
        enabled: Bool, hostRef: String, sessionRef: String, phase: LiveActivityPhase, step: Int,
        startedAt: Date, waitingCount: Int
    ) -> LiveActivityContent? {
        guard enabled, phase != .ended, !hostRef.isEmpty, !sessionRef.isEmpty else { return nil }
        return LiveActivityContent(
            hostRef: hostRef, sessionRef: sessionRef, phase: phase,
            step: step, startedAt: startedAt, waitingCount: waitingCount)
    }

    /// Approval wins over a question. Ordinary pending work stays running.
    public static func phase(pendingApprovals: Int, pendingQuestions: Int, running: Bool) -> LiveActivityPhase {
        if pendingApprovals > 0 { return .approval }
        if pendingQuestions > 0 { return .question }
        return running ? .running : .ended
    }
}
