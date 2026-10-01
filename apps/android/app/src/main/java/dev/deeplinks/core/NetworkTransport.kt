package dev.deeplinks.core

import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 当前默认网络能不能走局域网（第三轮 S4）。
 *
 * 在外面只有蜂窝网络时，`RouteSelector` 会先花 0.8–1.2 秒探测一个根本不可达的局域网地址，
 * 再回落到远程。这里把「默认网络是否有 Wi-Fi / 以太网传输」记成一个进程级快照，
 * 让选路直接跳过探测。VPN 判断不了底层时保持 `null`（未知，行为不变）。
 */
/** 诊断页用的网络类型。选路仍只看 [NetworkTransport.lanCapable]，不靠这个枚举。 */
enum class NetworkKind { WIFI, CELLULAR, NONE, UNKNOWN }

object NetworkTransport {
    /** true=有 Wi-Fi/以太网；false=只有蜂窝；null=未知（老系统 / VPN）。 */
    @Volatile
    var lanCapable: Boolean? = null

    /**
     * 同一次 [refresh] 记下的类型：无默认网络是 [NetworkKind.NONE]，
     * VPN 或拿不到能力是 [NetworkKind.UNKNOWN]。不额外发起探测。
     */
    @Volatile
    var snapshotKind: NetworkKind = NetworkKind.UNKNOWN

    /** 由 [DshApplication] 的网络回调刷新。 */
    fun refresh(cm: ConnectivityManager) {
        val active = cm.activeNetwork
        val caps = if (active != null) cm.getNetworkCapabilities(active) else null
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        lanCapable = lanCapableFrom(active != null, caps != null, wifi, ethernet, vpn)
        snapshotKind = networkKindFrom(active != null, caps != null, wifi, ethernet, vpn)
    }

    internal fun computeLanCapable(cm: ConnectivityManager): Boolean? {
        val active = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(active) ?: return null
        return lanCapableFrom(
            hasActive = true,
            capsKnown = true,
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        )
    }
}

/** 与改前 [NetworkTransport.computeLanCapable] 同一套结论。 */
internal fun lanCapableFrom(
    hasActive: Boolean,
    capsKnown: Boolean,
    wifi: Boolean,
    ethernet: Boolean,
    vpn: Boolean,
): Boolean? {
    if (!hasActive || !capsKnown) return null
    if (wifi || ethernet) return true
    // VPN 之下通常是 Wi-Fi 或蜂窝，但拿不到底层类型时按未知处理，保留原来的探测行为。
    if (vpn) return null
    return false
}

internal fun networkKindFrom(
    hasActive: Boolean,
    capsKnown: Boolean,
    wifi: Boolean,
    ethernet: Boolean,
    vpn: Boolean,
): NetworkKind = when {
    !hasActive -> NetworkKind.NONE
    !capsKnown || vpn -> NetworkKind.UNKNOWN
    wifi || ethernet -> NetworkKind.WIFI
    else -> NetworkKind.CELLULAR
}
