package dev.deeplinks.core.remote

import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        } finally { socket?.close(); server.close() }
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
        } finally { socket?.close(); server.close() }
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
        } finally { server.close() }
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
            relay.close()
            tlsServerThread.join(2_000)
        }
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
