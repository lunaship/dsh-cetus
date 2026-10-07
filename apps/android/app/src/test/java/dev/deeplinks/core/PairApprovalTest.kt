package dev.deeplinks.core

import dev.deeplinks.devices.isNetworkPairFailure
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v4 1.5 / 1.6：等批准轮询的判定与失败页「网络问题」分类。 */
class PairApprovalTest {

    @Test
    fun `approval polling authenticates pending approved and rejected requests`() {
        val token = "test-pair-approval-token"
        val approved = AtomicBoolean(false)
        val revoked = AtomicBoolean(false)
        val keys = TestServerIdentity.keyStore()
        val tls = TestServerIdentity.serverContext(keys)
        val pin = TestServerIdentity.pin(keys)
        val server = MockWebServer()
        server.useHttps(tls.socketFactory)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.headers["x-dsh-link-token"] != token || revoked.get()) {
                    return MockResponse.Builder().code(401).body("""{"error":"invalid token"}""").build()
                }
                return if (approved.get()) {
                    MockResponse.Builder().code(200).body("""{"sessions":[]}""").build()
                } else {
                    MockResponse.Builder().code(403).body("""{"pending":true}""").build()
                }
            }
        }
        server.start()
        val host = Host("approval fixture", server.url("/").toString().trimEnd('/'), token, certFingerprint = pin)
        try {
            assertEquals(PairClient.Approval.Pending, PairClient.approvalState(host))
            approved.set(true)
            assertEquals(PairClient.Approval.Approved, PairClient.approvalState(host))
            revoked.set(true)
            assertEquals(PairClient.Approval.Rejected, PairClient.approvalState(host))
        } finally {
            HostHttp.onNetworkChanged()
            server.close()
        }
    }

    @Test
    fun `approval state follows plugin auth responses`() {
        assertEquals(PairClient.Approval.Approved, PairClient.approvalFromResponse(200, """{"sessions":[]}"""))
        assertEquals(PairClient.Approval.Pending, PairClient.approvalFromResponse(403, """{"error":"pending","pending":true}"""))
        assertEquals(PairClient.Approval.Unknown, PairClient.approvalFromResponse(403, """{"error":"forbidden"}"""))
        assertEquals(PairClient.Approval.Unknown, PairClient.approvalFromResponse(403, "not json"))
        assertEquals(PairClient.Approval.Rejected, PairClient.approvalFromResponse(401, null))
        assertEquals(PairClient.Approval.Unknown, PairClient.approvalFromResponse(502, ""))
    }

    @Test
    fun `only transport failures count as network problems`() {
        assertTrue(isNetworkPairFailure(IOException("all addresses failed")))
        assertTrue(isNetworkPairFailure(SocketTimeoutException()))
        assertFalse(isNetworkPairFailure(IllegalStateException("配对码已过期")))
    }
}
