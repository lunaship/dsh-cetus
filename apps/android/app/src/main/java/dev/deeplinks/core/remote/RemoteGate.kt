package dev.deeplinks.core.remote

import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 远程短请求并发闸门：同一台电脑同时最多 [PERMITS] 个远程请求在途，其余排队复用连接池里释放出来的隧道。
 * 长连接（SSE）不经过这里，否则会一直占着名额。
 *
 * 与 R3 的单设备并发流上限（12）对齐：3 个空闲 + 4 个在途 + 2 条 SSE ≤ 12。
 */
object RemoteGate {
    const val PERMITS = 4

    private val gates = ConcurrentHashMap<String, Semaphore>()

    fun acquire(key: String, timeoutMs: Long): Boolean =
        gates.computeIfAbsent(key) { Semaphore(PERMITS, true) }.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)

    fun release(key: String) {
        gates[key]?.release()
    }
}

/**
 * 包一层响应体：在 body / source 关闭时归还 [onClose] 对应的远程名额（只会归还一次）。
 */
internal class ReleasingBody(
    private val original: ResponseBody,
    private val onClose: () -> Unit,
) : ResponseBody() {

    private val released = AtomicBoolean(false)
    private val buffered: BufferedSource = object : ForwardingSource(original.source()) {
        override fun close() {
            try {
                super.close()
            } finally {
                release()
            }
        }
    }.buffer()

    private fun release() {
        if (released.compareAndSet(false, true)) onClose()
    }

    override fun contentType(): MediaType? = original.contentType()

    override fun contentLength(): Long = original.contentLength()

    override fun source(): BufferedSource = buffered

    override fun close() {
        buffered.close()
    }
}
