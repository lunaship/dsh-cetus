package dev.deeplinks.core

internal const val MONITOR_ACTION_START = "dev.deeplinks.action.START_SESSION_MONITOR"
internal const val MONITOR_ACTION_STOP = "dev.deeplinks.action.STOP_SESSION_MONITOR"
internal const val MONITOR_ACTION_ANSWER = "dev.deeplinks.action.ANSWER_APPROVAL"

internal enum class MonitorStartDecision {
    /** 主机、会话、slot 都齐，继续接管或提交审批。 */
    Continue,
    /** startForegroundService 进来却要马上退出：先 startForeground 再停，避免超时崩溃。 */
    SatisfyThenStop,
    /** startService 或系统重建的退出：不能调用 startForeground。 */
    StopPlain,
}

/**
 * 决定这条启动该怎么收场。
 * [slotMatches] 在 intent 没有带 slot 时为 true（和原来的「没写 slot 就不比」一致）。
 */
internal fun monitorStartDecision(
    action: String?,
    hasHost: Boolean,
    slotMatches: Boolean,
    hasSession: Boolean,
    takeoverOn: Boolean,
    isRestore: Boolean,
): MonitorStartDecision {
    if (action == MONITOR_ACTION_STOP) return MonitorStartDecision.StopPlain
    if (isRestore && !takeoverOn) return MonitorStartDecision.StopPlain
    val earlyExit = !hasHost || !slotMatches || !hasSession
    if (!earlyExit) return MonitorStartDecision.Continue
    val foregroundStart = action == MONITOR_ACTION_START || action == MONITOR_ACTION_ANSWER
    return if (foregroundStart) MonitorStartDecision.SatisfyThenStop else MonitorStartDecision.StopPlain
}
