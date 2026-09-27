package dev.deeplinks.native

import dev.deeplinks.core.ByteCodec
import java.io.File
import java.security.MessageDigest

/**
 * 会话尾页 history 的本地快照：打开会话先显示上次的内容，网络结果回来再整体接管（lody 的「本地投影秒开」）。
 *
 * - 只存尾页原始响应（服务端 JSON），读回时用同一个 [parseHistoryResponse] 解析，不另维护序列化格式。
 * - 按主机分目录、按会话一个文件；文件名是哈希，不暴露会话 id。落盘前经 [codec] 加密。
 * - 上限：单文件 [MAX_BYTES]，每台主机保留最近访问的 [MAX_SESSIONS] 个会话；超出即丢，缓存可随时重建。
 * - 快照只用于显示：待处理的审批 / 提问降为「状态待确认」，进行中标记清掉（见 [sanitizeCachedMessages]）。
 */
internal class SessionHistoryCache(
    root: File,
    slotKey: String,
    private val codec: ByteCodec,
) {
    private val dir = File(root, hostDirName(slotKey))

    /** 同一内容不重复写（history 轮询很频繁）。 */
    private val lastWritten = HashMap<String, Int>()

    fun read(sessionId: String): HistoryResult? {
        val file = fileFor(sessionId)
        if (!file.isFile) return null
        return try {
            val plain = codec.decode(file.readBytes()) ?: return null.also { file.delete() }
            file.setLastModified(System.currentTimeMillis())
            val parsed = parseHistoryResponse(org.json.JSONObject(String(plain, Charsets.UTF_8)), beforeSeq = null)
            parsed.copy(messages = sanitizeCachedMessages(parsed.messages))
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    @Synchronized
    fun write(sessionId: String, rawJson: String) {
        val bytes = rawJson.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) {
            remove(sessionId)
            return
        }
        val hash = rawJson.hashCode()
        if (lastWritten[sessionId] == hash) return
        try {
            dir.mkdirs()
            val target = fileFor(sessionId)
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeBytes(codec.encode(bytes))
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return
            }
            lastWritten[sessionId] = hash
            prune()
        } catch (_: Exception) {
            // 缓存写失败不影响主流程
        }
    }

    @Synchronized
    fun remove(sessionId: String) {
        lastWritten.remove(sessionId)
        fileFor(sessionId).delete()
    }

    @Synchronized
    fun clear() {
        lastWritten.clear()
        dir.deleteRecursively()
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) } ?: return
        if (files.size <= MAX_SESSIONS) return
        files.sortedByDescending { it.lastModified() }.drop(MAX_SESSIONS).forEach { it.delete() }
    }

    private fun fileFor(sessionId: String) = File(dir, sha256(sessionId).take(32) + SUFFIX)

    companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        const val MAX_SESSIONS = 30
        private const val SUFFIX = ".hist"
        private const val DIR = "session-history"

        fun rootDir(cacheDir: File) = File(cacheDir, DIR)

        fun hostDirName(slotKey: String) = sha256(slotKey).take(24)

        /** 解除配对时清掉该主机的全部快照。 */
        fun clearHost(cacheDir: File, slotKey: String) {
            File(rootDir(cacheDir), hostDirName(slotKey)).deleteRecursively()
        }

        private fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * 快照里的消息只作显示：
 * - 未结束的审批 / 提问 → [REQUEST_UNKNOWN]（卡片显示「状态待确认」且不可提交，等网络结果接管）；
 * - 进行中标记清掉，不显示转圈 / 光标；不播入场动画。
 */
internal fun sanitizeCachedMessages(messages: List<MobileMessage>): List<MobileMessage> = messages.map { msg ->
    val pendingRequest = (msg.role == "approval" || msg.role == "question") &&
        !isTerminalRequestStatus(msg.requestStatus)
    if (!pendingRequest && msg.running != true && !msg.entrance) {
        msg
    } else {
        msg.copy(
            requestStatus = if (pendingRequest) REQUEST_UNKNOWN else msg.requestStatus,
            running = if (msg.running == true) false else msg.running,
            entrance = false,
        )
    }
}

/** 快照显示期间 SSE 追加的消息要保留；网络 history 回来时只替换掉快照本身的那部分。 */
internal fun liveAfterCache(messages: List<MobileMessage>, cachedIds: Set<String>): List<MobileMessage> =
    if (cachedIds.isEmpty()) messages else messages.filter { it.id !in cachedIds }
