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
import java.util.concurrent.atomic.AtomicBoolean

private val DROP_REQUEST = setOf(
    "host", "connection", "upgrade", "content-length", "transfer-encoding",
    "authorization", "x-dsh-link-token", "proxy-connection", "keep-alive",
    "sec-websocket-key", "sec-websocket-version", "sec-websocket-extensions",
)

/** 转发给上游的请求头。WebSocket 的 key / version / extensions 留给 OkHttp 自己生成，协议名保留。 */
internal fun previewUpstreamHeaders(headers: List<Pair<String, String>>): List<Pair<String, String>> {
    val kept = headers.filter { it.first.lowercase(Locale.US) !in DROP_REQUEST }
    val protocols = webSocketProtocols(kept)
    if (protocols.isEmpty()) return kept
    val rest = kept.filter { !it.first.equals("Sec-WebSocket-Protocol", ignoreCase = true) }
    return rest + ("Sec-WebSocket-Protocol" to protocols.joinToString(", "))
}

/** 把本地预览请求经 HostHttp 转到插件。设备 token 只加在这一跳。 */
internal fun hostPreviewExchange(host: Host, forward: PreviewForward): PreviewHttpResult {
    val headers = authHeaders(host) + previewUpstreamHeaders(forward.headers)
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
internal fun hostPreviewWebSocket(
    host: Host,
    forward: PreviewForward,
    client: Socket,
    accept: (String?) -> Unit,
) {
    relayPreviewWebSocket(client, forward, accept) { headers, listener ->
        HostHttp.openWebSocket(host, forward.pluginPath, authHeaders(host) + headers, listener)
    }
}

/**
 * 把已经连上的本地客户端 socket 接到上游 WebSocket。
 * [accept] 会在持有客户端写锁时调用，参数是上游选中的子协议；不在客户端请求列表里则不调用，调用方回 502。
 */
internal fun relayPreviewWebSocket(
    client: Socket,
    forward: PreviewForward,
    accept: (String?) -> Unit,
    openUpstream: (headers: List<Pair<String, String>>, listener: WebSocketListener) -> WebSocket,
) {
    val writer = FrameWriter(client.getOutputStream())
    val ready = CountDownLatch(1)
    val handshakeFinished = AtomicBoolean(false)
    var handshakeFailed = false
    val locallyClosed = AtomicBoolean(false)
    val clientCloseSent = AtomicBoolean(false)
    val acceptedHandshake = AtomicBoolean(false)
    val requested = webSocketProtocols(forward.headers)
    val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val selected = response.header("Sec-WebSocket-Protocol")?.trim()?.takeIf { it.isNotEmpty() }
            if (selected != null && selected !in requested) {
                handshakeFailed = true
            } else {
                try {
                    synchronized(writer.lock) { accept(selected) }
                    acceptedHandshake.set(true)
                } catch (_: Exception) {
                    handshakeFailed = true
                }
            }
            handshakeFinished.set(true)
            ready.countDown()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                writer.write(0x1, text.toByteArray(Charsets.UTF_8))
            } catch (_: Exception) {
                try { client.close() } catch (_: Exception) {}
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                writer.write(0x2, bytes.toByteArray())
            } catch (_: Exception) {
                try { client.close() } catch (_: Exception) {}
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closeClient(code)
            try { webSocket.close(code, null) } catch (_: Exception) {}
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closeClient(code)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (handshakeFinished.compareAndSet(false, true)) {
                handshakeFailed = true
                ready.countDown()
            }
            // 握手还没 101 时把 socket 留给调用方写 502。
            if (acceptedHandshake.get()) {
                try { client.close() } catch (_: Exception) {}
            }
        }

        private fun closeClient(code: Int) {
            locallyClosed.set(true)
            if (clientCloseSent.compareAndSet(false, true)) {
                try { writer.writeClose(code) } catch (_: Exception) {}
            }
            try { client.close() } catch (_: Exception) {}
        }
    }
    val upstream = openUpstream(previewUpstreamHeaders(forward.headers), listener)
    try {
        if (!ready.await(20, TimeUnit.SECONDS) || handshakeFailed) {
            upstream.cancel()
            return
        }
    } catch (_: InterruptedException) {
        upstream.cancel()
        Thread.currentThread().interrupt()
        return
    }
    var readFailed = false
    try {
        val reader = ClientMessageReader(client.getInputStream(), writer)
        while (true) {
            val frame = reader.next() ?: break
            when (frame.opcode) {
                0x1 -> upstream.send(frame.payload.toString(Charsets.UTF_8))
                0x2 -> upstream.send(frame.payload.toByteString())
                0x8 -> {
                    upstream.close(1000, null)
                    break
                }
                0x9 -> writer.write(0xA, frame.payload)
                0xA -> Unit
            }
        }
    } catch (_: Exception) {
        if (!locallyClosed.get()) readFailed = true
    } finally {
        try {
            if (readFailed) upstream.cancel() else upstream.close(1000, null)
        } catch (_: Exception) {
        }
    }
}

private fun authHeaders(host: Host): List<Pair<String, String>> = buildList {
    add("x-dsh-link-token" to host.token)
    if (host.token.isNotBlank()) add("Authorization" to "Bearer ${host.token}")
}
