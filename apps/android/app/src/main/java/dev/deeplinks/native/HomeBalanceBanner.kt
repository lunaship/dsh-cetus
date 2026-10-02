package dev.deeplinks.native

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.deeplinks.core.DshS
import dev.deeplinks.core.Host
import dev.deeplinks.core.LocaleManager
import dev.deeplinks.core.appSettingsHostCacheId
import dev.deeplinks.core.balanceBelowThreshold
import dev.deeplinks.core.readBalanceAlert
import dev.deeplinks.core.shouldFetchBalance
import dev.deeplinks.native.ui.DshBanner
import dev.deeplinks.native.ui.DshBannerTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class HomeBalancePreview(val text: String)

/** 最近一次首页余额。进程内记住时间，避免回到首页时连着打接口。 */
internal object BalanceSnapshot {
    private val at = HashMap<String, Long>()
    private val value = HashMap<String, MobileBalance>()

    fun peek(key: String): MobileBalance? = value[key]

    fun put(key: String, balance: MobileBalance, now: Long = System.currentTimeMillis()) {
        value[key] = balance
        at[key] = now
    }

    fun beginFetch(key: String, now: Long): Boolean {
        if (!shouldFetchBalance(at[key], now)) return false
        at[key] = now
        return true
    }
}

@Composable
internal fun HomeBalanceBanner(
    onOpenSettings: () -> Unit,
    preview: HomeBalancePreview? = null,
) {
    if (preview != null) {
        BalanceBanner(preview.text, onOpenSettings)
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val host = remember { runCatching { dev.deeplinks.core.HostStore.current(context) }.getOrNull() } ?: return
    HomeBalanceBannerLive(host, onOpenSettings)
}

@Composable
private fun HomeBalanceBannerLive(host: Host, onOpenSettings: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val key = remember(host.slotKey) { appSettingsHostCacheId(host) }
    val scope = rememberCoroutineScope()
    var balance by remember(key) { mutableStateOf(BalanceSnapshot.peek(key)) }
    var pref by remember(key) { mutableStateOf(readBalanceAlert(context, host)) }
    LifecycleResumeEffect(key) {
        pref = readBalanceAlert(context, host)
        if (pref.enabled && BalanceSnapshot.beginFetch(key, System.currentTimeMillis())) {
            scope.launch {
                val locale = if (LocaleManager.language == "en") "en-US" else "zh-CN"
                val loaded = withContext(Dispatchers.IO) {
                    runCatching { MobileApiClient(host).getBalance(locale) }.getOrNull()
                }
                if (loaded != null) {
                    BalanceSnapshot.put(key, loaded)
                    balance = loaded
                }
            }
        }
        onPauseOrDispose { }
    }
    val shown = balance
    val topUp = shown?.wallets?.firstNotNullOfOrNull { wallet ->
        wallet.balance.trim().toBigDecimalOrNull()?.let { wallet to it }
    }
    val threshold = pref.threshold
    if (shown != null && topUp != null && threshold != null &&
        balanceBelowThreshold(shown.status, topUp.second, pref)
    ) {
        val label = formatAlertMoney(topUp.first.currency, threshold)
        BalanceBanner(DshS.translation("balanceAlertBanner").format(label), onOpenSettings)
    }
}

private fun formatAlertMoney(currency: String, amount: java.math.BigDecimal): String {
    val text = formatMoney(amount.toPlainString())
    return when (currency.uppercase()) {
        "CNY" -> "¥$text"
        "USD" -> "$$text"
        else -> "$text $currency"
    }
}

@Composable
private fun BalanceBanner(text: String, onOpenSettings: () -> Unit) {
    DshBanner(
        text = text,
        tone = DshBannerTone.Warn,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpace.s16, vertical = DshSpace.s8)
            .clickable(onClick = onOpenSettings),
    )
}
