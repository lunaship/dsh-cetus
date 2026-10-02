package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

class PreviewLocalProxyTest {

    @Test
    fun pathRequiresTheKeyAndAPreviewId() {
        val key = "ab".repeat(16)
        val id = "cd".repeat(12)
        assertEquals(
            "/dsh-link/mobile/preview/$id/assets/app.js?x=1",
            mapPreviewPath(key, "/$key/$id/assets/app.js?x=1"),
        )
        assertNull(mapPreviewPath(key, "/other/$id/"))
        assertNull(mapPreviewPath(key, "/$key/not-an-id/"))
        assertNull(mapPreviewPath(key, "http://127.0.0.1/$key/$id/"))
        assertNull(mapPreviewPath(key, "/$key/$id/../secret"))
    }

    @Test
    fun listensOnLoopbackAndRejectsTheWrongKey() {
        var called = false
        val proxy = PreviewLocalProxy(
            exchange = {
                called = true
                PreviewHttpResult(200, listOf("content-type" to "text/plain", "content-length" to "2"), ByteArrayInputStream("ok".toByteArray()))
            },
            webSocket = { _, _, _ -> },
        )
        proxy.start()
        try {
            assertTrue(proxy.bindAddress.isLoopbackAddress)
            assertEquals("127.0.0.1", proxy.bindAddress.hostAddress)
            val refused = httpGet(proxy.port, "/nope/path")
            assertTrue(refused.contains("404"))
            assertFalse(called)
            val id = "ef".repeat(12)
            val ok = httpGet(proxy.port, "/${proxy.key}/$id/index.html")
            assertTrue(ok.contains("200"))
            assertTrue(ok.contains("ok"))
            assertTrue(called)
        } finally {
            proxy.close()
        }
    }

    @Test
    fun forwardsWebSocketFrames() {
        val id = "11".repeat(12)
        val proxy = PreviewLocalProxy(
            exchange = { error("http") },
            webSocket = { forward, client, accept ->
                assertTrue(forward.pluginPath.startsWith("/dsh-link/mobile/preview/$id/"))
                accept()
                val frame = readClientFrame(client.getInputStream())
                assertEquals("hi", frame!!.payload.toString(StandardCharsets.UTF_8))
                writeServerFrame(client.getOutputStream(), 0x1, "yo".toByteArray())
            },
        )
        proxy.start()
        try {
            Socket(InetAddress.getByName("127.0.0.1"), proxy.port).use { socket ->
                val out = socket.getOutputStream()
                val path = "/${proxy.key}/$id/ws"
                out.write("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n".toByteArray())
                out.flush()
                val status = readHttpHead(socket.getInputStream())
                assertTrue(status.startsWith("HTTP/1.1 101"))
                out.write(maskedText("hi"))
                out.flush()
                val frame = readClientFrame(socket.getInputStream())
                assertEquals(0x1, frame!!.opcode)
                assertEquals("yo", frame.payload.toString(StandardCharsets.UTF_8))
            }
        } finally {
            proxy.close()
        }
    }
}

private fun httpGet(port: Int, path: String): String =
    Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
        socket.soTimeout = 3_000
        val out = socket.getOutputStream()
        out.write("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
        socket.getInputStream().readBytes().toString(StandardCharsets.ISO_8859_1)
    }

private fun readHttpHead(input: java.io.InputStream): String {
    val out = StringBuilder()
    while (!out.endsWith("\r\n\r\n") && out.length < 4096) {
        val b = input.read()
        if (b < 0) break
        out.append(b.toChar())
    }
    return out.toString()
}

private fun maskedText(text: String): ByteArray {
    val payload = text.toByteArray()
    val mask = byteArrayOf(1, 2, 3, 4)
    val out = java.io.ByteArrayOutputStream()
    out.write(0x81)
    out.write(0x80 or payload.size)
    out.write(mask)
    payload.forEachIndexed { index, byte ->
        out.write(byte.toInt() xor mask[index % 4].toInt())
    }
    return out.toByteArray()
}
