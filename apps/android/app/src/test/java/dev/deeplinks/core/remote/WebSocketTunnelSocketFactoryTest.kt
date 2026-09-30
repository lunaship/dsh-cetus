package dev.deeplinks.core.remote

import dev.deeplinks.core.Host
import dev.deeplinks.core.HostHttp
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Request
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLServerSocket
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSocketTunnelSocketFactoryTest {
    private val route = ByteArray(16) { (it + 1).toByte() }
    private val keyId = ByteArray(16) { (it + 20).toByte() }
    private val key = ByteArray(32) { (it + 40).toByte() }
    private val nonce = ByteArray(16) { (it + 70).toByte() }

    @Test
    fun `ready authenticates client open and relays one MiB with backpressure`() {
        val server = MockWebServer()
        val opened = CountDownLatch(1)
        val accepted = AtomicReference<JSONObject>()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send(JSONObject().put("t", "hello").put("v", 1)
                    .put("ch", DlpCrypto.base64Url(ByteArray(32) { 0x5a }))
                    .put("now", 1790000000L).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                accepted.set(JSONObject(text))
                opened.countDown()
                webSocket.send("""{"t":"ready"}""")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { webSocket.send(bytes) }
        }).build())
        server.start()
        var socket: Socket? = null
        try {
            val activeSocket = factory(server).createSocket()
            socket = activeSocket
            activeSocket.connect(InetSocketAddress("relay.invalid", 443), 5_000)
            assertTrue(opened.await(2, TimeUnit.SECONDS))
            val open = accepted.get()
            assertEquals("client_open", open.getString("t"))
            assertEquals("device", open.getString("kind"))
            assertEquals(DlpCrypto.base64Url(route), open.getString("route"))
            val transcript = DlpCrypto.clientTranscript(route, "device", keyId, open.getLong("ts"),
                java.util.Base64.getUrlDecoder().decode(open.getString("nonce")))
            assertEquals(DlpCrypto.base64Url(DlpCrypto.clientMac(key, transcript)), open.getString("mac"))

            val payload = ByteArray(1024 * 1024) { (it * 13).toByte() }
            activeSocket.getOutputStream().write(payload)
            activeSocket.getOutputStream().flush()
            val echoed = ByteArray(payload.size)
            var offset = 0
            while (offset < echoed.size) {
                val count = activeSocket.getInputStream().read(echoed, offset, echoed.size - offset)
                check(count >= 0) { "tunnel ended before the echo completed" }
                offset += count
            }
            assertArrayEquals(payload, echoed)
        } finally { socket?.close(); quietlyClose(server) }
    }

    @Test
    fun `pre ready relay errors map to typed route failures`() {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("""{"t":"hello","v":1,"ch":"${DlpCrypto.base64Url(ByteArray(32))}","now":1790000000}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("""{"t":"error","code":"ROUTE_OFFLINE"}""")
            }
        }).build())
        server.start()
        var socket: Socket? = null
        try {
            val activeSocket = factory(server).createSocket()
            socket = activeSocket
            val error = org.junit.Assert.assertThrows(RouteOfflineException::class.java) {
                activeSocket.connect(InetSocketAddress("relay.invalid", 443), 5_000)
            }
            assertTrue(error.message!!.contains("offline"))
        } finally { socket?.close(); quietlyClose(server) }
    }

    @Test
    fun `remote close produces EOF on the tunnel socket`() {
        val server = MockWebServer()
        val webSocketRef = AtomicReference<WebSocket>()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocketRef.set(webSocket)
                webSocket.send("""{"t":"hello","v":1,"ch":"${DlpCrypto.base64Url(ByteArray(32))}","now":1790000000}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("""{"t":"ready"}""")
            }
        }).build())
        server.start()
        try {
            val socket = factory(server).createSocket()
            socket.connect(InetSocketAddress("relay.invalid", 443), 5_000)
            socket.soTimeout = 2_000
            webSocketRef.get().close(1000, "done")
            assertEquals(-1, socket.getInputStream().read())
            socket.close()
        } finally { quietlyClose(server) }
    }

    @Test
    fun `pinned inner TLS handshake and HTTP request pass through WSS tunnel`() {
        val password = "dlp1-test".toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        val p12 = javaClass.getResourceAsStream("/dlp1-test-server.p12")!!
        p12.use { keyStore.load(it, password) }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, password) }
        val serverContext = SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
        val tlsServer = serverContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        val certificate = keyStore.getCertificate(keyStore.aliases().nextElement())
        val pin = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
        val trustManager = dev.deeplinks.core.PinnedSsl.pinnedTrustManager(pin)
        val clientContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        val requestSeen = CountDownLatch(1)
        val relayPeer = AtomicReference<Socket>()
        val tlsServerThread = Thread {
            val accepted = tlsServer.accept() as SSLSocket
            accepted.use { secure ->
                secure.startHandshake()
                val input = secure.getInputStream().bufferedReader()
                val requestLine = input.readLine()
                while (input.readLine()?.isNotEmpty() == true) Unit
                if (requestLine == "GET / HTTP/1.1") requestSeen.countDown()
                val body = "DLP/1 tunneled"
                secure.getOutputStream().write(
                    ("HTTP/1.1 200 OK\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body")
                        .toByteArray(Charsets.US_ASCII),
                )
                secure.getOutputStream().flush()
            }
        }.apply { isDaemon = true; start() }

        val relay = MockWebServer()
        relay.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("""{"t":"hello","v":1,"ch":"${DlpCrypto.base64Url(ByteArray(32) { 0x13 })}","now":1790000000}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (JSONObject(text).optString("t") != "client_open") return
                val peer = Socket("127.0.0.1", tlsServer.localPort)
                relayPeer.set(peer)
                webSocket.send("""{"t":"ready"}""")
                Thread {
                    val input = peer.getInputStream()
                    val buffer = ByteArray(64 * 1024)
                    try {
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            webSocket.send(buffer.copyOfRange(0, count).toByteString())
                        }
                    } catch (_: Exception) { }
                }.apply { isDaemon = true; start() }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                relayPeer.get()?.getOutputStream()?.write(bytes.toByteArray())
                relayPeer.get()?.getOutputStream()?.flush()
            }
        }).build())
        relay.start()
        try {
            val raw = factory(relay).createSocket()
            raw.connect(InetSocketAddress("relay.invalid", 443), 5_000)
            val secure = clientContext.socketFactory.createSocket(raw, "localhost", tlsServer.localPort, true) as SSLSocket
            val parameters: SSLParameters = secure.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            secure.sslParameters = parameters
            secure.startHandshake()
            secure.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            secure.getOutputStream().flush()
            val response = secure.getInputStream().readBytes().toString(Charsets.US_ASCII)
            assertTrue(requestSeen.await(2, TimeUnit.SECONDS))
            assertTrue(response.contains("HTTP/1.1 200 OK"))
            assertTrue(response.contains("DLP/1 tunneled"))
            secure.close()
        } finally {
            runCatching { relayPeer.get()?.close() }
            runCatching { tlsServer.close() }
            quietlyClose(relay)
            tlsServerThread.join(2_000)
        }
    }

    @Test
    fun `production remote client reuses one tunnel for sequential GETs`() {
        val harness = remoteTunnelHarness()
        try {
            val client = HostHttp.buildRemoteClient(harness.host, harness.remote, 0L)
            repeat(5) {
                client.newCall(Request.Builder().url("https://localhost/messages").build())
                    .execute().use { response ->
                        assertEquals(200, response.code)
                        assertEquals("ok", response.body.string())
                    }
            }
            assertEquals("顺序 5 次 GET 只应建一条隧道", 1, harness.relay.requestCount)
            // 先放掉池里的隧道再关中继，避免 MockWebServer 收尾时仍有活动连接。
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        } finally {
            harness.close()
        }
    }

    @Test
    fun `executeRemote reuses a tunnel and caps concurrency at RemoteGate permits`() {
        val harness = remoteTunnelHarness()
        try {
            repeat(5) {
                HostHttp.execute(
                    harness.host,
                    HostHttp.DshRequest("GET", "/messages"),
                    forceRoute = HostRoute.REMOTE,
                    remoteOverride = harness.remote,
                ).use { response ->
                        assertEquals(200, response.code)
                        assertEquals("ok", response.body.string())
                    }
            }
            assertEquals("顺序 5 次请求只应建一条隧道", 1, harness.relay.requestCount)

            val executor = Executors.newFixedThreadPool(8)
            try {
                val futures = (1..8).map {
                    executor.submit(Callable {
                        HostHttp.execute(
                            harness.host,
                            HostHttp.DshRequest("GET", "/messages"),
                            forceRoute = HostRoute.REMOTE,
                            remoteOverride = harness.remote,
                        ).use { it.body.string() }
                    })
                }
                futures.forEach { it.get(30, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }
            // 并发被 RemoteGate 限制在 PERMITS 内，加上顺序阶段留下的一条空闲隧道。
            assertTrue(
                "并发后隧道数 ${harness.relay.requestCount} 不应超过 ${RemoteGate.PERMITS + 1}",
                harness.relay.requestCount <= RemoteGate.PERMITS + 1,
            )
            // 先放掉池里的隧道再关中继，避免 MockWebServer 收尾时仍有活动连接。
            HostHttp.evictIdleRemote()
        } finally {
            harness.close()
        }
    }

    /** 外层 WSS 中继（TLS 的 MockWebServer）+ 内层 TLS 假插件，端到端一条 DLP/1 隧道。 */
    private class RemoteTunnelHarness(
        val host: Host,
        val remote: RemoteRoute,
        val relay: MockWebServer,
        private val cleanup: () -> Unit,
    ) : AutoCloseable {
        override fun close() = cleanup()
    }

    private fun remoteTunnelHarness(): RemoteTunnelHarness {
        val password = "dlp1-test".toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/dlp1-test-server.p12")!!.use { keyStore.load(it, password) }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, password) }
        val serverContext = SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
        val certificate = keyStore.getCertificate(keyStore.aliases().nextElement())
        val pin = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }

        val tlsServer = serverContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        val peers = Collections.synchronizedList(mutableListOf<Socket>())
        val tlsThread = Thread {
            try {
                while (true) {
                    val accepted = tlsServer.accept() as SSLSocket
                    Thread { serveInnerTls(accepted) }.apply { isDaemon = true; start() }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }

        val relay = MockWebServer()
        relay.useHttps(serverContext.socketFactory)
        repeat(8) {
            relay.enqueue(MockResponse.Builder().webSocketUpgrade(relayUpgradeListener(tlsServer.localPort, peers)).build())
        }
        relay.start()

        val remote = RemoteRoute(
            endpoint = "wss://localhost:${relay.port}/ws",
            routeId = ByteArray(16) { (it + 1).toByte() },
            keyId = ByteArray(16) { (it + 20).toByte() },
            key = ByteArray(32) { (it + 40).toByte() },
            kind = "device",
            outerPin = pin,
        )
        val host = Host(
            name = "harness",
            baseUrl = "https://localhost:443",
            token = "",
            deviceId = "harness-${relay.port}",
            certFingerprint = pin,
        )
        return RemoteTunnelHarness(host, remote, relay) {
            runCatching { tlsServer.close() }
            peers.forEach { runCatching { it.close() } }
            tlsThread.join(1_000)
            // 收尾只负责释放资源，不让 MockWebServer 的队列收尾超时把测试判成失败。
            runCatching { relay.close() }
        }
    }

    /** 每个外层 WSS 连接对应一个内层 TLS 假插件；按字节做双向桥接。 */
    private fun relayUpgradeListener(tlsPort: Int, peers: MutableList<Socket>) = object : WebSocketListener() {
        @Volatile private var peer: Socket? = null

        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            webSocket.send("""{"t":"hello","v":1,"ch":"${DlpCrypto.base64Url(ByteArray(32) { 0x13 })}","now":1790000000}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (JSONObject(text).optString("t") != "client_open") return
            val opened = Socket("127.0.0.1", tlsPort)
            peer = opened
            peers.add(opened)
            webSocket.send("""{"t":"ready"}""")
            Thread {
                val input = opened.getInputStream()
                val buffer = ByteArray(64 * 1024)
                try {
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        webSocket.send(buffer.copyOfRange(0, count).toByteString())
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true; start() }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                peer?.getOutputStream()?.apply { write(bytes.toByteArray()); flush() }
            } catch (_: Exception) {
            }
        }
    }

    /** 内层 TLS 假插件：对一个连接内的每个 HTTP 请求回 200 并保持连接（keep-alive）。 */
    private fun serveInnerTls(socket: SSLSocket) {
        socket.use { secure ->
            try {
                secure.startHandshake()
                val reader = secure.getInputStream().bufferedReader(Charsets.US_ASCII)
                val out = secure.getOutputStream()
                while (true) {
                    val requestLine = reader.readLine() ?: break
                    if (requestLine.isEmpty()) break
                    while (true) {
                        val line = reader.readLine() ?: return
                        if (line.isEmpty()) break
                    }
                    val body = "ok"
                    out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: keep-alive\r\n\r\n$body")
                            .toByteArray(Charsets.US_ASCII),
                    )
                    out.flush()
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * `MockWebServer.close()` 在本机偶发「Gave up waiting for queue to shut down」的 5 秒收尾超时
     * （main 上同样出现，与用例断言无关）。收尾只负责释放资源，吞掉它以免把绿色用例误判成失败。
     */
    private fun quietlyClose(server: MockWebServer) {
        runCatching { server.close() }
    }

    private fun mappingFactory() = WebSocketTunnelSocketFactory(
        endpoint = "wss://relay.invalid/ws",
        routeId = ByteArray(16),
        kind = "device",
        keyId = ByteArray(16),
        key = ByteArray(32),
    )

    @Test
    fun `busy reject codes map to RouteBusyException with the code`() {
        val factory = mappingFactory()
        for (code in listOf("DEVICE_LIMIT", "SERVER_BUSY", "RATE_LIMITED")) {
            val error = factory.mapError(code)
            assertTrue("$code should be busy, was $error", error is RouteBusyException)
            assertEquals(code, (error as RouteBusyException).code)
        }
        // close reason 也按同一套映射
        val closed = factory.mapClose(4005, "SERVER_BUSY")
        assertTrue(closed is RouteBusyException)
        assertEquals("SERVER_BUSY", (closed as RouteBusyException).code)
    }

    @Test
    fun `other reject codes stay RouteRejectedException`() {
        val factory = mappingFactory()
        val rejected = factory.mapError("BAD_MAC")
        assertTrue(rejected is RouteRejectedException)
        assertEquals("BAD_MAC", (rejected as RouteRejectedException).code)
        val unknown = factory.mapClose(4007, "UNKNOWN_KEY")
        assertTrue(unknown is RouteRejectedException)
        assertEquals("UNKNOWN_KEY", (unknown as RouteRejectedException).code)
    }

    private fun factory(server: MockWebServer) = WebSocketTunnelSocketFactory(
        endpoint = server.url("/ws").toString().replaceFirst("http", "ws"),
        routeId = route,
        kind = "device",
        keyId = keyId,
        key = key,
        allowInsecureWs = true,
        now = { 1_790_000_000_000L },
    )
}
