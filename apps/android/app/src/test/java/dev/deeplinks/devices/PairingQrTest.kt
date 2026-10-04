package dev.deeplinks.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class PairingQrTest {

    @Test
    fun `valid payload parses`() {
        val text = """{"type":"dsh-link","pairingCode":"123456","urls":["https://10.0.0.2:18640"],"name":"书房","certFingerprint":"ab"}"""
        val parsed = parsePairingQr(text)
        assertTrue(parsed is PairingQrResult.Ok)
        val qr = (parsed as PairingQrResult.Ok).qr
        assertEquals("123456", qr.code)
        assertEquals(listOf("https://10.0.0.2:18640"), qr.urls)
        assertEquals("书房", qr.name)
        assertEquals("ab", qr.certFingerprint)
    }

    @Test
    fun `non dsh json is not dsh`() {
        assertTrue(parsePairingQr("""{"type":"other"}""") is PairingQrResult.NotDsh)
        assertTrue(parsePairingQr("not-json") is PairingQrResult.NotDsh)
    }

    @Test
    fun `malformed urls do not crash`() {
        assertTrue(parsePairingQr("""{"type":"dsh-link","pairingCode":"1","urls":[1,2]}""") is PairingQrResult.Invalid)
        assertTrue(parsePairingQr("""{"type":"dsh-link","pairingCode":"1","urls":[{"x":1}]}""") is PairingQrResult.Invalid)
        assertTrue(parsePairingQr("""{"type":"dsh-link","pairingCode":"1","urls":[null]}""") is PairingQrResult.Invalid)
        assertTrue(parsePairingQr("""{"type":"dsh-link","pairingCode":"1"}""") is PairingQrResult.Invalid)
        assertTrue(parsePairingQr("""{"type":"dsh-link","pairingCode":"","urls":["https://x"]}""") is PairingQrResult.Invalid)
    }

    private val remote = """"remote":{"e":"wss://relay.example/ws","r":"AAAAAAAAAAAAAAAAAAAAAA","s":"AQEBAQEBAQEBAQEBAQEBAQ"}"""

    @Test
    fun `unified code carries lan urls and a bootstrap remote route`() {
        val text = """{"type":"dsh-link","pairingCode":"123456","urls":["https://10.0.0.2:18640"],"name":"书房","certFingerprint":"ab",$remote}"""
        val qr = (parsePairingQr(text) as PairingQrResult.Ok).qr
        assertEquals(listOf("https://10.0.0.2:18640"), qr.urls)
        assertEquals("bootstrap", qr.remote?.kind)
        assertEquals("wss://relay.example/ws", qr.remote?.endpoint)
    }

    @Test
    fun `remote-only code needs the inner pin`() {
        val withPin = """{"type":"dsh-link","pairingCode":"123456","certFingerprint":"ab",$remote}"""
        assertTrue(parsePairingQr(withPin) is PairingQrResult.Ok)
        val noPin = """{"type":"dsh-link","pairingCode":"123456",$remote}"""
        assertTrue(parsePairingQr(noPin) is PairingQrResult.Invalid)
    }

    @Test
    fun `broken remote only drops remote pairing`() {
        val text = """{"type":"dsh-link","pairingCode":"1","urls":["https://10.0.0.2:18640"],"certFingerprint":"ab","remote":{"e":"ws://insecure/ws","r":"x","s":"y"}}"""
        val qr = (parsePairingQr(text) as PairingQrResult.Ok).qr
        assertEquals(null, qr.remote)
    }

    @Test
    fun `past QR does not reject based on untrusted phone clock`() {
        val past = System.currentTimeMillis() - 1000
        val text = """{"type":"dsh-link","pairingCode":"123456","urls":["https://10.0.0.2:18640"],"certFingerprint":"ab","issuedAt":1735743600000,"expiresAt":""" + past + """}"""
        val result = parsePairingQr(text)
        assertTrue(result is PairingQrResult.Ok)
    }

    @Test
    fun `qr with future expiresAt is ok`() {
        val future = System.currentTimeMillis() + 60_000
        val text = """{"type":"dsh-link","pairingCode":"123456","urls":["https://10.0.0.2:18640"],"certFingerprint":"ab","issuedAt":1735743600000,"expiresAt":""" + future + """}"""
        val result = parsePairingQr(text)
        assertTrue(result is PairingQrResult.Ok)
        val qr = (result as PairingQrResult.Ok).qr
        assertEquals(1735743600000L, qr.issuedAt)
        assertEquals(future, qr.expiresAt)
    }

    @Test
    fun `legacy qr without expiry is ok`() {
        val text = """{"type":"dsh-link","pairingCode":"123456","urls":["https://10.0.0.2:18640"],"certFingerprint":"ab"}"""
        val result = parsePairingQr(text)
        assertTrue(result is PairingQrResult.Ok)
        val qr = (result as PairingQrResult.Ok).qr
        assertNull(qr.issuedAt)
        assertNull(qr.expiresAt)
    }

    @Test
    fun `timestamp fields require positive integers and ordered lifetime`() {
        val base = """{"type":"dsh-link","code":"1","urls":["https://10.0.0.2:18640"]"""
        for (fields in listOf("\"expiresAt\":\"123\"", "\"issuedAt\":1.5", "\"issuedAt\":200,\"expiresAt\":100")) {
            assertTrue(parsePairingQr("$base,$fields}") is PairingQrResult.Invalid)
        }
    }

    @Test
    fun `missing malformed and legacy remote stay distinguishable`() {
        val base = """{"type":"dsh-link","code":"1","urls":["https://10.0.0.2:18640"]"""
        fun capability(extra: String) = (parsePairingQr("$base$extra}") as PairingQrResult.Ok).qr.remoteCapability
        assertEquals(QrRemoteCapability.ABSENT, capability(""))
        assertEquals(QrRemoteCapability.INVALID, capability(",\"remote\":{}"))
        assertEquals(QrRemoteCapability.LEGACY, capability(",\"relay\":{}"))
    }
}
