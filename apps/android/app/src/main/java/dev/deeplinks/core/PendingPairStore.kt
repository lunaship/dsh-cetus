package dev.deeplinks.core

import android.content.Context
import dev.deeplinks.core.remote.HostRoute
import org.json.JSONObject
import java.util.UUID

/** 单个候选设备与正式 HostStore 分开存放；整个记录使用 TokenCrypto 加密。 */
object PendingPairStore {
    private const val PREFS = "dsh_pending_pair"
    private const val KEY = "pending"

    private fun repository(ctx: Context): PendingPairRepository {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return PendingPairRepository(
            read = {
                prefs.getString(KEY, null)?.let {
                    TokenCrypto.decrypt(ctx, it) ?: error("Unreadable pending credentials")
                }
            },
            write = { plain ->
                val editor = prefs.edit()
                if (plain == null) editor.remove(KEY) else editor.putString(KEY, TokenCrypto.encrypt(ctx, plain))
                editor.commit()
            },
        )
    }

    @Synchronized fun abort(ctx: Context, attemptId: String): Boolean = repository(ctx).abort(attemptId)
    @Synchronized fun complete(ctx: Context, attemptId: String, host: Host): Boolean =
        repository(ctx).complete(attemptId, host) { HostStore.upsert(ctx, it) }
    @Synchronized fun begin(ctx: Context): String? = repository(ctx).begin()
    @Synchronized fun save(ctx: Context, session: PairingSession): Boolean = repository(ctx).save(session)
    @Synchronized fun loadAny(ctx: Context): PairingSession? = runCatching { repository(ctx).load() }.getOrNull()
    @Synchronized fun isUnreadable(ctx: Context): Boolean = runCatching { repository(ctx).load() }.isFailure
    @Synchronized fun isCurrent(ctx: Context, attemptId: String): Boolean = repository(ctx).isCurrent(attemptId)
    @Synchronized fun remove(ctx: Context, attemptId: String, deviceId: String): Boolean = repository(ctx).remove(attemptId, deviceId)
    @Synchronized fun pause(ctx: Context, session: PairingSession, failure: PairFailure): Boolean =
        repository(ctx).save(session.copy(paused = true, pauseReason = failure.code))
    @Synchronized fun resume(ctx: Context, session: PairingSession): Boolean = repository(ctx).save(session.copy(paused = false))

    /** 锁覆盖身份核对和晋升；保存失败不清候选，旧异步结果不能覆盖新配对。 */
    @Synchronized fun promote(ctx: Context, session: PairingSession): Boolean =
        repository(ctx).promote(session) { HostStore.upsert(ctx, it) }

    /** 处理晋升成功、清候选前进程退出的情况。 */
    @Synchronized fun reconcile(ctx: Context, current: Host?): PairingSession? =
        runCatching { repository(ctx).reconcile(current) }.getOrNull()
}

/** 存储事务的纯 Kotlin 部分，生产与测试共享身份核对和写入顺序。 */
internal class PendingPairRepository(
    private val read: () -> String?,
    private val write: (String?) -> Boolean,
) {
    private fun root(): JSONObject = read()?.let(::JSONObject) ?: JSONObject()

    fun begin(): String? = runCatching {
        val attempt = UUID.randomUUID().toString()
        if (write(JSONObject().put("attemptId", attempt).toString())) attempt else null
    }.getOrNull()

    fun isCurrent(attemptId: String): Boolean = runCatching {
        attemptId.isNotBlank() && root().optString("attemptId") == attemptId
    }.getOrDefault(false)

    fun load(): PairingSession? {
        val root = root()
        if (root.length() == 0) return null
        require(root.has("attemptId")) // 未发布的残缺旧格式不能恢复设备凭据
        val o = root.optJSONObject("session") ?: return null
        val host = HostStore.hostsFromJson(o.getString("host")).single()
        require(host.token.isNotBlank() && host.deviceId.isNotBlank())
        val attempt = root.getString("attemptId")
        require(attempt.isNotBlank() && o.getString("attemptId") == attempt)
        return PairingSession(
            requestId = o.getString("requestId"), attemptId = attempt, host = host,
            pairRoute = if (o.isNull("pairRoute")) null else HostRoute.valueOf(o.getString("pairRoute")),
            pendingExpiresAt = if (o.isNull("pendingExpiresAt")) null else o.getLong("pendingExpiresAt"),
            serverNow = if (o.isNull("serverNow")) null else o.getLong("serverNow"),
            receivedAtMs = o.getLong("receivedAtMs"), paused = o.optBoolean("paused"),
            pauseReason = runCatching { PairFailureCode.valueOf(o.optString("pauseReason")) }.getOrNull(),
        )
    }

    fun save(session: PairingSession): Boolean = runCatching {
        if (!isCurrent(session.attemptId)) return false
        val o = JSONObject()
            .put("requestId", session.requestId).put("attemptId", session.attemptId)
            .put("host", HostStore.hostsToJson(listOf(session.host)))
            .put("pairRoute", session.pairRoute?.name ?: JSONObject.NULL)
            .put("pendingExpiresAt", session.pendingExpiresAt ?: JSONObject.NULL)
            .put("serverNow", session.serverNow ?: JSONObject.NULL)
            .put("receivedAtMs", session.receivedAtMs).put("paused", session.paused)
            .put("pauseReason", session.pauseReason?.name ?: JSONObject.NULL)
        write(JSONObject().put("attemptId", session.attemptId).put("session", o).toString())
    }.getOrDefault(false)

    fun remove(attemptId: String, deviceId: String): Boolean = runCatching {
        if (!isCurrent(attemptId)) return false
        val current = load() ?: return write(null)
        if (current.attemptId != attemptId || current.host.deviceId != deviceId) return false
        write(null)
    }.getOrDefault(false)

    fun abort(attemptId: String): Boolean = runCatching {
        if (!isCurrent(attemptId)) return false
        write(null)
    }.getOrDefault(false)

    fun complete(attemptId: String, host: Host, saveHost: (Host) -> Boolean): Boolean {
        if (!isCurrent(attemptId) || !saveHost(host)) return false
        write(JSONObject().put("attemptId", attemptId).toString())
        return true
    }

    fun promote(session: PairingSession, saveHost: (Host) -> Boolean): Boolean {
        val current = runCatching { load() }.getOrNull() ?: return false
        if (current.attemptId != session.attemptId || current.host.deviceId != session.host.deviceId) return false
        if (!saveHost(current.host)) return false
        // 正式保存已成功，清理失败可在下次启动由 reconcile 补做。
        write(JSONObject().put("attemptId", current.attemptId).toString())
        return true
    }

    fun reconcile(host: Host?): PairingSession? {
        val pending = load() ?: return null
        if (host != null && host.deviceId == pending.host.deviceId && host.deviceId.isNotBlank()) {
            remove(pending.attemptId, pending.host.deviceId)
            return null
        }
        return pending
    }
}
