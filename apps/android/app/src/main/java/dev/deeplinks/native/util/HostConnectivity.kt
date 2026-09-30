package dev.deeplinks.native.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.deeplinks.core.ConnectivitySignals

/** 一次探测的结果快照：传参比传三个值省地方，也让「哪三个值是一组」显式。 */
data class HostConnectivitySnapshot(
    val online: Boolean?,
    val viaRemote: Boolean,
    val latencyMs: Long?,
)

/**
 * 电脑连通性的**单一来源**（2026-09-28 重设计 · 方案 3/7）。
 *
 * 首页有一个 30 秒的 `PairClient.health` 探针（首页没有会话 SSE，在线与延迟只能靠它）。
 * 设置页是另一条路由，拿不到首页的局部状态——如果各自探一次，就会出现两个探测循环
 * 互相打架、两边数字对不上（同一台电脑在两屏显示不同的延迟，用户只会认为 App 坏了）。
 *
 * 所以探针仍然只有首页那一个，它把结果写进这里；设置页只读。与 LocaleManager /
 * ThemeManager 一样是进程内单例。
 */
object HostConnectivity {
    /** null = 还没探过（刚进 App），此时不宣称「离线」——那是假消息。 */
    var online by mutableStateOf<Boolean?>(null)

    /** 最近一次成功的请求是不是走的远程（DLP/1 中继）。 */
    var viaRemote by mutableStateOf(false)

    var latencyMs by mutableStateOf<Long?>(null)

    fun update(online: Boolean?, viaRemote: Boolean, latencyMs: Long?) {
        this.online = online
        this.viaRemote = viaRemote
        this.latencyMs = latencyMs
    }

    /** 请求立刻重新探测（回前台、网络变化、用户点「重试」）——只是转调 [ConnectivitySignals]。 */
    fun requestProbe() = ConnectivitySignals.requestProbe()

    val probeNow: kotlinx.coroutines.flow.SharedFlow<Unit> get() = ConnectivitySignals.probeNow

    /** 只读快照（读它就会订阅这三个 state，变化时自动重组）。 */
    val snapshot: HostConnectivitySnapshot
        get() = HostConnectivitySnapshot(online, viaRemote, latencyMs)
}
