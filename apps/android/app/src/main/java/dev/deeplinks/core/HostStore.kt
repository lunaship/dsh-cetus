package dev.deeplinks.core

import android.content.Context
import dev.deeplinks.core.remote.DlpCrypto
import dev.deeplinks.core.remote.RemoteRoute
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

data class Host(
    val name: String,
    val baseUrl: String,
    val token: String,
    val deviceId: String = "",
    val certFingerprint: String = "",
    /** DLP/1 远程能力（RFC §7.1）：插件在配对响应或 bootstrap 里下发，四项齐全才算有。 */
    val remoteEndpoint: String = "",
    val remoteRouteId: String = "",
    val remoteHandle: String = "",
    val remoteKey: String = "",
    val remoteOuterPin: String = "",
) {
    val hasRemote: Boolean
        get() = remoteEndpoint.isNotBlank() && remoteRouteId.isNotBlank() && remoteHandle.isNotBlank() && remoteKey.isNotBlank()

    /** 解析后的远程路由；字段不合法时为 null（按没有远程能力处理）。 */
    fun remoteRoute(): RemoteRoute? =
        RemoteRoute.fromStored(remoteEndpoint, remoteRouteId, remoteHandle, remoteKey, remoteOuterPin)

    /**
     * 本机缓存（会话快照、草稿）的命名空间。远程只是同一台电脑的另一条路，不参与区分；
     * 前缀保持 `lan|`，已有的局域网缓存不失效。
     */
    val slotKey: String
        get() = "lan|$name|$baseUrl"

    fun withRemote(route: RemoteRoute): Host = copy(
        remoteEndpoint = route.endpoint,
        remoteRouteId = DlpCrypto.base64Url(route.routeId),
        remoteHandle = DlpCrypto.base64Url(route.keyId),
        remoteKey = DlpCrypto.base64Url(route.key),
        remoteOuterPin = route.outerPin,
    )

    fun withoutRemote(): Host = copy(
        remoteEndpoint = "",
        remoteRouteId = "",
        remoteHandle = "",
        remoteKey = "",
        remoteOuterPin = "",
    )

    fun putInto(intent: Intent): Intent {
        intent.putExtra(EXTRA_HOST_NAME, name)
        intent.putExtra(EXTRA_HOST_BASE_URL, baseUrl)
        return intent
    }
}

const val EXTRA_HOST_NAME = "hostName"
const val EXTRA_HOST_BASE_URL = "hostBaseUrl"
const val EXTRA_AUTH_NOTICE = "authNotice"

fun List<Host>.resolveFromIntent(intent: Intent): Host? =
    resolveHost(intent.getStringExtra(EXTRA_HOST_NAME), intent.getStringExtra(EXTRA_HOST_BASE_URL))

internal fun List<Host>.resolveHost(name: String?, baseUrl: String?): Host? {
    if (!name.isNullOrBlank()) {
        val named = filter { it.name == name }
        if (named.size == 1) return named.first()
        if (!baseUrl.isNullOrBlank()) named.firstOrNull { it.baseUrl == baseUrl }?.let { return it }
        named.firstOrNull()?.let { return it }
    }
    if (!baseUrl.isNullOrBlank()) {
        filter { it.baseUrl == baseUrl }.firstOrNull()?.let { return it }
    }
    return firstOrNull()
}

sealed class HostLoadResult {
    data class Ok(val hosts: List<Host>) : HostLoadResult()
    data object Empty : HostLoadResult()
    data object Undecryptable : HostLoadResult()
}

/**
 * 本机配对的电脑：**单设备**。只保存一台；配对新电脑即替换旧的。
 *
 * 存储格式仍是 JSON 数组（与旧版多设备数据互相可读）；读到旧版多台记录时
 * 只取最近使用的那一台（[singleHostOf]），其余在下一次写入时自然丢弃。
 */
object HostStore {
    private const val PREFS = "dsh_hosts"
    private const val KEY = "hosts"
    private const val LOCK = "hosts_locked"

    /** 最近成功进入 Workspace 的设备身份（[stableIdentity]，重命名安全）。 */
    private const val KEY_LAST_HOST = "last_host_identity"

    fun loadResult(ctx: Context): HostLoadResult {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return HostLoadResult.Empty
        val lastIdentity = prefs.getString(KEY_LAST_HOST, null)
        return if (TokenCrypto.isEncrypted(raw)) {
            val plain = TokenCrypto.decrypt(ctx, raw)
            if (plain == null) {
                prefs.edit().putBoolean(LOCK, true).apply()
                HostLoadResult.Undecryptable
            } else {
                prefs.edit().putBoolean(LOCK, false).apply()
                val host = singleHostOf(hostsFromJson(plain), lastIdentity)
                if (host == null) HostLoadResult.Empty else HostLoadResult.Ok(listOf(host))
            }
        } else {
            val legacy = singleHostOf(hostsFromJson(raw), lastIdentity)
            if (legacy == null) {
                prefs.edit().remove(KEY).apply()
                return HostLoadResult.Empty
            }
            if (!save(ctx, legacy)) return HostLoadResult.Undecryptable
            val stored = prefs.getString(KEY, null)
            if (stored.isNullOrEmpty() || !TokenCrypto.isEncrypted(stored)) {
                return HostLoadResult.Undecryptable
            }
            HostLoadResult.Ok(listOf(legacy))
        }
    }

