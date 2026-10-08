import DLCore
import Foundation
import Testing

struct LiveActivityTests {
    @Test("关闭或结束时不显示活动")
    func disabledAndEndedStayHidden() {
        let now = Date(timeIntervalSince1970: 0)
        #expect(
            LiveActivityPolicy.content(
                enabled: false, hostRef: "host", sessionRef: "session", phase: .approval, step: 2,
                startedAt: now, waitingCount: 1) == nil)
        #expect(
            LiveActivityPolicy.content(
                enabled: true, hostRef: "host", sessionRef: "session", phase: .ended, step: 2,
                startedAt: now, waitingCount: 0) == nil)
    }

    @Test("活动只保留会话引用、状态和等待数")
    func contentKeepsSafeFields() {
        let now = Date(timeIntervalSince1970: 10)
        let content = LiveActivityPolicy.content(
            enabled: true, hostRef: "host", sessionRef: "session", phase: .question, step: 0,
            startedAt: now, waitingCount: -1)
        #expect(
            content
                == LiveActivityContent(
                    hostRef: "host", sessionRef: "session", phase: .question, step: 1, startedAt: now,
                    waitingCount: 0))
    }

    @Test("审批优先于提问，没有等待时才算运行")
    func phasePrefersApproval() {
        #expect(LiveActivityPolicy.phase(pendingApprovals: 1, pendingQuestions: 2, running: true) == .approval)
        #expect(LiveActivityPolicy.phase(pendingApprovals: 0, pendingQuestions: 1, running: true) == .question)
        #expect(LiveActivityPolicy.phase(pendingApprovals: 0, pendingQuestions: 0, running: true) == .running)
        #expect(LiveActivityPolicy.phase(pendingApprovals: 0, pendingQuestions: 0, running: false) == .ended)
    }
}
