package dev.deeplinks.core

import android.content.Context
import org.json.JSONObject

private fun hostPrefs(context: Context, host: Host) =
    context.getSharedPreferences("dsh_cost_${appSettingsHostCacheId(host)}", Context.MODE_PRIVATE)

internal fun readBalanceAlert(context: Context, host: Host): BalanceAlertPref {
    val prefs = hostPrefs(context, host)
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

internal fun readTierCustom(context: Context, host: Host): Map<ModelTier, TierCustom> =
    decodeTierCustom(hostPrefs(context, host).getString("tier_custom", null))

internal fun writeTierCustom(context: Context, host: Host, custom: Map<ModelTier, TierCustom>) {
    hostPrefs(context, host).edit().putString("tier_custom", encodeTierCustom(custom)).apply()
}

internal fun encodeTierCustom(custom: Map<ModelTier, TierCustom>): String {
    val root = JSONObject()
    for ((tier, value) in custom) {
        root.put(
            tier.name,
            JSONObject()
                .put("provider", value.provider)
                .put("model", value.modelId)
                .put("effort", value.effort ?: JSONObject.NULL),
        )
    }
    return root.toString()
}

internal fun decodeTierCustom(raw: String?): Map<ModelTier, TierCustom> {
    if (raw.isNullOrBlank()) return emptyMap()
    val root = try {
        JSONObject(raw)
    } catch (_: Exception) {
        return emptyMap()
    }
    val out = linkedMapOf<ModelTier, TierCustom>()
    for (tier in ModelTier.entries) {
        val obj = root.optJSONObject(tier.name) ?: continue
        val model = obj.optString("model").trim()
        if (model.isEmpty()) continue
        val effort = if (obj.isNull("effort")) null else obj.optString("effort").trim().ifEmpty { null }
        out[tier] = TierCustom(obj.optString("provider"), model, effort)
    }
    return out
}
