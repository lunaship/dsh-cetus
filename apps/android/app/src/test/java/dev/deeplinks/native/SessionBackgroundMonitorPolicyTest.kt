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
}
