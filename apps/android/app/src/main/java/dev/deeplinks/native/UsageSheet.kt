package dev.deeplinks.native

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.tabularNums
import dev.deeplinks.native.ui.v4.DlBigNumber
import dev.deeplinks.native.ui.v4.DlBottomSheet
import dev.deeplinks.native.ui.v4.DlPill
import dev.deeplinks.native.ui.v4.DlSize
import dev.deeplinks.native.util.compactDuration
import dev.deeplinks.native.util.compactTokens
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 5.7 会话用量：一个大数字 + 上下文进度条 + 键值行。点上下文环，或会话「⋯」里的「用量」打开。
 * stats 为 null 是旧 Host：只说明没有数据，不发额外请求。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UsageSheet(stats: MobileSessionStats?, onDismiss: () -> Unit) {
    DlBottomSheet(onDismissRequest = onDismiss, title = DshS.sessionStatsSheetTitle) {
        UsagePanel(stats)
    }
}

/** 面板正文。截图直接画这一块，不依赖底部弹层的宿主。 */
@Composable
internal fun UsagePanel(stats: MobileSessionStats?) {
    val figures = usageFigures(stats)
    if (figures == null) {
        Text(
            DshS.translation("usageEmpty"),
            color = Dsh.labelSecondary,
            style = DshType.body,
            modifier = Modifier.padding(horizontal = DshSpace.s24, vertical = DshSpace.s12),
        )
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = DshSpace.s24)) {
        DlBigNumber(compactTokens(figures.totalTokens), DshS.translation("usageTokensUnit"))
        formatEstimate(stats?.estimatedCost)?.let { estimate ->
            Text(estimate, style = DshType.supporting, color = Dsh.labelSecondary)
        }
        if (figures.contextWindowTokens > 0) ContextUsageBlock(figures)
        Column(Modifier.padding(top = DshSpace.s12)) {
            UsageLine(DshS.statsCacheHitLabel, formatPercent(figures.cacheHitRate))
            UsageLine(
                DshS.translation("usageIoLabel"),
                listOf(figures.uncachedInputTokens, figures.cacheReadTokens, figures.outputTokens)
                    .joinToString(" / ") { compactTokens(it) },
            )
            UsageLine(DshS.translation("usageTurnsSteps"), "${figures.turns} / ${figures.steps}")
            UsageLine(
                DshS.translation("usageTimesLabel"),
                formatDurationOrDash(figures.llmMs) + " / " + formatDurationOrDash(figures.toolMs),
            )
            UsageLine(DshS.translation("usageAvgTtft"), formatDurationOrDash(figures.avgTtftMs?.toLong()))
            UsageLine(DshS.tokenRateUnit, formatSpeed(figures.outputTokensPerSec))
        }
    }
}

@Composable
private fun ContextUsageBlock(figures: UsageFigures) {
    val percent = contextUsedPercent(figures.contextUsedTokens, figures.contextWindowTokens) ?: return
    Column(Modifier.padding(top = DshSpace.s16), verticalArrangement = Arrangement.spacedBy(DshSpace.s8)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(DshS.contextUsed, style = DshType.supporting, color = Dsh.labelPrimary, modifier = Modifier.weight(1f))
            Text(
                "${compactTokens(figures.contextUsedTokens)} / ${compactTokens(figures.contextWindowTokens)} · $percent%",
                style = DshType.supporting.tabularNums(),
                color = Dsh.labelSecondary,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(DshSpace.s4)
                .clip(DlPill)
                .background(Dsh.surface2),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percent / 100f)
                    .fillMaxHeight()
                    .background(if (percent > 80) Dsh.wait else Dsh.brand400),
            )
        }
        breakdownCaption(figures)?.let { caption ->
            Text(caption, style = DshType.supporting, color = Dsh.labelSecondary)
        }
    }
}

/** 「系统 12% · 工具 9% · 消息 25%」：占整个上下文窗口的比例，只用文字不用色条。 */
@Composable
private fun breakdownCaption(figures: UsageFigures): String? {
    if (figures.breakdown.isEmpty() || figures.contextWindowTokens <= 0) return null
    val parts = mutableListOf<String>()
    for (slice in figures.breakdown) {
        val pct = contextUsedPercent(slice.tokens, figures.contextWindowTokens) ?: 0
        parts += "${breakdownLabel(slice.key)} $pct%"
    }
    return parts.joinToString(" · ")
}

@Composable
private fun UsageLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = DlSize.rowSingle - DshSpace.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = Dsh.labelPrimary,
            style = DshType.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(value, color = Dsh.labelSecondary, style = DshType.body.tabularNums(), maxLines = 1)
    }
}

@Composable
private fun breakdownLabel(key: String): String = when (key) {
    "system" -> DshS.translation("usageSystemShort")
    "tools" -> DshS.tools
    "messages" -> DshS.translation("usageMessagesShort")
    else -> key
}

private fun formatPercent(rate: Double?): String =
    if (rate == null) "—" else "${(rate * 100).roundToInt().coerceIn(0, 100)}%"

private fun formatDurationOrDash(ms: Long?): String =
    if (ms == null || ms <= 0L) "—" else compactDuration(ms)

@Composable
private fun formatEstimate(cost: EstimatedCost?): String? {
    if (cost == null) return null
    val min = cost.amountMin
    val max = cost.amountMax
    val money = if (min != null && max != null) {
        formatEstimateMoney(cost.currency, min) + "–" + formatEstimateMoney(cost.currency, max)
    } else {
        formatEstimateMoney(cost.currency, cost.amount)
    }
    val date = cost.priceDate
    return if (date.isNullOrBlank()) {
        DshS.translation("usageEstimateNoDate").format(money)
    } else {
        DshS.translation("usageEstimate").format(money, date)
    }
}

private fun formatEstimateMoney(currency: String, amount: Double): String {
    val text = if (amount >= 0.01 || amount == 0.0) {
        String.format(Locale.US, "%.2f", amount)
    } else {
        String.format(Locale.US, "%.4f", amount)
    }
    return when (currency.uppercase(Locale.US)) {
        "CNY" -> "¥$text"
        "USD" -> "$$text"
        else -> "$text $currency"
    }
}

private fun formatSpeed(tokensPerSec: Double?): String {
    if (tokensPerSec == null || !tokensPerSec.isFinite()) return "—"
    return if (tokensPerSec >= 100.0) {
        String.format(Locale.US, "%.0f", tokensPerSec)
    } else {
        String.format(Locale.US, "%.1f", tokensPerSec)
    }
}
