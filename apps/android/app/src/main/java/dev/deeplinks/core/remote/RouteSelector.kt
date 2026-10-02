package dev.deeplinks.core.remote

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 一次请求实际走的路。 */
enum class HostRoute { LAN, REMOTE }

/**
 * 自动选路（RFC §7.2）。「自动切换」是**每次新建连接时选路**，不按 SSID 判断，也不迁移正在进行的连接。
 *
 * - 网络代：系统报告网络变化时 +1，清空缓存（[onNetworkChanged]）；
 * - 缓存：局域网结果 30 秒、远程结果 15 秒（回家后较快切回局域网）；
 * - 没有有效缓存时探测一次局域网（调用方给的 probe：TCP + 钉扎 TLS 握手，不发任何 HTTP 与凭据），
 *   同一台电脑同时只探一次，其他请求等结果；
 * - 没有远程能力时，布尔探测不跑（只有一条路）。给出多个直连地址时仍会探测，用来在主地址和 Tailscale 备用地址之间选一条。
 *
 * 在线 / 离线的判定不在这里（R4 放在 `HostConnectivity` 的探测循环里）：选路只决定「这条路通不通」，
 * 不因临时失败把整台电脑标成离线。
 *
 * 纯逻辑，不碰 Android：时钟与探测都由调用方注入，JVM 单测可直接覆盖。
 */
class RouteSelector(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private data class Cached(
        val generation: Long,
        val route: HostRoute,
        val until: Long,
        val lanAddress: String? = null,
    )

    private val generation = AtomicLong(0)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val lastRoutes = ConcurrentHashMap<String, HostRoute>()
    private val clockOffsets = ConcurrentHashMap<String, Long>()

    val currentGeneration: Long get() = generation.get()

    /** 网络变化：旧的选路结论全部作废。 */
    fun onNetworkChanged() {
        generation.incrementAndGet()
        cache.clear()
    }

    /**
     * 本次请求按什么顺序尝试。首项是选中的路；第二项只在「路由建立阶段」失败时才会用到
     * （由调用方判定，见 HostHttp.attemptWithFailover）。
     *
     * [lanCapable]（S4）：当前默认网络是否可能走局域网。明确为 `false`（只有蜂窝）时跳过局域网探测，
     * 直接缓存并返回远程；`true` / `null`（未知）时行为不变。
     */
    fun order(
        key: String,
        hasRemote: Boolean,
        lanCapable: () -> Boolean? = { null },
        probeLan: () -> Boolean,
    ): List<HostRoute> {
        if (!hasRemote) return listOf(HostRoute.LAN)
        if (lanCapable() == false) {
            remember(key, HostRoute.REMOTE)
            return orderFrom(HostRoute.REMOTE)
        }
        fresh(key)?.let { return orderFrom(it.route) }
        val lock = locks.computeIfAbsent(key) { Any() }
        synchronized(lock) {
            fresh(key)?.let { return orderFrom(it.route) }
            val route = if (probeLan()) HostRoute.LAN else HostRoute.REMOTE
            remember(key, route)
            return orderFrom(route)
        }
    }

    /**
     * 先试 [candidates] 里的主地址，失败再试后面的 Tailscale 备用地址。
     * 通的那条记入本代缓存；都失败才选远程。网络变化会清掉这份缓存。
     */
    fun order(
        key: String,
        hasRemote: Boolean,
        candidates: List<String>,
        lanCapable: () -> Boolean? = { null },
        probeDirect: (String) -> Boolean,
    ): List<HostRoute> {
        if (!hasRemote) {
            fresh(key)?.let { return listOf(HostRoute.LAN) }
            val lock = locks.computeIfAbsent(key) { Any() }
            synchronized(lock) {
                fresh(key)?.let { return listOf(HostRoute.LAN) }
                remember(key, HostRoute.LAN, firstDirect(candidates, probeDirect))
                return listOf(HostRoute.LAN)
            }
        }
        if (lanCapable() == false) {
            remember(key, HostRoute.REMOTE, null)
            return orderFrom(HostRoute.REMOTE)
        }
        fresh(key)?.let { return orderFrom(it.route) }
        val lock = locks.computeIfAbsent(key) { Any() }
        synchronized(lock) {
            fresh(key)?.let { return orderFrom(it.route) }
            val hit = firstDirect(candidates, probeDirect)
            val route = if (hit != null) HostRoute.LAN else HostRoute.REMOTE
            remember(key, route, hit)
            return orderFrom(route)
        }
    }

    /** 当前网络代里，局域网探测选中的直连地址。没有或已过期时为 null。 */
    fun lanAddress(key: String): String? {
        val hit = fresh(key) ?: return null
        if (hit.route != HostRoute.LAN) return null
        return hit.lanAddress?.takeIf { it.isNotBlank() }
    }

    /** 某条路实际成功了：记为当前选路，并更新界面上显示的「局域网 / 远程」。 */
    fun noteSuccess(key: String, route: HostRoute) {
        lastRoutes[key] = route
        remember(key, route)
    }

    /** 选中的路在建立阶段失败：作废缓存，下一次请求重新探测。 */
    fun forget(key: String) {
        cache.remove(key)
    }

    fun lastRoute(key: String): HostRoute? = lastRoutes[key]

    /** CLOCK_SKEW 时按电脑给的 hostNow 记下偏移（秒），之后该电脑的远程会合都带上它。 */
    fun clockOffsetSec(key: String): Long = clockOffsets[key] ?: 0L

    fun noteHostNow(key: String, hostNowSec: Long) {
        clockOffsets[key] = hostNowSec - clock() / 1000L
    }

    private fun fresh(key: String): Cached? {
        val hit = cache[key] ?: return null
        if (hit.generation != generation.get() || hit.until <= clock()) return null
        return hit
    }

    private fun firstDirect(candidates: List<String>, probe: (String) -> Boolean): String? {
        for (url in candidates) {
            if (url.isBlank()) continue
            if (probe(url)) return url
        }
        return null
    }

    /** 沿用上一次记下的直连地址（布尔探测不知道具体是哪条）。 */
    private fun remember(key: String, route: HostRoute) {
        remember(key, route, if (route == HostRoute.LAN) fresh(key)?.lanAddress else null)
    }

    private fun remember(key: String, route: HostRoute, lanAddress: String?) {
        val ttl = if (route == HostRoute.LAN) LAN_TTL_MS else REMOTE_TTL_MS
        cache[key] = Cached(
            generation.get(),
            route,
            clock() + ttl,
            if (route == HostRoute.LAN) lanAddress?.takeIf { it.isNotBlank() } else null,
        )
    }

    private fun orderFrom(route: HostRoute): List<HostRoute> =
        if (route == HostRoute.LAN) listOf(HostRoute.LAN, HostRoute.REMOTE) else listOf(HostRoute.REMOTE, HostRoute.LAN)

    companion object {
        const val LAN_TTL_MS = 30_000L
        const val REMOTE_TTL_MS = 15_000L

        /** 进程内唯一实例：HostHttp 与网络回调共用。 */
        val shared = RouteSelector()
    }
}
