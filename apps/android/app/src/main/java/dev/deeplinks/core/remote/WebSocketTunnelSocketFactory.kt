package dev.deeplinks.core.remote

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import dev.deeplinks.core.PinnedSsl
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

open class RouteConnectException(message: String, cause: Throwable? = null) : IOException(message, cause)
class RouteOfflineException : RouteConnectException("remote route offline")
class RouteOpenTimeoutException : RouteConnectException("remote stream open timed out")
class RouteRateLimitedException : RouteConnectException("remote route rate limited")
class RouteServerBusyException : RouteConnectException("remote server busy")
class RouteRejectedException(val code: String) : RouteConnectException("remote request rejected: $code")
class RouteProtocolException : RouteConnectException("remote protocol error")

/** DLP/1 外层 WSS 与插件内层 TLS 之间提供一个真实、可读写的裸 Socket。 */
class WebSocketTunnelSocketFactory(
    private val endpoint: String,
    private val routeId: ByteArray,
    private val kind: String,
    private val keyId: ByteArray,
    private val key: ByteArray,
    private val outerPin: String = "",
    private val clockOffsetSec: Long = 0,
    private val allowInsecureWs: Boolean = false,
    private val now: () -> Long = { System.currentTimeMillis() },
) : SocketFactory() {
    init {
        require(routeId.size == 16 && keyId.size == 16 && key.size == 32)
        require(kind == "device" || kind == "bootstrap")
        require(endpoint.startsWith("wss://") || (allowInsecureWs && endpoint.startsWith("ws://")))
        require(outerPin.isEmpty() || outerPin.matches(Regex("[0-9a-f]{64}")))
    }

    constructor(route: RemoteRoute, clockOffsetSec: Long = 0) : this(
        endpoint = route.endpoint,
        routeId = route.routeId,
        kind = route.kind,
        keyId = route.keyId,
        key = route.key,
        outerPin = route.outerPin,
        clockOffsetSec = clockOffsetSec,
    )

    private val client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder().retryOnConnectionFailure(false)
        if (outerPin.isNotEmpty()) {
            val trustManager = PinnedSsl.pinnedTrustManager(outerPin)
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(trustManager), SecureRandom())
            builder.sslSocketFactory(context.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true }
        }
        builder.build()
    }

    override fun createSocket(): Socket = TunnelSocket()
    override fun createSocket(host: String?, port: Int): Socket = TunnelSocket()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = TunnelSocket()
    override fun createSocket(host: InetAddress?, port: Int): Socket = TunnelSocket()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = TunnelSocket()

    internal fun newRawSocket(): Socket = TunnelSocket()

    private inner class TunnelSocket : Socket() {
        private val closed = AtomicBoolean(false)
        private val ready = CountDownLatch(1)
        private val peerReady = CountDownLatch(1)
        @Volatile private var failure: IOException? = null
        @Volatile private var webSocket: WebSocket? = null
        @Volatile private var serverSocket: ServerSocket? = null
        @Volatile private var gatewayPeer: Socket? = null
        @Volatile private var localSocket: Socket? = null
        @Volatile private var connected = false
        @Volatile private var connectTimeoutMs = 10_000
        @Volatile private var readTimeoutMs = 0
        @Volatile private var tcpNoDelay = true

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            if (closed.get()) throw SocketException("Socket is closed")
            if (connected) throw SocketException("already connected")
            connectTimeoutMs = minOf(if (timeout > 0) timeout else 10_000, 10_000)
            val request = Request.Builder().url(this@WebSocketTunnelSocketFactory.endpoint).build()
            webSocket = client.newWebSocket(request, TunnelListener())
            try {
                awaitReady()
                openGateway()
                connected = true
            } catch (e: Exception) {
                closeTunnel()
                webSocket?.cancel()
                throw e
            }
        }

        private fun awaitReady() {
            if (!ready.await(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
                webSocket?.cancel()
                throw SocketTimeoutException("DLP/1 connect timed out")
            }
            failure?.let { throw it }
            if (closed.get()) throw SocketException("Socket is closed")
        }

        private fun openGateway() {
            val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
            server.soTimeout = connectTimeoutMs
            serverSocket = server
            Thread({
                try {
                    val peer = server.accept()
                    peer.tcpNoDelay = true
                    peer.soTimeout = 1_000
                    val received = ByteArray(secret.size)
                    var offset = 0
                    while (offset < received.size) {
                        val count = peer.getInputStream().read(received, offset, received.size - offset)
                        if (count < 0) throw IOException("gateway closed before authentication")
                        offset += count
                    }
                    if (!MessageDigest.isEqual(secret, received)) throw IOException("gateway authentication failed")
                    peer.soTimeout = readTimeoutMs
                    gatewayPeer = peer
                    peerReady.countDown()
                    pumpUpstream(peer)
                } catch (e: Exception) {
                    if (!closed.get()) failure = e as? IOException ?: IOException("gateway failed", e)
                    ready.countDown()
                    closeTunnel()
                } finally {
                    runCatching { server.close() }
                }
            }, "dsh-dlp-gateway").apply { isDaemon = true; start() }

            val local = Socket()
            local.tcpNoDelay = tcpNoDelay
            local.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), server.localPort), connectTimeoutMs)
            local.getOutputStream().write(secret)
            local.getOutputStream().flush()
            if (!peerReady.await(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
                local.close()
                throw SocketTimeoutException("local tunnel gateway timed out")
            }
            failure?.let { throw it }
            local.soTimeout = readTimeoutMs
            localSocket = local
        }

        private fun pumpUpstream(peer: Socket) {
            val input = peer.getInputStream()
            val buffer = ByteArray(64 * 1024)
            try {
                while (!closed.get()) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    while (!closed.get() && (webSocket?.queueSize() ?: 0L) > 1024 * 1024) Thread.sleep(5)
                    if (closed.get() || webSocket?.send(buffer.copyOfRange(0, count).toByteString()) != true) break
                }
            } catch (_: Exception) {
            } finally {
                closeTunnel()
            }
        }

        private inner class TunnelListener : WebSocketListener() {
            private var helloSeen = false

            override fun onOpen(webSocket: WebSocket, response: Response) = Unit

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    if (text.toByteArray(Charsets.UTF_8).size > 4 * 1024) throw RouteProtocolException()
                    val message = JSONObject(text)
                    when (message.optString("t")) {
                        "hello" -> {
                            if (helloSeen || message.optInt("v", -1) != DlpCrypto.VERSION) throw RouteProtocolException()
                            helloSeen = true
                            val challenge = decode(message.getString("ch"), 32)
                            val route = this@WebSocketTunnelSocketFactory.routeId
                            val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
                            val timestamp = now() / 1000L + clockOffsetSec
                            val transcript = DlpCrypto.clientTranscript(route, kind, keyId, timestamp, nonce)
                            val mac = DlpCrypto.clientMac(key, transcript)
                            val open = JSONObject()
                                .put("t", "client_open")
                                .put("v", DlpCrypto.VERSION)
                                .put("route", DlpCrypto.base64Url(route))
                                .put("kind", kind)
                                .put("key", DlpCrypto.base64Url(keyId))
                                .put("ts", timestamp)
                                .put("nonce", DlpCrypto.base64Url(nonce))
                                .put("mac", DlpCrypto.base64Url(mac))
                            if (!webSocket.send(open.toString())) throw RouteProtocolException()
                        }
                        "ready" -> {
                            if (!helloSeen) throw RouteProtocolException()
                            ready.countDown()
                        }
                        "error" -> {
                            val code = message.optString("code")
                            failure = mapError(code)
                            ready.countDown()
                            webSocket.close(1000, "")
                        }
                        else -> throw RouteProtocolException()
                    }
                } catch (e: Exception) {
                    failure = e as? IOException ?: RouteProtocolException()
                    ready.countDown()
                    webSocket.close(1000, "")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    if (ready.count != 0L || bytes.size > 256 * 1024) throw RouteProtocolException()
                    if (!peerReady.await(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)) throw RouteOpenTimeoutException()
                    val output = gatewayPeer?.getOutputStream() ?: throw SocketException("gateway closed")
                    // 该写入刻意在 OkHttp 的 WebSocket 读线程中阻塞，形成有界背压。
                    synchronized(output) { output.write(bytes.toByteArray()); output.flush() }
                } catch (e: Exception) {
                    failure = e as? IOException ?: RouteProtocolException()
                    closeTunnel()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (ready.count != 0L) {
                    failure = mapClose(code, reason)
                    ready.countDown()
                    closeTunnel()
                } else {
                    signalRemoteEof()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                signalRemoteEof()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (ready.count != 0L) {
                    failure = t as? IOException ?: RouteProtocolException()
                    ready.countDown()
                }
                closeTunnel()
            }
        }

        private fun closeTunnel() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { serverSocket?.close() }
            runCatching { gatewayPeer?.close() }
            runCatching { localSocket?.close() }
            webSocket?.close(1000, "")
        }

        private fun signalRemoteEof() {
            runCatching { gatewayPeer?.shutdownOutput() }
        }

        override fun getInputStream(): InputStream = localSocket?.getInputStream() ?: throw SocketException("tunnel is not connected")
        override fun getOutputStream(): OutputStream = localSocket?.getOutputStream() ?: throw SocketException("tunnel is not connected")
        override fun close() { closeTunnel(); webSocket?.cancel() }
        override fun isConnected(): Boolean = connected
        override fun isClosed(): Boolean = closed.get()
        override fun isBound(): Boolean = localSocket?.isBound ?: false
        override fun isInputShutdown(): Boolean = localSocket?.isInputShutdown ?: false
        override fun isOutputShutdown(): Boolean = localSocket?.isOutputShutdown ?: false
        override fun getInetAddress(): InetAddress? = localSocket?.inetAddress
        override fun getLocalAddress(): InetAddress = localSocket?.localAddress ?: InetAddress.getByName("0.0.0.0")
        override fun getPort(): Int = localSocket?.port ?: 0
        override fun getLocalPort(): Int = localSocket?.localPort ?: -1
        override fun getRemoteSocketAddress(): SocketAddress? = localSocket?.remoteSocketAddress
        override fun getLocalSocketAddress(): SocketAddress? = localSocket?.localSocketAddress
        override fun setTcpNoDelay(on: Boolean) { tcpNoDelay = on; localSocket?.tcpNoDelay = on }
        override fun getTcpNoDelay(): Boolean = localSocket?.tcpNoDelay ?: tcpNoDelay
        override fun setSoTimeout(timeout: Int) { readTimeoutMs = timeout; localSocket?.soTimeout = timeout }
        override fun getSoTimeout(): Int = localSocket?.soTimeout ?: readTimeoutMs
        override fun shutdownInput() { localSocket?.shutdownInput() ?: throw SocketException("tunnel is not connected") }
        override fun shutdownOutput() { localSocket?.shutdownOutput() ?: throw SocketException("tunnel is not connected") }
    }

    private fun decode(value: String, length: Int): ByteArray = try {
        val bytes = java.util.Base64.getUrlDecoder().decode(value)
        if (bytes.size != length || java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != value) throw RouteProtocolException()
        bytes
    } catch (_: IllegalArgumentException) { throw RouteProtocolException() }

    private fun mapError(code: String): IOException = when (code) {
        "ROUTE_OFFLINE" -> RouteOfflineException()
        "OPEN_TIMEOUT" -> RouteOpenTimeoutException()
        "RATE_LIMITED" -> RouteRateLimitedException()
        "SERVER_BUSY" -> RouteServerBusyException()
        "PROTOCOL_ERROR", "UNSUPPORTED_VERSION", "AUTH_FAILED" -> RouteProtocolException()
        else -> RouteRejectedException(code.ifBlank { "UNKNOWN" })
    }

    private fun mapClose(code: Int, reason: String): IOException = when (reason) {
        "ROUTE_OFFLINE" -> RouteOfflineException()
        "OPEN_TIMEOUT" -> RouteOpenTimeoutException()
        "RATE_LIMITED" -> RouteRateLimitedException()
        "SERVER_BUSY" -> RouteServerBusyException()
        else -> when (code) {
            4003 -> RouteOfflineException()
            4006 -> RouteOpenTimeoutException()
            4004 -> RouteRateLimitedException()
            4005 -> RouteServerBusyException()
            else -> RouteRejectedException(reason.ifBlank { code.toString() })
        }
    }
}