    /** 已配对设备（至多一台）。 */
    fun load(ctx: Context): List<Host> = when (val r = loadResult(ctx)) {
        is HostLoadResult.Ok -> r.hosts
        HostLoadResult.Empty, HostLoadResult.Undecryptable -> emptyList()
    }

    /** 当前配对的电脑；未配对或凭据不可读时为 null。 */
    fun current(ctx: Context): Host? = load(ctx).firstOrNull()

    private fun save(ctx: Context, host: Host?): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(LOCK, false)) return false
        val hosts = listOfNotNull(host)
        return try {
            val encrypted = TokenCrypto.encrypt(ctx, hostsToJson(hosts))
            val committed = prefs.edit().putString(KEY, encrypted).commit()
            if (committed) syncLastHostIdentity(prefs, host)
            committed
        } catch (_: Exception) {
            false
        }
    }

    private fun syncLastHostIdentity(prefs: android.content.SharedPreferences, host: Host?) {
        val current = prefs.getString(KEY_LAST_HOST, null)
        val next = host?.stableIdentity()
        if (next == current) return
        val editor = prefs.edit()
        if (next == null) editor.remove(KEY_LAST_HOST) else editor.putString(KEY_LAST_HOST, next)
        editor.apply()
    }

    /** 最近成功使用的设备身份；null 表示尚无记录（首次启动或已解除配对）。 */
    fun lastHostIdentity(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_HOST, null)

    /** 设备成功连接并进入 Workspace 后调用。 */
    fun rememberLastHost(ctx: Context, host: Host) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_HOST, host.stableIdentity())
            .apply()
    }

    fun isLocked(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(LOCK, false)

    /**
     * 写入配对设备：同一台的更新（远程能力补齐 / 清除）与配对新电脑都走这里，
     * 后者直接替换旧设备。
     */
    @Synchronized
    fun upsert(ctx: Context, host: Host): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(LOCK, false)) {
            // 解锁态禁止静默覆盖：调用方须显式 clearLockAndReplace
            return false
        }
        return save(ctx, host)
    }

    /** 密钥不可用后的显式恢复：清掉不可读的旧记录并写入这一台。 */
    @Synchronized
    fun clearLockAndReplace(ctx: Context, host: Host): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(LOCK, false).apply()
        return save(ctx, host)
    }

    /** 解除配对（本机侧）。只在存的还是这台时清空，避免误删刚换上的新设备。本机的会话快照与草稿一并清掉。 */
    @Synchronized
    fun remove(ctx: Context, host: Host): Boolean {
        val current = current(ctx) ?: return true
        if (current.slotKey != host.slotKey && current.baseUrl != host.baseUrl) return true
        val ok = save(ctx, null)
        if (ok) {
            runCatching { dev.deeplinks.native.SessionHistoryCache.clearHost(ctx.cacheDir, current.slotKey) }
            runCatching { dev.deeplinks.native.util.WorkspacePrefs(ctx).saveComposerDrafts(current.slotKey, emptyMap()) }
        }
        return ok
    }

    // ---- 纯函数（单元测试直测，无 Android 依赖） ----

    internal fun hostsToJson(hosts: List<Host>): String {
        val arr = JSONArray()
        for (h in hosts) {
            arr.put(
                JSONObject()
                    .put("name", h.name)
                    .put("baseUrl", h.baseUrl)
                    .put("token", h.token)
                    .put("deviceId", h.deviceId)
                    .put("certFingerprint", h.certFingerprint)
                    .put("remoteEndpoint", h.remoteEndpoint)
                    .put("remoteRouteId", h.remoteRouteId)
                    .put("remoteHandle", h.remoteHandle)
                    .put("remoteKey", h.remoteKey)
                    .put("remoteOuterPin", h.remoteOuterPin),
            )
        }
        return arr.toString()
    }

    internal fun hostsFromJson(json: String): List<Host> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val rawUrl = o.getString("baseUrl")
                val url = if (rawUrl.startsWith("http://")) "https://" + rawUrl.removePrefix("http://") else rawUrl
                // 旧版 DLR/1 的 relay* 字段：该中继已下线，读到即丢弃，下一次写入时自然消失
                Host(
                    name = o.getString("name"),
                    baseUrl = url,
                    token = o.getString("token"),
                    deviceId = o.optString("deviceId"),
                    certFingerprint = o.optString("certFingerprint"),
                    remoteEndpoint = o.optString("remoteEndpoint"),
                    remoteRouteId = o.optString("remoteRouteId"),
                    remoteHandle = o.optString("remoteHandle"),
                    remoteKey = o.optString("remoteKey"),
                    remoteOuterPin = o.optString("remoteOuterPin"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 旧版多设备数据收敛为一台：优先最近使用的，否则取列表首项（最近配对）。 */
    internal fun singleHostOf(hosts: List<Host>, lastIdentity: String?): Host? =
        pickStartupHost(hosts, intentHost = null, lastIdentity = lastIdentity)
}

/**
 * 插件 bootstrap 的 `remote`（RFC §6.4、§10.4）：对象 = 更新远程能力；JSON null = 插件停了远程，
 * 只清远程字段、保留局域网配对；没有这个键（旧插件）= 不动。解析不了的对象也不动，
 * 不因为一次坏数据丢掉能用的凭据（RFC §7.4）。
 */
internal fun applyBootstrapRemote(host: Host, root: JSONObject): Host {
    val update = runCatching { RemoteRoute.bootstrapUpdate(root) }.getOrNull() ?: return host
    if (!update.present) return host
    val route = update.route ?: return host.withoutRemote()
    return host.withRemote(route)
}
