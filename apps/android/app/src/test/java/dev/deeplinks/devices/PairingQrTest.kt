package dev.deeplinks.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `legacy DLR relay key is ignored`() {
        val text = """{"type":"dsh-link","pairingCode":"123456","urls":["https://10.0.0.2:18640"],"certFingerprint":"ab","relay":{"v":2,"client":"relay.example:8443","routeId":"rid","routeSecret":"sec"}}"""
        val qr = (parsePairingQr(text) as PairingQrResult.Ok).qr
        assertEquals(null, qr.remote)
        // 只有旧 relay、没有局域网地址的码：旧中继已下线，这张码用不了
        assertTrue(parsePairingQr("""{"type":"dsh-link","pairingCode":"1","certFingerprint":"ab","relay":{"client":"x","routeId":"r","routeSecret":"s"}}""") is PairingQrResult.Invalid)
    }
}
