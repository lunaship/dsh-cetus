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
    public var title: String
    public var phase: LiveActivityPhase
    public var step: Int
    public var startedAt: Date
    public var waitingCount: Int

    public init(
        hostRef: String, sessionRef: String, title: String, phase: LiveActivityPhase, step: Int, startedAt: Date,
        waitingCount: Int
    ) {
        self.hostRef = hostRef
        self.sessionRef = sessionRef
        self.title = title
        self.phase = phase
        self.step = max(1, step)
        self.startedAt = startedAt
        self.waitingCount = max(0, waitingCount)
    }
}

public enum LiveActivityPolicy {
    public static func content(
        enabled: Bool, hostRef: String, sessionRef: String, title: String, phase: LiveActivityPhase, step: Int,
        startedAt: Date, waitingCount: Int
    ) -> LiveActivityContent? {
        guard enabled, phase != .ended, !hostRef.isEmpty, !sessionRef.isEmpty else { return nil }
        let cleanTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        return LiveActivityContent(
            hostRef: hostRef, sessionRef: sessionRef, title: cleanTitle.isEmpty ? sessionRef : cleanTitle, phase: phase,
            step: step, startedAt: startedAt, waitingCount: waitingCount)
    }
}
