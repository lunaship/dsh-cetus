package dev.deeplinks.core

import dev.deeplinks.devices.hostFromPair
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairClientTest {

    @Test
    fun `qr tailnet address is stored beside the primary`() {
        assertEquals(
            "https://100.64.0.8:18640",
            PairClient.tailnetSpare(
                listOf("https://192.168.1.10:18640", "https://100.64.0.8:18640"),
                "https://192.168.1.10:18640",
            ),
        )
        assertEquals(
            "",
            PairClient.tailnetSpare(
                listOf("https://100.64.0.8:18640", "https://192.168.1.10:18640"),
                "https://100.64.0.8:18640/",
            ),
        )
        assertEquals("", PairClient.tailnetSpare(listOf("https://10.0.0.2:18640"), "https://10.0.0.2:18640"))
        assertTrue(isTailnetUrl("https://100.127.1.1:18640"))
        assertTrue(isTailnetUrl("https://[fd7a:115c:a1e0::8]:18640"))
        assertFalse(isTailnetUrl("https://100.63.1.1:18640"))
        assertFalse(isTailnetUrl("https://8.8.8.8:18640"))
        val stored = hostFromPair(
            "书房",
            PairClient.Result(
                baseUrl = "https://192.168.1.10:18640",
                name = "书房",
                token = "tok",
                tailnetUrl = "https://100.64.0.8:18640",
            ),
        )
        assertEquals("https://192.168.1.10:18640", stored.baseUrl)
        assertEquals("https://100.64.0.8:18640", stored.tailnetUrl)
    }

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
    fun `pair success carries route and expiry timestamps`() {
        val r = PairClient.parsePairSuccess(
            "https://10.0.0.2:18640",
            """{"ok":true,"token":"tok","deviceId":"dev-1","pending":true,"pendingExpiresAt":1735747200000,"serverNow":1735743600000,"remote":{"e":"wss://relay.example/ws","r":"AAAAAAAAAAAAAAAAAAAAAA","h":"AQEBAQEBAQEBAQEBAQEBAQ","k":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}}""",
            "手机",
            "ab",
            dev.deeplinks.core.remote.HostRoute.REMOTE,
        )
        assertTrue(r.pending)
        assertEquals(dev.deeplinks.core.remote.HostRoute.REMOTE, r.pairRoute)
        assertEquals(1735747200000L, r.pendingExpiresAt)
        assertEquals(1735743600000L, r.serverNow)
    }

    @Test
    fun `pair http errors preserve reason and recovery without server text`() {
        assertEquals(PairFailureCode.PAIR_CODE_INVALID, PairClient.pairFailureFromHttp(401, "").code)
        assertEquals(PairFailureCode.SAME_NAME, PairClient.pairFailureFromHttp(409, "").code)
        assertEquals(PairRecovery.RESCAN, PairClient.pairFailureFromHttp(401, "").recovery)
        assertEquals(DshStringsZh.pairCodeInvalid, PairClient.friendlyPairError(401, """{"error":"secret token"}"""))
    }

    @Test
    fun `pair http errors follow app language`() {
        assertEquals(DshStringsZh.pairCodeInvalid, PairClient.friendlyPairError(401, ""))
        assertEquals(DshStringsZh.pairNameTaken, PairClient.friendlyPairError(409, "{}"))
        assertEquals(DshStringsZh.remoteCredentialInvalid, PairClient.friendlyPairError(415, """{"error":"ignored"}"""))
        assertEquals(DshStringsZh.pairTooManyAttempts, PairClient.friendlyPairError(429, ""))
        assertEquals(DshStringsZh.pairHostUnavailable.format(503), PairClient.friendlyPairError(503, ""))
        assertEquals(DshStringsZh.pairFailedHttp.format(418), PairClient.friendlyPairError(418, ""))
        assertEquals(DshStringsZh.pairCodeInvalid, PairClient.friendlyPairError(401, """{"error":"bad pin"}"""))
    }
}
