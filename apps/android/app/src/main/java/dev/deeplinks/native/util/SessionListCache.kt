package dev.deeplinks.native.util

import dev.deeplinks.core.ByteCodec
import dev.deeplinks.native.MobileSession
import dev.deeplinks.native.MobileSessionActivity
import dev.deeplinks.native.MobileSessionResult
import dev.deeplinks.native.MobileWorkspace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 首页会话列表的本地缓存（第三轮 S1）。
 *
 * 冷启动时首页不再干等 `bootstrap`（用户有 400+ 会话、约 113KB）回来才显示：
 * 先铺上次的列表与工作区目录，网络回来再整体替换。复用 [dev.deeplinks.core.LocalCacheCrypto] 加密落盘，
 * 按 `host.slotKey` 分目录；只保留最近 [MAX_SESSIONS] 个会话。
 */
internal data class SessionListSnapshot(
    val sessions: List<MobileSession>,
    val archivedSessionIds: Set<String>,
    val workspaces: List<MobileWorkspace>,
    val writtenAt: Long = 0L,
)

internal class SessionListCache(
    root: File,
    slotKey: String,
    private val codec: ByteCodec,
) {
    private val dir = File(root, hostDirName(slotKey))
    private val file = File(dir, FILE_NAME)

    @Synchronized
    fun read(): SessionListSnapshot? {
        if (!file.isFile) return null
        return try {
            val plain = codec.decode(file.readBytes())
            if (plain == null) {
                file.delete()
                return null
            }
            val snapshot = decodeSessionListSnapshot(String(plain, Charsets.UTF_8))
            if (snapshot == null) {
                file.delete()
                return null
            }
            snapshot
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    @Synchronized
    fun write(snapshot: SessionListSnapshot) {
        try {
            dir.mkdirs()
            val capped = snapshot.copy(
                sessions = capSessions(snapshot.sessions),
                writtenAt = System.currentTimeMillis(),
            )
            val bytes = encodeSessionListSnapshot(capped).toByteArray(Charsets.UTF_8)
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeBytes(codec.encode(bytes))
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (_: Exception) {
            // 缓存写失败不影响主流程
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    companion object {
        const val VERSION = 1
        const val MAX_SESSIONS = 200
        private const val DIR = "session-list"
        private const val FILE_NAME = "list.json.cache"

        fun rootDir(cacheDir: File) = File(cacheDir, DIR)

        fun hostDirName(slotKey: String) = sha256(slotKey).take(24)

        /** 解除配对时清掉该主机的列表缓存。 */
        fun clearHost(cacheDir: File, slotKey: String) {
            File(rootDir(cacheDir), hostDirName(slotKey)).deleteRecursively()
        }

        private fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/** 最近 [SessionListCache.MAX_SESSIONS] 个会话，按更新时间倒序。 */
internal fun capSessions(sessions: List<MobileSession>): List<MobileSession> =
    sessions.sortedByDescending { sessionMillis(it.updatedAt) }.take(SessionListCache.MAX_SESSIONS)

// ---------- 序列化（纯函数，可在 JVM 单测直接跑） ----------

internal fun encodeSessionListSnapshot(snapshot: SessionListSnapshot): String {
    val root = JSONObject()
    root.put("version", SessionListCache.VERSION)
    root.put("writtenAt", snapshot.writtenAt)
    root.put("archived", JSONArray().also { arr -> snapshot.archivedSessionIds.forEach { arr.put(it) } })
    root.put("sessions", JSONArray().also { arr -> snapshot.sessions.forEach { arr.put(sessionToJson(it)) } })
    root.put("workspaces", JSONArray().also { arr -> snapshot.workspaces.forEach { arr.put(workspaceToJson(it)) } })
    return root.toString()
}

internal fun decodeSessionListSnapshot(text: String): SessionListSnapshot? {
    val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
    if (root.optInt("version", -1) != SessionListCache.VERSION) return null
    val sessions = root.optJSONArray("sessions")?.let { arr ->
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::sessionFromJson) }
    } ?: emptyList()
    val workspaces = root.optJSONArray("workspaces")?.let { arr ->
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::workspaceFromJson) }
    } ?: emptyList()
    val archived = root.optJSONArray("archived")?.let { arr ->
        (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }.toSet()
    } ?: emptySet()
    return SessionListSnapshot(
        sessions = sessions,
        archivedSessionIds = archived,
        workspaces = workspaces,
        writtenAt = root.optLong("writtenAt", 0L),
    )
}

private fun sessionToJson(s: MobileSession): JSONObject = JSONObject()
    .put("sessionId", s.sessionId)
    .put("title", s.title)
    .put("updatedAt", s.updatedAt)
    .put("running", s.running)
    .put("blank", s.blank)
    .put("cwd", s.cwd ?: JSONObject.NULL)
    .put("agentPreset", s.agentPreset ?: JSONObject.NULL)
    .put("origin", s.origin ?: JSONObject.NULL)
    .put("parentSessionId", s.parentSessionId ?: JSONObject.NULL)
    .put("subagentCount", s.subagentCount ?: JSONObject.NULL)
    .put("awaitingInput", s.awaitingInput)
    .put("stoppedReason", s.stoppedReason ?: JSONObject.NULL)
    .put(
        "activity",
        s.activity?.let {
            JSONObject()
                .put("kind", it.kind)
                .put("label", it.label ?: JSONObject.NULL)
                .put("step", it.step ?: JSONObject.NULL)
                .put("startedAt", it.startedAt ?: JSONObject.NULL)
        } ?: JSONObject.NULL,
    )
    .put(
        "lastResult",
        s.lastResult?.let {
            JSONObject()
                .put("text", it.text ?: JSONObject.NULL)
                .put("files", it.files ?: JSONObject.NULL)
                .put("added", it.added ?: JSONObject.NULL)
                .put("deleted", it.deleted ?: JSONObject.NULL)
        } ?: JSONObject.NULL,
    )

private fun sessionFromJson(o: JSONObject): MobileSession = MobileSession(
    sessionId = o.optString("sessionId"),
    title = o.optString("title"),
    updatedAt = o.optLong("updatedAt"),
    running = o.optBoolean("running"),
    blank = o.optBoolean("blank"),
    cwd = o.nullableString("cwd"),
    agentPreset = o.nullableString("agentPreset"),
    origin = o.nullableString("origin"),
    parentSessionId = o.nullableString("parentSessionId"),
    subagentCount = o.nullableInt("subagentCount"),
    awaitingInput = o.optBoolean("awaitingInput"),
    activity = o.optJSONObject("activity")?.let {
        MobileSessionActivity(
            kind = it.optString("kind"),
            label = it.nullableString("label"),
            step = it.nullableLong("step"),
            startedAt = it.nullableLong("startedAt"),
        )
    },
    lastResult = o.optJSONObject("lastResult")?.let {
        MobileSessionResult(
            text = it.nullableString("text"),
            files = it.nullableLong("files"),
            added = it.nullableLong("added"),
            deleted = it.nullableLong("deleted"),
        )
    },
    stoppedReason = o.nullableString("stoppedReason"),
)

private fun workspaceToJson(w: MobileWorkspace): JSONObject = JSONObject()
    .put("workspaceId", w.workspaceId)
    .put("path", w.path)
    .put("title", w.title)
    .put("sessionIds", JSONArray().also { arr -> w.sessionIds.forEach { arr.put(it) } })

private fun workspaceFromJson(o: JSONObject): MobileWorkspace = MobileWorkspace(
    workspaceId = o.optString("workspaceId"),
    path = o.optString("path"),
    title = o.optString("title"),
    sessionIds = o.optJSONArray("sessionIds")?.let { arr ->
        (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
    } ?: emptyList(),
)

private fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONObject.nullableInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

private fun JSONObject.nullableLong(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)
