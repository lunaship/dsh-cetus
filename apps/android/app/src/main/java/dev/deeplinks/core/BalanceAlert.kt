package dev.deeplinks.core

import java.math.BigDecimal

/** 首页余额提醒。默认关闭；关闭或没有阈值时不提示。 */
data class BalanceAlertPref(val enabled: Boolean = false, val threshold: BigDecimal? = null)

internal const val BALANCE_FETCH_INTERVAL_MS = 5 * 60 * 1000L

/**
 * 只在提醒开着、余额接口是 ready、并且充值余额低于阈值时为真。
 * signed-out / failed / unavailable，或金额读不出来，都不提示。
 */
internal fun balanceBelowThreshold(
    status: String,
    topUp: BigDecimal?,
    pref: BalanceAlertPref,
): Boolean {
    if (!pref.enabled || pref.threshold == null) return false
    if (status != "ready") return false
    val amount = topUp ?: return false
    return amount < pref.threshold
}

/** 首页进入时最多隔这么久拉一次。没有上次记录就可以拉。 */
internal fun shouldFetchBalance(lastAtMs: Long?, nowMs: Long, minIntervalMs: Long = BALANCE_FETCH_INTERVAL_MS): Boolean {
    val last = lastAtMs ?: return true
    return nowMs - last >= minIntervalMs
}

internal fun parseAlertAmount(raw: String): BigDecimal? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val amount = text.toBigDecimalOrNull() ?: return null
    if (amount.signum() < 0) return null
    return amount
}
