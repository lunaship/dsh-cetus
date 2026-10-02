package dev.deeplinks.native

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.tabularNums
import dev.deeplinks.native.ui.DshSheet
import dev.deeplinks.native.util.compactDuration
import dev.deeplinks.native.util.compactTokens
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 会话用量底部面板。点上下文环，或会话「⋯」里的「用量」打开。
 * stats 为 null 是旧 Host：只说明没有数据，不发额外请求。
 */
@Composable
internal fun UsageSheet(stats: MobileSessionStats?, onDismiss: () -> Unit) {
    DshSheet(
        onDismiss = onDismiss,
        title = DshS.sessionStatsSheetTitle,
        showClose = true,
        skipPartiallyExpanded = true,
    ) {
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
            modifier = Modifier.padding(vertical = DshSpace.s12),
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(DshSpace.s8)) {
        UsageLine(DshS.translation("usageUncached"), compactTokens(figures.uncachedInputTokens))
        UsageLine(DshS.translation("usageCacheRead"), compactTokens(figures.cacheReadTokens))
        UsageLine(DshS.translation("usageOutput"), compactTokens(figures.outputTokens))
        UsageLine(DshS.translation("usageTotal"), compactTokens(figures.totalTokens), strong = true)
        UsageLine(DshS.statsCacheHitLabel, formatPercent(figures.cacheHitRate))
        UsageLine(DshS.translation("usageTurns"), figures.turns.toString())
        UsageLine(DshS.translation("usageSteps"), figures.steps.toString())
        UsageLine(DshS.translation("usageLlmTime"), formatDurationOrDash(figures.llmMs))
        UsageLine(DshS.translation("usageToolTime"), formatDurationOrDash(figures.toolMs))
        UsageLine(DshS.translation("usageAvgTtft"), formatDurationOrDash(figures.avgTtftMs?.toLong()))
        UsageLine(DshS.tokenRateUnit, formatSpeed(figures.outputTokensPerSec))
        if (figures.contextWindowTokens > 0) {
            ContextUsageBlock(figures)
        }
    }
}

@Composable
private fun ContextUsageBlock(figures: UsageFigures) {
    val percent = contextUsedPercent(figures.contextUsedTokens, figures.contextWindowTokens) ?: return
    Column(verticalArrangement = Arrangement.spacedBy(DshSpace.s8)) {
        UsageLine(
            DshS.statsContextWindow,
            "${compactTokens(figures.contextUsedTokens)} / ${compactTokens(figures.contextWindowTokens)} · $percent%",
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(DshSpace.s4)
                .clip(RoundedCornerShape(DshRadius.micro))
                .background(Dsh.bgTrack),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percent / 100f)
                    .fillMaxHeight()
                    .background(if (percent > 80) Dsh.warn else Dsh.brand500),
            )
        }
        if (figures.breakdown.isNotEmpty()) {
            BreakdownBar(figures.breakdown)
            figures.breakdown.forEach { slice ->
                ContextMeterRow(
                    label = breakdownLabel(slice.key),
                    value = compactTokens(slice.tokens),
                    swatchColor = breakdownColor(slice.key),
                )
            }
        }
    }
}

@Composable
private fun BreakdownBar(slices: List<UsageSlice>) {
    val total = slices.sumOf { it.tokens.toDouble() }.takeIf { it > 0.0 } ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DshSpace.s8)
            .clip(RoundedCornerShape(DshRadius.micro)),
    ) {
        slices.forEach { slice ->
            val weight = (slice.tokens.toDouble() / total).toFloat().coerceAtLeast(0.001f)
            Box(
                modifier = Modifier
                    .weight(weight)
                    .fillMaxHeight()
                    .background(breakdownColor(slice.key)),
            )
        }
    }
}

@Composable
private fun UsageLine(label: String, value: String, strong: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = Dsh.labelSecondary,
            style = if (strong) DshType.bodyStrong else DshType.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            color = Dsh.labelPrimary,
            style = (if (strong) DshType.bodyStrong else DshType.body).tabularNums(),
            maxLines = 1,
        )
    }
}

@Composable
private fun breakdownLabel(key: String): String = when (key) {
    "system" -> DshS.systemPrompt
    "tools" -> DshS.tools
    "messages" -> DshS.chatMessages
    else -> key
}

@Composable
private fun breakdownColor(key: String): Color = when (key) {
    "system" -> Dsh.systemAccent
    "tools" -> Dsh.toolsAccent
    else -> Dsh.brand400
}

private fun formatPercent(rate: Double?): String =
    if (rate == null) "—" else "${(rate * 100).roundToInt().coerceIn(0, 100)}%"

private fun formatDurationOrDash(ms: Long?): String =
    if (ms == null || ms <= 0L) "—" else compactDuration(ms)

private fun formatSpeed(tokensPerSec: Double?): String {
    if (tokensPerSec == null || !tokensPerSec.isFinite()) return "—"
    return if (tokensPerSec >= 100.0) {
        String.format(Locale.US, "%.0f", tokensPerSec)
    } else {
        String.format(Locale.US, "%.1f", tokensPerSec)
    }
}
