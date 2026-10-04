package dev.deeplinks.core

import dev.deeplinks.core.remote.HostRoute
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 真实 HTTPS 验证 token/GET：不让单纯的状态码映射掩盖认证头丢失。 */
class PairApprovalHttpTest {
    @Test fun `pending approval requests carry device token and never repeat pair POST`() {
        val password = "dlp1-test".toCharArray()
        val keys = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/dlp1-test-server.p12")!!.use { keys.load(it, password) }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
        val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        val certificate = keys.getCertificate(keys.aliases().nextElement())
        val pin = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
        MockWebServer().use { server ->
            server.useHttps(tls.socketFactory)
            server.enqueue(MockResponse.Builder().code(403).body("""{"pending":true}""").build())
            server.enqueue(MockResponse.Builder().code(200).body("""{"sessions":[]}""").build())
            server.start()
            val host = Host("fixture", "https://localhost:${server.port}", "device-token", deviceId = "fixture-${server.port}", certFingerprint = pin)
            val session = PairingSession("request", "attempt", host, HostRoute.LAN, null, null, System.currentTimeMillis())
            assertEquals(PairApproval.Pending, PairClient.approvalStateSealed(session))
            assertEquals(PairApproval.Approved, PairClient.approvalStateSealed(session))
            repeat(2) {
                val request = server.takeRequest()
                assertEquals("GET", request.method)
                assertEquals("/dsh-link/mobile/sessions", request.url.encodedPath)
                assertEquals("device-token", request.headers["x-dsh-link-token"])
            }
            assertTrue(server.requestCount == 2)
        }
    }
}
