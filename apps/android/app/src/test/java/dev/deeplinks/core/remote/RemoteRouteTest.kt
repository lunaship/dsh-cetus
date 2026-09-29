package dev.deeplinks.core.remote

import java.io.File
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRouteTest {
    private val vectors by lazy { JSONObject(File("../../../testdata/dlp1/vectors.json").readText()) }
    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    @Test
    fun `QR bootstrap route derives the shared keys`() {
        val input = vectors.getJSONObject("inputs")
        val output = vectors.getJSONObject("outputs")
        val remote = JSONObject()
            .put("e", "wss://relay.example/ws")
            .put("r", b64(output.getString("routeId").chunked(2).map { it.toInt(16).toByte() }.toByteArray()))
            .put("s", b64(input.getString("bootstrapSeed").chunked(2).map { it.toInt(16).toByte() }.toByteArray()))
            .put("p", "a".repeat(64))
        val route = RemoteRoute.fromQr(JSONObject().put("remote", remote))!!
        assertEquals("bootstrap", route.kind)
        assertEquals(output.getString("bootstrapId"), route.keyId.joinToString("") { "%02x".format(it) })
        assertEquals(output.getString("bootstrapKey"), route.key.joinToString("") { "%02x".format(it) })
        assertEquals("a".repeat(64), route.outerPin)
    }

    @Test
    fun `pair and bootstrap device route parse separately`() {
        val handle = ByteArray(16) { it.toByte() }
        val key = ByteArray(32) { (it + 32).toByte() }
        val routeId = ByteArray(16) { (it + 64).toByte() }
        val remote = JSONObject().put("e", "wss://relay.example/ws")
            .put("r", b64(routeId)).put("h", b64(handle)).put("k", b64(key))
        val pair = RemoteRoute.fromPairResponse(JSONObject().put("remote", remote))!!
        assertEquals("device", pair.kind)
        assertEquals(handle.toList(), pair.keyId.toList())
        assertEquals(key.toList(), pair.key.toList())

        assertTrue(RemoteRoute.bootstrapUpdate(JSONObject()).present.not())
        assertNull(RemoteRoute.bootstrapUpdate(JSONObject()).route)
        val cleared = RemoteRoute.bootstrapUpdate(JSONObject().put("remote", JSONObject.NULL))
        assertTrue(cleared.present)
        assertNull(cleared.route)
        val updated = RemoteRoute.bootstrapUpdate(JSONObject().put("remote", remote))
        assertTrue(updated.present)
        assertEquals(pair.keyId.toList(), updated.route!!.keyId.toList())
    }

    @Test
    fun `missing and null remote keys stay distinct and malformed fields fail`() {
        assertFalse(RemoteRoute.bootstrapUpdate(JSONObject()).present)
        assertTrue(RemoteRoute.bootstrapUpdate(JSONObject().put("remote", JSONObject.NULL)).present)
        assertNull(RemoteRoute.fromPairResponse(JSONObject().put("remote", JSONObject.NULL)))
        val malformed = JSONObject().put("remote", JSONObject().put("e", "wss://relay.example/ws").put("r", b64(ByteArray(15))))
        assertThrows(IllegalArgumentException::class.java) { RemoteRoute.fromPairResponse(malformed) }
        val invalidEndpoint = JSONObject().put("remote", JSONObject().put("e", "ws://relay.example/ws").put("r", b64(ByteArray(16))).put("s", b64(ByteArray(16))))
        assertThrows(IllegalArgumentException::class.java) { RemoteRoute.fromQr(invalidEndpoint) }
    }
}
