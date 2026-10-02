package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v4 4.3 / 4.4：哪一条审批 / 提问进底部决策栏。 */
class DecisionBarsTest {

    private fun approval(id: String, status: String? = REQUEST_PENDING, phone: Boolean = true) =
        MobileMessage(id = id, role = "approval", text = "", approvalId = "a-$id", requestStatus = status, takenOverByPhone = phone)

    private fun question(id: String, status: String? = REQUEST_PENDING, payload: String = """[{"id":"x","question":"?","options":["a"]}]""") =
        MobileMessage(id = id, role = "question", text = "", questionRpcId = "r-$id", questionPayloadJson = payload, requestStatus = status)

    private fun user(id: String) = MobileMessage(id = id, role = "user", text = "hi")

    @Test
    fun picksLatestActionable() {
        assertEquals("q2", pendingDecision(listOf(approval("a1"), question("q2")))?.id)
        assertEquals("a3", pendingDecision(listOf(question("q2"), approval("a3")))?.id)
    }

    @Test
    fun skipsApprovalsThePhoneCannotAnswer() {
        assertNull(pendingDecision(listOf(approval("a1", phone = false))))
        assertNull(pendingDecision(listOf(approval("a1", status = REQUEST_RESOLVED))))
        assertNull(pendingDecision(listOf(approval("a1", status = REQUEST_UNKNOWN))))
    }

    @Test
    fun skipsAnsweredStaleAndUnsupportedQuestions() {
        assertNull(pendingDecision(listOf(question("q1", status = REQUEST_RESOLVED))))
        assertNull(pendingDecision(listOf(question("q1"), user("u2"))))
        assertNull(pendingDecision(listOf(question("q1", payload = """[{"id":"x","type":"file","question":"?"}]"""))))
    }

    @Test
    fun olderApprovalStillShowsAfterNewerIsResolved() {
        val msgs = listOf(approval("a1"), approval("a2", status = REQUEST_RESOLVED))
        assertEquals("a1", pendingDecision(msgs)?.id)
    }

    @Test
    fun commandComesFromToolArgs() {
        assertEquals("rm -rf build/", approvalCommand("""{"command":"rm -rf build/"}"""))
        assertNull(approvalCommand("""{"path":"a.kt"}"""))
        assertNull(approvalCommand("not json"))
        assertNull(approvalCommand(null))
    }
}
