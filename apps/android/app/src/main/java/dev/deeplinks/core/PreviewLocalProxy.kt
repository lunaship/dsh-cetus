package dev.deeplinks.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 手机上的本地预览代理。只听 127.0.0.1，路径必须带随机密钥。
 * 真正连电脑的部分由调用方经 HostHttp 完成。
 */
internal class PreviewLocalProxy(
    private val exchange: (PreviewForward) -> PreviewHttpResult,
    private val webSocket: (PreviewForward, Socket, (String?) -> Unit) -> Unit,
) {
    val key: String = randomKey()
    private val server = ServerSocket()
    @Volatile private var closed = false
    private val clients = ConcurrentHashMap.newKeySet<Socket>()

    val port: Int get() = server.localPort
    val bindAddress: InetAddress get() = server.inetAddress

    fun start() {
        try {
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        } catch (e: Exception) {
            close()
            throw e
        }
        val thread = Thread({
            while (!closed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                clients.add(socket)
                if (closed) {
                    clients.remove(socket)
                    try { socket.close() } catch (_: Exception) {}
                    break
                }
                Thread({
                    try {
                        handle(socket)
                    } catch (_: Exception) {
                    } finally {
                        clients.remove(socket)
                        try { socket.close() } catch (_: Exception) {}
                    }
                }, "dsh-preview").apply { isDaemon = true }.start()
            }
        }, "dsh-preview-accept")
        thread.isDaemon = true
        thread.start()
    }

    /** 关掉监听和所有客户端 socket。可重复调用。 */
    fun close() {
        closed = true
        try { server.close() } catch (_: Exception) {}
        for (socket in clients.toList()) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun localUrl(previewId: String): String = "http://127.0.0.1:$port/$key/$previewId/"

    private fun handle(socket: Socket) {
        socket.soTimeout = 60_000
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val head = readHead(input) ?: return
        val request = parseHead(head) ?: run {
            writeStatus(output, 400, "bad request")
            return
        }
        val pluginPath = mapPreviewPath(key, request.path)
        if (pluginPath == null) {
            writeStatus(output, 404, "not found")
            return
        }
        val forward = PreviewForward(
            method = request.method,
            pluginPath = pluginPath,
            headers = request.headers,
            body = if (request.contentLength > 0) readBody(input, request.contentLength) else ByteArray(0),
        )
        if (request.websocket) {
            val wsKey = request.webSocketKey
            if (wsKey == null || !isValidWebSocketKey(wsKey)) {
                writeStatus(output, 400, "bad request")
                return
            }
            socket.soTimeout = 0
            var accepted = false
            try {
                webSocket(forward, socket) { selected ->
                    if (!accepted) {
                        writeSwitching(output, wsKey, selected)
                        accepted = true
                    }
                }
            } catch (_: Exception) {
                // 上游失败时如果还没 101，就回 502。
            }
            if (!accepted) {
                try { writeStatus(output, 502, "bad gateway") } catch (_: Exception) {}
            }
            return
        }
        val result = try {
            exchange(forward)
        } catch (_: Exception) {
            writeStatus(output, 502, "bad gateway")
            return
        }
        result.use { writeResult(output, it) }
    }
}

internal data class PreviewForward(
    val method: String,
    val pluginPath: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
)

internal class PreviewHttpResult(
    val status: Int,
    val headers: List<Pair<String, String>>,
    val body: InputStream,
    val contentLength: Long? = null,
) : AutoCloseable {
    override fun close() {
        try { body.close() } catch (_: Exception) {}
    }
}

internal data class WsFrame(val opcode: Int, val payload: ByteArray, val fin: Boolean = true)

internal class WsProtocolException(val code: Int) : IOException("websocket protocol $code")

/** 同一个客户端 socket 上的全部写操作共用这一把锁。 */
internal class FrameWriter(private val output: OutputStream) {
    val lock = Any()

    fun write(opcode: Int, payload: ByteArray) {
        synchronized(lock) {
            writeServerFrame(output, opcode, payload)
        }
    }

    fun writeClose(code: Int) {
        val wire = wireCloseCode(code)
        val payload = byteArrayOf((wire shr 8).toByte(), (wire and 0xff).toByte())
        write(0x8, payload)
    }
}

private data class ParsedRequest(
    val method: String,
    val path: String,
    val headers: List<Pair<String, String>>,
    val contentLength: Int,
    val websocket: Boolean,
    val webSocketKey: String?,
)

private const val MAX_HEAD = 64 * 1024
private const val MAX_BODY = 8 * 1024 * 1024
internal const val MAX_FRAME = 1 * 1024 * 1024
private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

internal fun webSocketAccept(key: String): String {
    val digest = MessageDigest.getInstance("SHA-1")
        .digest((key + WS_GUID).toByteArray(Charsets.ISO_8859_1))
    return Base64.getEncoder().encodeToString(digest)
}

internal fun isValidWebSocketKey(key: String): Boolean = try {
    Base64.getDecoder().decode(key).size == 16
} catch (_: IllegalArgumentException) {
    false
}

internal fun webSocketProtocols(headers: List<Pair<String, String>>): List<String> =
    headers.asSequence()
        .filter { it.first.equals("Sec-WebSocket-Protocol", ignoreCase = true) }
        .flatMap { it.second.split(',').asSequence() }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()

internal fun wireCloseCode(code: Int): Int = when (code) {
    in 1000..1003, in 1007..1014, in 3000..4999 -> code
    else -> 1000
}

internal fun mapPreviewPath(key: String, pathAndQuery: String): String? {
    if (pathAndQuery.startsWith("http://") || pathAndQuery.startsWith("https://")) return null
    val cut = pathAndQuery.indexOf('?')
    val path = if (cut >= 0) pathAndQuery.substring(0, cut) else pathAndQuery
    val query = if (cut >= 0) pathAndQuery.substring(cut) else ""
    val parts = path.split('/').filter { it.isNotEmpty() }
    // 恒定时间比较：不让逐字节的提前返回泄露 key 的前缀
    if (parts.size < 2 || !MessageDigest.isEqual(parts[0].toByteArray(), key.toByteArray())) return null
    val id = parts[1]
    if (!Regex("^[a-f0-9]{24}$").matches(id)) return null
    if (parts.drop(2).any { it == "." || it == ".." }) return null
    val rest = parts.drop(2).joinToString("/")
    val suffix = if (rest.isEmpty()) "/" else "/$rest"
    return "/dsh-link/mobile/preview/$id$suffix$query"
}

/**
 * 读一帧客户端数据。超过 [MAX_FRAME] 时写 close 1009；没有 mask、RSV 或保留 opcode 时写 close 1002。
 * 帧边界上的 EOF 返回 null。
 */
internal fun readClientFrame(
    input: InputStream,
    writer: FrameWriter? = null,
    requireMask: Boolean = true,
): WsFrame? {
    val b0 = input.read()
    if (b0 < 0) return null
    val b1 = input.read()
    if (b1 < 0) throw IOException("truncated websocket frame")
    if ((b0 and 0x70) != 0) reject(writer, 1002)
    val fin = (b0 and 0x80) != 0
    val opcode = b0 and 0x0f
    if (opcode !in 0x0..0x2 && opcode != 0x8 && opcode != 0x9 && opcode != 0xA) reject(writer, 1002)
    val masked = (b1 and 0x80) != 0
    if (requireMask && !masked) reject(writer, 1002)
    if (!requireMask && masked) reject(writer, 1002)
    val lenCode = b1 and 0x7f
    val length = when (lenCode) {
        126 -> {
            val hi = input.read()
            val lo = input.read()
            if (hi < 0 || lo < 0) throw IOException("truncated websocket frame")
            (hi shl 8) or lo
        }
        127 -> read64BitLength(input, writer)
        else -> lenCode
    }
    if (lenCode == 126 && length < 126) reject(writer, 1002)
    val control = opcode >= 0x8
    if (control && (!fin || length > 125)) reject(writer, 1002)
    if (length > MAX_FRAME) reject(writer, 1009)
    val mask = ByteArray(4)
    if (masked && !readFully(input, mask)) throw IOException("truncated websocket frame")
    val payload = ByteArray(length)
    if (length > 0 && !readFully(input, payload)) throw IOException("truncated websocket frame")
    if (masked) {
        for (i in payload.indices) {
            payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
    }
    return WsFrame(opcode, payload, fin)
}

/** 重组 FIN=0 的数据帧。控制帧（8/9/A）可以夹在分片之间，会先于未完成的消息返回。 */
internal class ClientMessageReader(
    private val input: InputStream,
    private val writer: FrameWriter,
) {
    private val pending = ByteArrayOutputStream()
    private var messageOpcode = -1
    private var total = 0

    fun next(): WsFrame? {
        while (true) {
            val frame = readClientFrame(input, writer) ?: return unfinishedOrEof()
            if (frame.opcode >= 0x8) return frame
            when (frame.opcode) {
                0x1, 0x2 -> {
                    if (messageOpcode != -1) fail(1002)
                    messageOpcode = frame.opcode
                    append(frame.payload)
                    if (frame.fin) return finish()
                }
                0x0 -> {
                    if (messageOpcode == -1) fail(1002)
                    append(frame.payload)
                    if (frame.fin) return finish()
                }
                else -> fail(1002)
            }
        }
    }

    private fun append(payload: ByteArray) {
        total += payload.size
        if (total > MAX_FRAME) fail(1009)
        if (payload.isNotEmpty()) pending.write(payload)
    }

    private fun finish(): WsFrame {
        val opcode = messageOpcode
        val payload = pending.toByteArray()
        pending.reset()
        messageOpcode = -1
        total = 0
        return WsFrame(opcode, payload, fin = true)
    }

    private fun unfinishedOrEof(): WsFrame? {
        if (messageOpcode != -1) throw IOException("truncated websocket message")
        return null
    }

    private fun fail(code: Int): Nothing = reject(writer, code)
}

internal fun writeServerFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
    val head = ByteArrayOutputStream()
    head.write(0x80 or opcode)
    val size = payload.size
    when {
        size < 126 -> head.write(size)
        size <= 0xffff -> {
            head.write(126)
            head.write(size shr 8)
            head.write(size and 0xff)
        }
        else -> {
            head.write(127)
            val n = size.toLong()
            for (shift in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) {
                head.write(((n shr shift) and 0xff).toInt())
            }
        }
    }
    output.write(head.toByteArray())
    if (payload.isNotEmpty()) output.write(payload)
    output.flush()
}

private fun read64BitLength(input: InputStream, writer: FrameWriter?): Int {
    var n = 0L
    repeat(8) {
        val b = input.read()
        if (b < 0) throw IOException("truncated websocket frame")
        n = (n shl 8) or b.toLong()
    }
    if (n < 0) reject(writer, 1002)
    if (n <= 0xffff) reject(writer, 1002)
    if (n > MAX_FRAME) reject(writer, 1009)
    return n.toInt()
}

private fun reject(writer: FrameWriter?, code: Int): Nothing {
    try { writer?.writeClose(code) } catch (_: Exception) {}
    throw WsProtocolException(code)
}

private fun readFully(input: InputStream, buf: ByteArray): Boolean {
    var off = 0
    while (off < buf.size) {
        val n = input.read(buf, off, buf.size - off)
        if (n < 0) return false
        off += n
    }
    return true
}

private fun randomKey(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun readHead(input: InputStream): String? {
    val out = ByteArrayOutputStream()
    var matched = 0
    while (out.size() < MAX_HEAD) {
        val b = input.read()
        if (b < 0) return null
        out.write(b)
        matched = when {
            matched == 0 && b == '\r'.code -> 1
            matched == 1 && b == '\n'.code -> 2
            matched == 2 && b == '\r'.code -> 3
            matched == 3 && b == '\n'.code -> return String(out.toByteArray(), Charsets.ISO_8859_1)
            else -> if (b == '\r'.code) 1 else 0
        }
    }
    return null
}

private fun parseHead(head: String): ParsedRequest? {
    val lines = head.split("\r\n")
    val request = lines.firstOrNull()?.split(" ") ?: return null
    if (request.size < 2) return null
    val headers = mutableListOf<Pair<String, String>>()
    for (line in lines.drop(1)) {
        if (line.isEmpty()) continue
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        headers += line.substring(0, colon).trim() to line.substring(colon + 1).trim()
    }
    val length = headers.firstOrNull { it.first.equals("content-length", true) }?.second?.toIntOrNull() ?: 0
    if (length !in 0..MAX_BODY) return null
    val upgrade = headers.any { it.first.equals("upgrade", true) && it.second.equals("websocket", true) }
    val wsKey = headers.firstOrNull { it.first.equals("Sec-WebSocket-Key", true) }?.second
    return ParsedRequest(request[0], request[1], headers, length, upgrade, wsKey)
}

private fun readBody(input: InputStream, length: Int): ByteArray {
    val buf = ByteArray(length)
    if (!readFully(input, buf)) return ByteArray(0)
    return buf
}

private fun writeStatus(output: OutputStream, code: Int, reason: String) {
    val body = reason.toByteArray()
    val text = "HTTP/1.1 $code $reason\r\ncontent-type: text/plain\r\ncontent-length: ${body.size}\r\nconnection: close\r\n\r\n"
    output.write(text.toByteArray(Charsets.ISO_8859_1))
    output.write(body)
    output.flush()
}

private fun writeSwitching(output: OutputStream, key: String, protocol: String?) {
    val protocolLine = if (protocol.isNullOrEmpty()) "" else "Sec-WebSocket-Protocol: $protocol\r\n"
    val text = "HTTP/1.1 101 Switching Protocols\r\n" +
        "Upgrade: websocket\r\n" +
        "Connection: Upgrade\r\n" +
        "Sec-WebSocket-Accept: ${webSocketAccept(key)}\r\n" +
        protocolLine +
        "\r\n"
    output.write(text.toByteArray(Charsets.ISO_8859_1))
    output.flush()
}

private fun writeResult(output: OutputStream, result: PreviewHttpResult) {
    val skip = setOf("connection", "keep-alive", "transfer-encoding", "upgrade", "proxy-connection")
    val lines = StringBuilder("HTTP/1.1 ${result.status} OK\r\n")
    for ((name, value) in result.headers) {
        if (name.lowercase() in skip) continue
        lines.append(name).append(": ").append(value).append("\r\n")
    }
    lines.append("connection: close\r\n\r\n")
    output.write(lines.toString().toByteArray(Charsets.ISO_8859_1))
    result.body.copyTo(output)
    output.flush()
}
