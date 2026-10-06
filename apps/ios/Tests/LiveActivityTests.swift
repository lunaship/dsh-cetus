import DLCore
import Foundation
import Testing

struct LiveActivityTests {
    @Test("关闭或结束时不显示活动")
    func disabledAndEndedStayHidden() {
        let now = Date(timeIntervalSince1970: 0)
        #expect(
            LiveActivityPolicy.content(
                enabled: false, hostRef: "host", sessionRef: "session", title: "审批", phase: .approval, step: 2,
                startedAt: now, waitingCount: 1) == nil)
        #expect(
            LiveActivityPolicy.content(
                enabled: true, hostRef: "host", sessionRef: "session", title: "审批", phase: .ended, step: 2,
                startedAt: now, waitingCount: 0) == nil)
    }

    @Test("活动只保留会话引用、状态和等待数")
    func contentKeepsSafeFields() {
        let now = Date(timeIntervalSince1970: 10)
        let content = LiveActivityPolicy.content(
            enabled: true, hostRef: "host", sessionRef: "session", title: "   ", phase: .question, step: 0,
            startedAt: now, waitingCount: -1)
        #expect(
            content
                == LiveActivityContent(
                    hostRef: "host", sessionRef: "session", title: "session", phase: .question, step: 1, startedAt: now,
                    waitingCount: 0))
    }
}
