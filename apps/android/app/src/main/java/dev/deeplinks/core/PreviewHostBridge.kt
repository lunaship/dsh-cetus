package dev.deeplinks.core

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.Socket
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private val DROP_REQUEST = setOf(
    "host", "connection", "upgrade", "content-length", "transfer-encoding",
    "authorization", "x-dsh-link-token", "proxy-connection", "keep-alive",
)

/** 把本地预览请求经 HostHttp 转到插件。设备 token 只加在这一跳。 */
internal fun hostPreviewExchange(host: Host, forward: PreviewForward): PreviewHttpResult {
    val headers = authHeaders(host) + forward.headers.filter { it.first.lowercase(Locale.US) !in DROP_REQUEST }
    val media = forward.headers.firstOrNull { it.first.equals("content-type", true) }?.second
    val response = HostHttp.execute(
        host,
        HostHttp.DshRequest(
            method = forward.method,
            path = forward.pluginPath,
            body = forward.body.takeIf { it.isNotEmpty() },
            headers = headers + listOf("Accept-Encoding" to "identity"),
            readTimeoutMs = 60_000,
            streaming = true,
            bodyMediaType = media,
        ),
    )
    return PreviewHttpResult(
        status = response.code,
        headers = buildList {
            for (i in 0 until response.headers.size) {
                add(response.headers.name(i) to response.headers.value(i))
            }
        },
        body = response.body.byteStream(),
        contentLength = response.body.contentLength().takeIf { it >= 0 },
    )
}

/** 本地 WebSocket 与插件上的预览 WebSocket 互转文本和二进制帧。 */
internal fun hostPreviewWebSocket(host: Host, forward: PreviewForward, client: Socket, accept: () -> Unit) {
    val ready = CountDownLatch(1)
    var failed = false
    lateinit var upstream: WebSocket
    val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            upstream = webSocket
            ready.countDown()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            writeServerFrame(client.getOutputStream(), 0x1, text.toByteArray(Charsets.UTF_8))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            writeServerFrame(client.getOutputStream(), 0x2, bytes.toByteArray())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            try { client.close() } catch (_: Exception) {}
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failed = true
            ready.countDown()
            try { client.close() } catch (_: Exception) {}
        }
    }
    val headers = authHeaders(host) + forward.headers.filter { it.first.lowercase(Locale.US) !in DROP_REQUEST }
    val socket = HostHttp.openWebSocket(host, forward.pluginPath, headers, listener)
    if (!ready.await(20, TimeUnit.SECONDS) || failed) {
        socket.cancel()
        return
    }
    accept()
    val input = client.getInputStream()
    while (true) {
        val frame = readClientFrame(input) ?: break
        when (frame.opcode) {
            0x1 -> upstream.send(frame.payload.toString(Charsets.UTF_8))
            0x2 -> upstream.send(frame.payload.toByteString())
            0x8 -> {
                upstream.close(1000, null)
                break
            }
            0x9 -> writeServerFrame(client.getOutputStream(), 0xA, frame.payload)
        }
    }
}

private fun authHeaders(host: Host): List<Pair<String, String>> = buildList {
    add("x-dsh-link-token" to host.token)
    if (host.token.isNotBlank()) add("Authorization" to "Bearer ${host.token}")
}
