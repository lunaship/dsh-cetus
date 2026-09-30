package dev.deeplinks.native.util

import dev.deeplinks.core.ConnectivitySignals
import dev.deeplinks.core.Host
import dev.deeplinks.core.PairClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 连续失败次数 → 是否判离线、下一次多久后探测（R4）。 */
internal data class ProbePlan(val offline: Boolean, val nextDelayMs: Long)

/**
 * 在线判定计划（纯函数，便于单测）：
 *
 * - 成功：30 秒后再探；
 * - 在线过的主机第一次失败不宣布离线（隧道抖动 / 繁忙很常见），3 秒后复查；
 * - 从未在线过的第一次失败就判离线（冷启动连不上就是连不上）；
 * - 此后 5 / 10 / 30 秒逐步退避。
 */
internal fun probePlan(consecutiveFailures: Int, everOnline: Boolean): ProbePlan = when {
    consecutiveFailures == 0 -> ProbePlan(offline = false, nextDelayMs = 30_000)
    consecutiveFailures == 1 && everOnline -> ProbePlan(offline = false, nextDelayMs = 3_000)
    consecutiveFailures <= 2 -> ProbePlan(offline = true, nextDelayMs = 5_000)
    consecutiveFailures <= 4 -> ProbePlan(offline = true, nextDelayMs = 10_000)
    else -> ProbePlan(offline = true, nextDelayMs = 30_000)
}

/**
 * 首页健康探测循环（R4）。每轮在 IO 线程调 [PairClient.health]，按 [probePlan] 决定在线状态和下次延迟；
 * 等待期间监听 [ConnectivitySignals.probeNow]，被唤醒时立刻进入下一轮。
 *
 * 证书失效（AuthFailed）沿原逻辑按「连不上」处理：原实现就是这样（不新增崩溃面）。
 */
internal suspend fun runReachabilityLoop(
    host: Host,
    onUpdate: (online: Boolean, latencyMs: Long?) -> Unit,
) {
    var failures = 0
    var everOnline = false
    while (true) {
        val latencyMs = withContext(Dispatchers.IO) {
            runCatching { PairClient.health(host) }.getOrNull()
        }
        if (latencyMs != null) {
            failures = 0
            everOnline = true
        } else {
            failures += 1
        }
        val plan = probePlan(failures, everOnline)
        onUpdate(!plan.offline, latencyMs)
        withTimeoutOrNull(plan.nextDelayMs) { ConnectivitySignals.probeNow.first() }
    }
}
