import DLCore
import DLModels
import Foundation

/// What the inbox wants the lock screen to show (RFC 0002 §5.6, plan §16.4).
///
/// Only one session is shown: one waiting for the user wins over a running one,
/// and a running one must have been going for `longTaskThreshold` so short tasks
/// do not flash an activity on and off.
struct InboxLiveActivityTarget: Equatable, Sendable {
    var sessionID: String
    var phase: LiveActivityPhase
    var step: Int
    var startedAt: Date
    var waitingCount: Int
}

let inboxLongTaskThreshold: TimeInterval = 60

func inboxLiveActivityTarget(_ sessions: [SessionSummary], now: Date) -> InboxLiveActivityTarget? {
    let waiting = sessions.filter { $0.awaitingInput == true && $0.sessionId?.isEmpty == false }
    if let session = waiting.max(by: { ($0.updatedAt ?? 0) < ($1.updatedAt ?? 0) }),
        let sessionID = session.sessionId
    {
        return InboxLiveActivityTarget(
            sessionID: sessionID, phase: .approval, step: session.activity?.step ?? 1,
            startedAt: inboxActivityStart(session, now: now), waitingCount: waiting.count)
    }
    let running = sessions.filter { session in
        guard session.running == true, session.sessionId?.isEmpty == false else { return false }
        return now.timeIntervalSince(inboxActivityStart(session, now: now)) >= inboxLongTaskThreshold
    }
    guard let session = running.max(by: { ($0.updatedAt ?? 0) < ($1.updatedAt ?? 0) }),
        let sessionID = session.sessionId
    else { return nil }
    return InboxLiveActivityTarget(
        sessionID: sessionID, phase: .running, step: session.activity?.step ?? 1,
        startedAt: inboxActivityStart(session, now: now), waitingCount: 0)
}

/// `activity.startedAt` is epoch milliseconds (MOBILE_SYNC_CONTRACT). Without it
/// the task is treated as just started, so it never crosses the threshold on a
/// guess.
private func inboxActivityStart(_ session: SessionSummary, now: Date) -> Date {
    guard let millis = session.activity?.startedAt, millis > 0 else { return now }
    return Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
}

/// Drives the lock-screen activity from inbox state. Ends the previous session's
/// activity when the target moves, and everything when nothing qualifies.
@MainActor
final class InboxLiveActivitySync {
    private let controller: LiveActivityController
    private var current: String?

    init(controller: LiveActivityController) {
        self.controller = controller
    }

    func restore() async {
        let handles = await controller.restore()
        current = handles.first?.sessionRef
    }

    func sync(hostID: String, sessions: [SessionSummary], now: Date) async {
        guard let target = inboxLiveActivityTarget(sessions, now: now) else {
            current = nil
            await controller.endAll()
            return
        }
        if let previous = current, previous != target.sessionID {
            await controller.end(sessionRef: previous)
        }
        current = target.sessionID
        await controller.update(
            hostRef: hostID, sessionRef: target.sessionID, phase: target.phase, step: target.step,
            startedAt: target.startedAt, waitingCount: target.waitingCount)
    }
}
