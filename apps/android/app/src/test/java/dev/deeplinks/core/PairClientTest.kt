package dev.deeplinks.core

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairClientTest {

    @Test
    fun `pair request carries idempotency key on both routes`() {
        val lan = PairClient.pairRequestBody("123456", "手机", "lan", "request-1")
        val remote = PairClient.pairRequestBody("123456", "手机", "remote", "request-1")
        assertEquals("request-1", lan.getString("requestId"))
        assertEquals(lan.getString("requestId"), remote.getString("requestId"))
    }

    @Test
    fun `pair success carries the device remote route`() {
        val r = PairClient.parsePairSuccess(
            "https://10.0.0.2:18640",
            """{"ok":true,"token":"tok","deviceId":"dev-1","pending":true,"remote":{"e":"wss://relay.example/ws","r":"AAAAAAAAAAAAAAAAAAAAAA","h":"AQEBAQEBAQEBAQEBAQEBAQ","k":"${"A".repeat(43)}"}}""",
            "手机",
            "ab",
        )
        assertTrue(r.pending)
        assertEquals("device", r.remote?.kind)
        assertEquals("wss://relay.example/ws", r.remote?.endpoint)
        // 旧插件没有 remote：照常成功，只是没有远程能力
        val old = PairClient.parsePairSuccess("https://10.0.0.2:18640", """{"ok":true,"token":"tok"}""", "手机", "ab")
        assertEquals(null, old.remote)
    }

    @Test
    fun `only connect-phase failures fall back to remote pairing`() {
        assertTrue(PairClient.canFallBackToRemote(java.net.ConnectException("refused")))
        assertFalse(PairClient.canFallBackToRemote(PinnedSsl.CertChangedException()))
        // 插件的明确答复（码错、同名）不是传输故障
        assertFalse(PairClient.canFallBackToRemote(Exception("配对码错误")))
    }

    @Test
    fun `first reachable returns the fastest working url or null`() {
        val urls = listOf("https://a", "https://b", "https://c")
        val hit = PairClient.firstReachable(urls, 2_000) { url ->
            if (url == "https://a") Thread.sleep(300)
            url != "https://c"
        }
        assertEquals("https://b", hit)
        assertEquals(null, PairClient.firstReachable(urls, 2_000) { false })
        assertEquals(null, PairClient.firstReachable(emptyList(), 2_000) { true })
    }

    @Test
    fun `pair success without pending is active`() {
        val r = PairClient.parsePairSuccess(
            "https://10.0.0.2:18640",
            """{"ok":true,"token":"tok","deviceId":"dev-1","name":"书房"}""",
            "手机",
            "ab",
        )
        assertEquals("tok", r.token)
        assertEquals("dev-1", r.deviceId)
        assertEquals("书房", r.name)
        assertEquals("ab", r.certFingerprint)
        assertFalse(r.pending)
    }

    @Test
    fun `pair success pending waits for host approval`() {
        val r = PairClient.parsePairSuccess(
            "https://10.0.0.2:18640",
            """{"ok":true,"token":"tok","deviceId":"dev-1","pending":true}""",
            "手机",
            null,
        )
        assertTrue(r.pending)
        assertEquals("手机", r.name)
        assertEquals("", r.certFingerprint)
    }

    @Test
    fun `health error classification never trusts relay reject codes`() {
        // 中继可能伪造拒绝码：只算连不上，不当凭据失效（RFC §7.4）
        assertTrue(classifyHostHealthError(dev.deeplinks.core.remote.RouteRejectedException("UNKNOWN_KEY")) is HostHealth.Unreachable)
        assertTrue(classifyHostHealthError(IOException("REVOKED revoked")) is HostHealth.Unreachable)
        assertTrue(classifyHostHealthError(IOException("RATE_LIMITED daily budget")) is HostHealth.Unreachable)
        assertTrue(classifyHostHealthError(IOException("connection refused")) is HostHealth.Unreachable)
        assertTrue(classifyHostHealthError(PinnedSsl.CertChangedException()) is HostHealth.AuthFailed)
    }

    @Test
    fun `pair http errors follow app language`() {
        assertEquals(DshStringsZh.pairCodeInvalid, PairClient.friendlyPairError(401, ""))
        assertEquals(DshStringsZh.pairNameTaken, PairClient.friendlyPairError(409, "{}"))
        assertEquals(DshStringsZh.pairBadRequest, PairClient.friendlyPairError(415, """{"error":"ignored"}"""))
        assertEquals(DshStringsZh.pairTooManyAttempts, PairClient.friendlyPairError(429, ""))
        assertEquals(DshStringsZh.pairHostUnavailable.format(503), PairClient.friendlyPairError(503, ""))
        assertEquals(DshStringsZh.pairFailedHttp.format(418), PairClient.friendlyPairError(418, ""))
        assertEquals("bad pin", PairClient.friendlyPairError(401, """{"error":"bad pin"}"""))
    }
}
