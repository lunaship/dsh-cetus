package dev.deeplinks.core.remote

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DlpCryptoTest {
    private val vectors by lazy {
        JSONObject(File("../../../testdata/dlp1/vectors.json").readText())
    }
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `route and signed transcripts match shared vectors`() {
        val input = vectors.getJSONObject("inputs")
        val output = vectors.getJSONObject("outputs")
        val pub = bytes(output.getString("hostPub"))
        val route = DlpCrypto.routeId(pub)
        assertEquals(output.getString("routeId"), hex(route))
        assertEquals(
            output.getString("T_register"),
            hex(DlpCrypto.registerTranscript(bytes(input.getString("ch")), pub)),
        )
        assertEquals(
            output.getString("T_accept"),
            hex(DlpCrypto.acceptTranscript(bytes(input.getString("ch")), pub, bytes(input.getString("sid")))),
        )
    }

    @Test
    fun `device and bootstrap values match shared vectors`() {
        val input = vectors.getJSONObject("inputs")
        val output = vectors.getJSONObject("outputs")
        val route = bytes(output.getString("routeId"))
        val handle = bytes(input.getString("relayHandle"))
        val deviceKey = DlpCrypto.deviceRelayKey(bytes(input.getString("keySeed")), handle)
        assertEquals(output.getString("deviceRelayKey"), hex(deviceKey))
        val deviceTranscript = DlpCrypto.clientTranscript(route, "device", handle, input.getLong("ts"), bytes(input.getString("nonce")))
        assertEquals(output.getString("T_client_device"), hex(deviceTranscript))
        assertEquals(output.getString("mac_device"), hex(DlpCrypto.clientMac(deviceKey, deviceTranscript)))

        val (bootstrapId, bootstrapKey) = DlpCrypto.bootstrapKeys(bytes(input.getString("bootstrapSeed")), route)
        assertEquals(output.getString("bootstrapId"), hex(bootstrapId))
        assertEquals(output.getString("bootstrapKey"), hex(bootstrapKey))
        val bootstrapTranscript = DlpCrypto.clientTranscript(route, "bootstrap", bootstrapId, input.getLong("ts"), bytes(input.getString("nonce")))
        assertEquals(output.getString("T_client_bootstrap"), hex(bootstrapTranscript))
        assertEquals(output.getString("mac_bootstrap"), hex(DlpCrypto.clientMac(bootstrapKey, bootstrapTranscript)))
        assertEquals(output.getJSONObject("b64u").getString("routeId"), DlpCrypto.base64Url(route))
        assertEquals(output.getJSONObject("b64u").getString("relayHandle"), DlpCrypto.base64Url(handle))
    }

    @Test
    fun `HKDF output uses exact requested length`() {
        val input = vectors.getJSONObject("inputs")
        val output = vectors.getJSONObject("outputs")
        val route = bytes(output.getString("routeId"))
        val (id, key) = DlpCrypto.bootstrapKeys(bytes(input.getString("bootstrapSeed")), route)
        assertArrayEquals(bytes(output.getString("bootstrapId")), id)
        assertArrayEquals(bytes(output.getString("bootstrapKey")), key)
        assertEquals(16, id.size)
        assertEquals(32, key.size)
    }
}
