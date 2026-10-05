package dev.deeplinks.core

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
        val key = "dGhlIHNhbXBsZSBub25jZQ=="
        val proxy = PreviewLocalProxy(
            exchange = { error("http") },
            webSocket = { forward, client, accept ->
                assertTrue(forward.pluginPath.startsWith("/dsh-link/mobile/preview/$id/"))
                accept(null)
                val frame = readClientFrame(client.getInputStream())
                assertEquals("hi", frame!!.payload.toString(StandardCharsets.UTF_8))
                writeServerFrame(client.getOutputStream(), 0x1, "yo".toByteArray())
            },
        )
        proxy.start()
        try {
            Socket(InetAddress.getByName("127.0.0.1"), proxy.port).use { socket ->
                socket.soTimeout = 3_000
                val out = socket.getOutputStream()
                val path = "/${proxy.key}/$id/ws"
                out.write(
                    ("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\n" +
                        "Connection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n")
                        .toByteArray(),
                )
                out.flush()
                val status = readHttpHead(socket.getInputStream())
                assertTrue(status.startsWith("HTTP/1.1 101"))
                assertTrue(status.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="))
                assertFalse(status.contains("Sec-WebSocket-Protocol"))
                out.write(maskedText("hi"))
                out.flush()
                val frame = readClientFrame(socket.getInputStream(), requireMask = false)
                assertEquals(0x1, frame!!.opcode)
                assertEquals("yo", frame.payload.toString(StandardCharsets.UTF_8))
            }
        } finally {
            proxy.close()
        }
    }

    @Test
    fun `rfc sample accept header`() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", webSocketAccept("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun `missing or short websocket key is 400`() {
        assertFalse(isValidWebSocketKey("AAAA"))
        assertFalse(isValidWebSocketKey("%%%"))
        var called = false
        val proxy = PreviewLocalProxy(
            exchange = { error("http") },
            webSocket = { _, _, _ -> called = true },
        )
        proxy.start()
        try {
            val id = "22".repeat(12)
            val missing = websocketUpgrade(proxy, id, key = null)
            assertTrue(missing, missing.contains("400"))
            assertFalse(called)
            val short = websocketUpgrade(proxy, id, key = "AAAA")
            assertTrue(short, short.contains("400"))
            assertFalse(called)
        } finally {
            proxy.close()
        }
    }

    @Test
    fun `upstream headers drop handshake fields and keep protocol`() {
        val headers = previewUpstreamHeaders(
            listOf(
                "Host" to "127.0.0.1",
                "Connection" to "Upgrade",
                "Upgrade" to "websocket",
                "Sec-WebSocket-Key" to "dGhlIHNhbXBsZSBub25jZQ==",
                "Sec-WebSocket-Version" to "13",
                "Sec-WebSocket-Extensions" to "permessage-deflate",
                "Sec-WebSocket-Protocol" to "vite-hmr, other",
                "Origin" to "http://127.0.0.1",
            ),
        )
        val names = headers.map { it.first.lowercase() }
        assertFalse(names.contains("host"))
        assertFalse(names.contains("sec-websocket-key"))
        assertFalse(names.contains("sec-websocket-version"))
        assertFalse(names.contains("sec-websocket-extensions"))
        assertEquals("vite-hmr, other", headers.first { it.first.equals("Sec-WebSocket-Protocol", true) }.second)
        assertEquals("http://127.0.0.1", headers.first { it.first.equals("Origin", true) }.second)
    }

    @Test
    fun `server frame over 65535 uses 64 bit length`() {
        val payload = ByteArray(70_000) { (it % 251).toByte() }
        val raw = ByteArrayOutputStream()
        writeServerFrame(raw, 0x1, payload)
        val bytes = raw.toByteArray()
        assertEquals(127, bytes[1].toInt() and 0xff)
        val frame = readClientFrame(ByteArrayInputStream(bytes), requireMask = false)
        assertEquals(0x1, frame!!.opcode)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun `client frame over 65535 is unmasked and reassembled`() {
        val payload = ByteArray(70_000) { (it % 251).toByte() }
        val frame = readClientFrame(ByteArrayInputStream(clientFrame(fin = true, opcode = 0x1, payload = payload)))
        assertEquals(0x1, frame!!.opcode)
        assertTrue(frame.fin)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun `unmasked client frame closes with 1002`() {
        val written = ByteArrayOutputStream()
        val error = assertThrows(WsProtocolException::class.java) {
            readClientFrame(
                ByteArrayInputStream(byteArrayOf(0x81.toByte(), 0x01, 'a'.code.toByte())),
                FrameWriter(written),
            )
        }
        assertEquals(1002, error.code)
        val close = written.toByteArray()
        assertEquals(0x88, close[0].toInt() and 0xff)
        assertEquals(0x03, close[2].toInt() and 0xff)
        assertEquals(0xEA, close[3].toInt() and 0xff)
    }

    @Test
    fun `frame longer than max closes with 1009`() {
        val written = ByteArrayOutputStream()
        val head = ByteArrayOutputStream()
        head.write(0x81)
        head.write(0xFF)
        val length = MAX_FRAME + 1
        for (shift in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) {
            head.write((length.toLong() shr shift).toInt() and 0xff)
        }
        val error = assertThrows(WsProtocolException::class.java) {
            readClientFrame(ByteArrayInputStream(head.toByteArray()), FrameWriter(written))
        }
        assertEquals(1009, error.code)
        val close = written.toByteArray()
        assertEquals(0x88, close[0].toInt() and 0xff)
        assertEquals(0x03, close[2].toInt() and 0xff)
        assertEquals(0xF1, close[3].toInt() and 0xff)
    }

    @Test
    fun `fragments reassemble and control frames can sit between them`() {
        val bytes = clientFrame(false, 0x1, "hel".toByteArray()) +
            clientFrame(true, 0x9, "p".toByteArray()) +
            clientFrame(true, 0x0, "lo".toByteArray())
        val reader = ClientMessageReader(ByteArrayInputStream(bytes), FrameWriter(ByteArrayOutputStream()))
        val ping = reader.next()
        assertEquals(0x9, ping!!.opcode)
        assertEquals("p", ping.payload.toString(StandardCharsets.UTF_8))
        val text = reader.next()
        assertEquals(0x1, text!!.opcode)
        assertEquals("hello", text.payload.toString(StandardCharsets.UTF_8))
        assertNull(reader.next())
    }

    @Test
    fun `fragment total over the max closes with 1009`() {
        val written = ByteArrayOutputStream()
        val chunk = ByteArray(MAX_FRAME)
        val bytes = clientFrame(false, 0x2, chunk) + clientFrame(true, 0x0, byteArrayOf(1))
        val reader = ClientMessageReader(ByteArrayInputStream(bytes), FrameWriter(written))
        val error = assertThrows(WsProtocolException::class.java) { reader.next() }
        assertEquals(1009, error.code)
        assertEquals(0x88, written.toByteArray()[0].toInt() and 0xff)
    }

    @Test
    fun closeIsIdempotentAndBindFailureSurfaces() {
        val proxy = PreviewLocalProxy(exchange = { error("no") }, webSocket = { _, _, _ -> })
        proxy.close()
        proxy.close()
        assertThrows(Exception::class.java) { proxy.start() }
        proxy.close()
    }

    @Test
    fun `subprotocol is echoed and accept is valid`() {
        BridgeFixture().use { bridge ->
            bridge.start(responseProtocol = "vite-hmr")
            bridge.connect(requestProtocol = "vite-hmr")
            assertTrue(bridge.clientOpen.await(5, TimeUnit.SECONDS))
            assertEquals("vite-hmr", bridge.clientProtocol.get())
            assertNull(bridge.clientFailure.get())
            val recorded = bridge.server.takeRequest(3, TimeUnit.SECONDS)
            assertEquals("vite-hmr", recorded!!.headers["Sec-WebSocket-Protocol"])
        }
    }

    @Test
    fun `upstream protocol the client did not request fails the handshake`() {
        BridgeFixture().use { bridge ->
            bridge.start(responseProtocol = "nope")
            bridge.connect(requestProtocol = "vite-hmr")
            assertTrue(bridge.clientDown.await(5, TimeUnit.SECONDS))
            assertEquals(1L, bridge.clientOpen.count)
            assertEquals(502, bridge.clientHttpCode.get())
        }
    }

    @Test
    fun `upstream 200kb text arrives intact`() {
        val payload = "a".repeat(200 * 1024)
        BridgeFixture().use { bridge ->
            bridge.start(responseProtocol = "vite-hmr")
            bridge.connect(requestProtocol = "vite-hmr")
            assertTrue(bridge.clientOpen.await(5, TimeUnit.SECONDS))
            assertTrue(awaitUpstream(bridge).send(payload))
            assertTrue(awaitSize(bridge.clientMessages, 1))
            assertEquals(payload, snapshot(bridge.clientMessages).single())
        }
    }

    @Test
    fun `client 200kb text arrives upstream intact`() {
        val payload = "b".repeat(200 * 1024)
        BridgeFixture().use { bridge ->
            bridge.start(responseProtocol = "vite-hmr")
            val client = bridge.connect(requestProtocol = "vite-hmr")
            assertTrue(bridge.clientOpen.await(5, TimeUnit.SECONDS))
            assertTrue(client.send(payload))
            assertTrue(awaitSize(bridge.upstreamMessages, 1))
            assertEquals(payload, snapshot(bridge.upstreamMessages).single())
        }
    }

    @Test
    fun `both directions can exchange 500 messages without corruption`() {
        BridgeFixture().use { bridge ->
            bridge.start(responseProtocol = "vite-hmr")
            val client = bridge.connect(requestProtocol = "vite-hmr")
            assertTrue(bridge.clientOpen.await(5, TimeUnit.SECONDS))
            val upstream = awaitUpstream(bridge)
            val gate = CountDownLatch(1)
            val sender = Thread {
                gate.await()
                repeat(500) { index -> assertTrue(upstream.send("u$index")) }
            }
            sender.isDaemon = true
            sender.start()
            gate.countDown()
            repeat(500) { index -> assertTrue(client.send("c$index")) }
            sender.join(10_000)
            assertTrue(awaitSize(bridge.clientMessages, 500))
            assertTrue(awaitSize(bridge.upstreamMessages, 500))
            assertEquals((0 until 500).map { "u$it" }, snapshot(bridge.clientMessages))
            assertEquals((0 until 500).map { "c$it" }, snapshot(bridge.upstreamMessages))
            assertNull(bridge.clientFailure.get())
        }
    }

    @Test
    fun `client close reaches upstream within 2 seconds`() {
        BridgeFixture().use { bridge ->
            bridge.start(responseProtocol = "vite-hmr")
            val client = bridge.connect(requestProtocol = "vite-hmr")
            assertTrue(bridge.clientOpen.await(5, TimeUnit.SECONDS))
            assertTrue(client.close(1000, null))
            assertTrue(bridge.upstreamDown.await(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `proxy close disconnects client and upstream within 2 seconds`() {
        BridgeFixture().use { bridge ->
            bridge.start(responseProtocol = "vite-hmr")
            bridge.connect(requestProtocol = "vite-hmr")
            assertTrue(bridge.clientOpen.await(5, TimeUnit.SECONDS))
            bridge.proxy.close()
            assertTrue(bridge.clientDown.await(2, TimeUnit.SECONDS))
            assertTrue(bridge.upstreamDown.await(2, TimeUnit.SECONDS))
        }
    }
}

private class BridgeFixture : AutoCloseable {
    val server = MockWebServer()
    val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    val upstream = AtomicReference<WebSocket>()
    val clientProtocol = AtomicReference<String?>()
    val clientMessages = Collections.synchronizedList(mutableListOf<String>())
    val upstreamMessages = Collections.synchronizedList(mutableListOf<String>())
    val clientOpen = CountDownLatch(1)
    val clientDown = CountDownLatch(1)
    val upstreamDown = CountDownLatch(1)
    val clientFailure = AtomicReference<Throwable>()
    val clientHttpCode = AtomicReference<Int>()
    lateinit var proxy: PreviewLocalProxy
    private var clientSocket: WebSocket? = null

    fun start(responseProtocol: String?) {
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                upstream.set(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                upstreamMessages.add(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                upstreamDown.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                upstreamDown.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                upstreamDown.countDown()
            }
        }
        val response = MockResponse.Builder().apply {
            if (responseProtocol != null) addHeader("Sec-WebSocket-Protocol", responseProtocol)
        }.webSocketUpgrade(listener).build()
        server.enqueue(response)
        server.start()
        proxy = PreviewLocalProxy(
            exchange = { error("http") },
            webSocket = { forward, socket, accept ->
                relayPreviewWebSocket(socket, forward, accept) { headers, upstreamListener ->
                    val builder = Request.Builder().url(server.url("/ws"))
                    headers.forEach { (name, value) -> builder.header(name, value) }
                    http.newWebSocket(builder.build(), upstreamListener)
                }
            },
        )
        proxy.start()
    }

    fun connect(requestProtocol: String?): WebSocket {
        val id = "ab".repeat(12)
        val builder = Request.Builder().url("http://127.0.0.1:${proxy.port}/${proxy.key}/$id/")
        if (requestProtocol != null) builder.header("Sec-WebSocket-Protocol", requestProtocol)
        val socket = http.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                clientProtocol.set(response.header("Sec-WebSocket-Protocol"))
                clientOpen.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                clientMessages.add(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                clientDown.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                clientDown.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                clientFailure.set(t)
                if (response != null) clientHttpCode.set(response.code)
                clientDown.countDown()
            }
        })
        clientSocket = socket
        return socket
    }

    override fun close() {
        if (::proxy.isInitialized) runCatching { proxy.close() }
        runCatching { clientSocket?.cancel() }
        clientDown.await(2, TimeUnit.SECONDS)
        upstreamDown.await(2, TimeUnit.SECONDS)
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        runCatching { server.close() }
    }
}

private fun awaitSize(list: MutableList<*>, size: Int, timeoutMs: Long = 10_000): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (list.size < size && System.nanoTime() < deadline) Thread.sleep(10)
    return list.size >= size
}

/** 客户端 onOpen 可能早于上游服务端的 onOpen；等上游 socket 就位再用，避免偶发 NPE。 */
private fun awaitUpstream(bridge: BridgeFixture, timeoutMs: Long = 5_000): WebSocket {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (bridge.upstream.get() == null && System.nanoTime() < deadline) Thread.sleep(10)
    return requireNotNull(bridge.upstream.get()) { "upstream WebSocket did not open" }
}

private fun snapshot(list: MutableList<String>): List<String> = synchronized(list) { ArrayList(list) }

private fun httpGet(port: Int, path: String): String =
    Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
        socket.soTimeout = 3_000
        val out = socket.getOutputStream()
        out.write("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
        socket.getInputStream().readBytes().toString(StandardCharsets.ISO_8859_1)
    }

private fun websocketUpgrade(proxy: PreviewLocalProxy, id: String, key: String?): String =
    Socket(InetAddress.getByName("127.0.0.1"), proxy.port).use { socket ->
        socket.soTimeout = 3_000
        val keyLine = if (key == null) "" else "Sec-WebSocket-Key: $key\r\n"
        val path = "/${proxy.key}/$id/ws"
        socket.getOutputStream().write(
            ("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                keyLine + "\r\n").toByteArray(),
        )
        socket.getOutputStream().flush()
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

private fun maskedText(text: String): ByteArray = clientFrame(true, 0x1, text.toByteArray())

private fun clientFrame(fin: Boolean, opcode: Int, payload: ByteArray, mask: ByteArray = byteArrayOf(1, 2, 3, 4)): ByteArray {
    val out = ByteArrayOutputStream()
    out.write((if (fin) 0x80 else 0) or opcode)
    val maskBit = 0x80
    when {
        payload.size < 126 -> out.write(maskBit or payload.size)
        payload.size <= 0xffff -> {
            out.write(maskBit or 126)
            out.write(payload.size shr 8)
            out.write(payload.size and 0xff)
        }
        else -> {
            out.write(maskBit or 127)
            val n = payload.size.toLong()
            for (shift in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) {
                out.write(((n shr shift) and 0xff).toInt())
            }
        }
    }
    out.write(mask)
    payload.forEachIndexed { index, byte ->
        out.write(byte.toInt() xor mask[index % 4].toInt())
    }
    return out.toByteArray()
}
