package dev.deeplinks.core

import android.content.Context

private fun hostPrefs(context: Context, host: Host) =
    context.getSharedPreferences("dsh_cost_${appSettingsHostCacheId(host)}", Context.MODE_PRIVATE)

internal fun readBalanceAlert(context: Context, host: Host): BalanceAlertPref {
    val prefs = hostPrefs(context, host)
    // v4 删除了省钱 / 均衡 / 最强三档：清掉旧版本留下的档位映射
    if (prefs.contains("tier_custom")) prefs.edit().remove("tier_custom").apply()
    val enabled = prefs.getBoolean("balance_alert", false)
    val amount = parseAlertAmount(prefs.getString("balance_alert_amount", null).orEmpty())
    return BalanceAlertPref(enabled && amount != null, amount)
}

internal fun writeBalanceAlert(context: Context, host: Host, pref: BalanceAlertPref) {
    hostPrefs(context, host).edit()
        .putBoolean("balance_alert", pref.enabled && pref.threshold != null)
        .putString("balance_alert_amount", pref.threshold?.toPlainString())
        .apply()
}
