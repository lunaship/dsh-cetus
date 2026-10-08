import DLCore
import DLModels
import Foundation
import Testing

@testable import Cetus

@Suite("Inbox Live Activity target")
struct InboxLiveActivityTests {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func millis(_ secondsAgo: TimeInterval) -> Int {
        Int((now.timeIntervalSince1970 - secondsAgo) * 1000)
    }

    private func running(_ id: String, startedSecondsAgo: TimeInterval?, updatedAt: Int = 0) -> SessionSummary {
        var session = SessionSummary(sessionId: id)
        session.running = true
        session.updatedAt = updatedAt
        if let ago = startedSecondsAgo {
            session.activity = SessionActivity(kind: nil, label: nil, step: 3, startedAt: millis(ago))
        }
        return session
    }

    @Test("短任务不创建活动")
    func shortTaskIsIgnored() {
        #expect(inboxLiveActivityTarget([running("a", startedSecondsAgo: 10)], now: now) == nil)
    }

    @Test("缺少开始时间时不猜测为长任务")
    func missingStartIsNotLong() {
        #expect(inboxLiveActivityTarget([running("a", startedSecondsAgo: nil)], now: now) == nil)
    }

    @Test("长任务显示为运行中并沿用真实开始时间")
    func longTaskRuns() throws {
        let target = try #require(inboxLiveActivityTarget([running("a", startedSecondsAgo: 120)], now: now))
        #expect(target.sessionID == "a")
        #expect(target.phase == .running)
        #expect(target.step == 3)
        #expect(target.startedAt == Date(timeIntervalSince1970: now.timeIntervalSince1970 - 120))
    }

    @Test("等待处理优先于运行中，并计入等待数量")
    func waitingWins() throws {
        var waitingA = SessionSummary(sessionId: "w1")
        waitingA.awaitingInput = true
        waitingA.updatedAt = 5
        var waitingB = SessionSummary(sessionId: "w2")
        waitingB.awaitingInput = true
        waitingB.updatedAt = 9
        let target = try #require(
            inboxLiveActivityTarget(
                [running("r", startedSecondsAgo: 600, updatedAt: 99), waitingA, waitingB], now: now))
        #expect(target.sessionID == "w2")
        #expect(target.phase == .approval)
        #expect(target.waitingCount == 2)
    }

    @Test("已结束的会话不显示")
    func stoppedIsIgnored() {
        var done = SessionSummary(sessionId: "d")
        done.running = false
        done.stoppedReason = "failed"
        #expect(inboxLiveActivityTarget([done], now: now) == nil)
    }
}
