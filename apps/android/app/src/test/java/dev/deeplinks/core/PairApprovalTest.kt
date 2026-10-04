package dev.deeplinks.core

import dev.deeplinks.devices.isNetworkPairFailure
import dev.deeplinks.core.RetryProblem
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v4 1.5 / 1.6：等批准轮询的判定与失败页「网络问题」分类。 */
class PairApprovalTest {

    @Test
    fun `approval state follows plugin auth responses`() {
        assertEquals(PairApproval.Approved, PairClient.approvalFromResponseSealed(200, """{"sessions":[]}"""))
        assertEquals(PairApproval.Pending, PairClient.approvalFromResponseSealed(403, """{"error":"pending","pending":true}"""))
        assertEquals(PairApproval.Retryable(RetryProblem.UNKNOWN), PairClient.approvalFromResponseSealed(403, """{"error":"forbidden"}"""))
        assertEquals(PairApproval.Retryable(RetryProblem.UNKNOWN), PairClient.approvalFromResponseSealed(403, "not json"))
        assertEquals(PairApproval.RejectedByHost, PairClient.approvalFromResponseSealed(401, null))
        assertEquals(PairApproval.Retryable(RetryProblem.UNKNOWN), PairClient.approvalFromResponseSealed(502, ""))
    }

    @Test
    fun `only transport failures count as network problems`() {
        assertTrue(isNetworkPairFailure(IOException("all addresses failed")))
        assertTrue(isNetworkPairFailure(SocketTimeoutException()))
        assertFalse(isNetworkPairFailure(IllegalStateException("配对码已过期")))
    }
}
