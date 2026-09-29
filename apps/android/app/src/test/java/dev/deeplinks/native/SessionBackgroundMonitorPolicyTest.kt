package dev.deeplinks.native

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBackgroundMonitorPolicyTest {
    @Test
    fun approvalActionsOnlyAppearForMatchingPendingRequest() {
        val snapshot = SessionRequestSnapshot(
            approvals = listOf(
                SessionRequestState(id = "pending", kind = "approval", status = REQUEST_PENDING),
                SessionRequestState(id = "resolved", kind = "approval", status = REQUEST_RESOLVED),
            ),
        )

        assertTrue(isPendingApprovalActionable(snapshot, "pending"))
        assertFalse(isPendingApprovalActionable(snapshot, "resolved"))
        assertFalse(isPendingApprovalActionable(snapshot, "other"))
    }

    @Test
    fun waitingForApprovalKeepsMonitorAliveAfterTurnEnds() {
        val session = MobileSession(
            sessionId = "s-1",
            title = "Approval",
            updatedAt = 0,
            running = false,
            blank = false,
            cwd = null,
            agentPreset = null,
            awaitingInput = true,
        )

        assertFalse(isSessionMonitorFinished(session))
        assertTrue(isSessionMonitorFinished(session.copy(awaitingInput = false)))
    }

    @Test
    fun backgroundMonitorRequiresExplicitOptIn() {
        // 默认关闭：无论会话在跑还是在等审批，都不启动后台接管
        assertFalse(shouldMonitorSession(backgroundTakeover = false, running = true, awaitingInput = false))
        assertFalse(shouldMonitorSession(backgroundTakeover = false, running = false, awaitingInput = true))
        // 打开后只跟进仍在运行或在等你处理的会话
        assertTrue(shouldMonitorSession(backgroundTakeover = true, running = true, awaitingInput = false))
        assertTrue(shouldMonitorSession(backgroundTakeover = true, running = false, awaitingInput = true))
        assertFalse(shouldMonitorSession(backgroundTakeover = true, running = false, awaitingInput = false))
    }
}
