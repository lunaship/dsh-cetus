package dev.deeplinks.core

/** 全部会话结束后仍保持连接的时间。 */
internal const val HOST_MONITOR_IDLE_MS = 5 * 60 * 1000L

internal data class HostLink(
    val hostKey: String,
    val active: Boolean,
)

internal data class HostMonitorSnapshot(
    val connect: Set<String>,
    val idleSince: Map<String, Long>,
)

internal fun isHostSessionActive(state: String): Boolean =
    state == "running" || state == "awaitingApproval" || state == "awaitingInput"

/**
 * 每台电脑一条连接。有进行中或等待中的会话就保持；全部结束后满 [idleMs] 再断开。
 * 多台电脑各自计时，互不影响。
 */
internal fun stepHostMonitor(
    nowMs: Long,
    links: List<HostLink>,
    idleSince: Map<String, Long>,
    idleMs: Long = HOST_MONITOR_IDLE_MS,
): HostMonitorSnapshot {
    val nextIdle = idleSince.toMutableMap()
    val connect = linkedSetOf<String>()
    val seen = linkedSetOf<String>()
    for (link in links) {
        if (link.hostKey.isBlank() || !seen.add(link.hostKey)) continue
        if (link.active) {
            nextIdle.remove(link.hostKey)
            connect += link.hostKey
        } else {
            val since = nextIdle.getOrPut(link.hostKey) { nowMs }
            if (nowMs - since < idleMs) connect += link.hostKey
        }
    }
    nextIdle.keys.retainAll(seen)
    return HostMonitorSnapshot(connect, nextIdle)
}

/** 默认网络真正切换时重连主机事件流。仅属性变化不重连。 */
internal fun hostMonitorShouldReconnect(action: NetworkChangeAction): Boolean =
    action == NetworkChangeAction.ResetPool
