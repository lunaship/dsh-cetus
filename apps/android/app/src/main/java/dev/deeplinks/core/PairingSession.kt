package dev.deeplinks.core

import dev.deeplinks.core.remote.HostRoute

/** 待批准设备使用 /pair 返回的 device 凭据，绝不保存二维码 bootstrap。 */
data class PairingSession(
    val requestId: String,
    val attemptId: String,
    val host: Host,
    val pairRoute: HostRoute?,
    val pendingExpiresAt: Long?,
    val serverNow: Long?,
    val receivedAtMs: Long,
    val paused: Boolean = false,
    val pauseReason: PairFailureCode? = null,
) {
    companion object {
        const val DEFAULT_WAIT_BUDGET_MS = 10 * 60 * 1000L
        const val DEFAULT_POLL_MS = 2_000L
        const val DEFAULT_RETRY_THRESHOLD = 3
    }

    /** 持久化 wall time 仅用于恢复时估算；进程内截止时间由单调时钟推进。 */
    fun remainingMs(wallNow: Long = System.currentTimeMillis()): Long {
        val initial = if (pendingExpiresAt != null && serverNow != null) {
            (pendingExpiresAt - serverNow).coerceIn(0L, DEFAULT_WAIT_BUDGET_MS)
        } else DEFAULT_WAIT_BUDGET_MS
        val elapsed = (wallNow - receivedAtMs).coerceAtLeast(0L)
        return (initial - elapsed).coerceAtLeast(0L)
    }
}
