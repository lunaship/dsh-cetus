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
object NetworkTransport {
    /** true=有 Wi-Fi/以太网；false=只有蜂窝；null=未知（老系统 / VPN）。 */
    @Volatile
    var lanCapable: Boolean? = null

    /** 由 [DshApplication] 的网络回调刷新。 */
    fun refresh(cm: ConnectivityManager) {
        lanCapable = computeLanCapable(cm)
    }

    internal fun computeLanCapable(cm: ConnectivityManager): Boolean? {
        val active = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(active) ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            // VPN 之下通常是 Wi-Fi 或蜂窝，但拿不到底层类型时按未知处理，保留原来的探测行为。
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> null
            else -> false
        }
    }
}
