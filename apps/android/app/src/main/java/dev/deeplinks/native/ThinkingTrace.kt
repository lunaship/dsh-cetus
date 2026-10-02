package dev.deeplinks.native

import dev.deeplinks.native.DshIconSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.dshRipple
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.L
import dev.deeplinks.core.DshType

/** 对齐参考组件 `cubic-bezier(0.23, 1, 0.32, 1)`。 */
private val ThinkingEase = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

/**
 * 四角星（思考条图标）。不复用 DSH 原子轨图标，避免和官方 Chat 思考标识撞车。
 */
private val StarFour16: ImageVector
    get() {
        val cached = _starFour16
        if (cached != null) return cached
        return ImageVector.Builder(
            name = "StarFour16",
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                lineTo(14.4f, 9.2f)
                lineTo(22f, 12f)
                lineTo(14.4f, 14.8f)
                lineTo(12f, 22f)
                lineTo(9.6f, 14.8f)
                lineTo(2f, 12f)
                lineTo(9.6f, 9.2f)
                close()
            }
        }.build().also { _starFour16 = it }
    }

private var _starFour16: ImageVector? = null

/**
 * DeepSeek 签名思考轨迹：凹进面板 + 左侧 2dp 品牌蓝竖条；
 * 进行中扫光标题，正文默认收起，用户点击后展开。
 */
@Composable
internal fun ThinkingTrace(
    working: Boolean,
    activeLabel: String,
    doneLabel: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    body: @Composable ColumnScope.() -> Unit,
) {
    // v4 4.6：展开后是一条细竖线串起正文，不做凹进面板 / 品牌色竖条
    val rail = Dsh.outline
    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                if (expanded) {
                    drawLine(
                        color = rail,
                        start = Offset(8.dp.toPx(), 40.dp.toPx()),
                        end = Offset(8.dp.toPx(), size.height - 8.dp.toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ThinkingHeader(
                working = working,
                activeLabel = activeLabel,
                doneLabel = doneLabel,
                expanded = expanded,
                onToggle = onToggle,
            )
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = tween(motionDuration(400), easing = ThinkingEase),
                ) + fadeIn(animationSpec = tween(motionDuration(240), easing = ThinkingEase)),
                exit = shrinkVertically(
                    animationSpec = tween(motionDuration(280), easing = FastOutSlowInEasing),
                ) + fadeOut(animationSpec = tween(motionDuration(180))),
            ) {
                Column(
                    modifier = Modifier.padding(start = DshSpace.s20, top = DshSpace.s4, bottom = DshSpace.s8),
                    content = body,
                )
            }
        }
    }
}

/** 等待首 token：四角星 + 扫光「思考中」+ 计时（安静 chrome，不用像素格）。 */
@Composable
internal fun ThinkingStatusRow(
    elapsedSec: Long,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            StarFour16,
            contentDescription = null,
            tint = Dsh.brand400,
            modifier = Modifier.size(DshIconSize.sm),
        )
        Spacer(Modifier.width(DshSpace.s8))
        ShimmerLabel(text = L.thinkingActive, working = true)
        Spacer(Modifier.width(DshSpace.s8))
        Text(
            formatThinkingElapsed(elapsedSec),
            style = DshType.label,
            color = Dsh.labelTertiary,
            maxLines = 1,
        )
    }
}

internal fun formatThinkingElapsed(elapsedSec: Long): String {
    if (elapsedSec < 60) return "${elapsedSec}s"
    return "${elapsedSec / 60}m ${elapsedSec % 60}s"
}

@Composable
private fun ThinkingHeader(
    working: Boolean,
    activeLabel: String,
    doneLabel: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(motionDuration(300), easing = ThinkingEase),
        label = "thinkingChevron",
    )
    val expandLabel = if (expanded) L.collapse else L.expand
    val labelFadeIn = motionDuration(350)
    val labelFadeOut = motionDuration(180)
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(DshRadius.control))
            .clickable(
                interactionSource = interaction,
                indication = dshRipple(),
                onClick = onToggle,
            )
            .semantics {
                role = Role.Button
                stateDescription = expandLabel
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = DshSpace.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                StarFour16,
                contentDescription = null,
                tint = Dsh.labelSecondary,
                modifier = Modifier.size(DshIconSize.sm),
            )
            Spacer(Modifier.width(DshSpace.s8))
            AnimatedContent(
                targetState = working,
                transitionSpec = {
                    fadeIn(tween(labelFadeIn)) togetherWith fadeOut(tween(labelFadeOut))
                },
                label = "thinkingLabel",
            ) { isWorking ->
                if (isWorking) {
                    ShimmerLabel(text = activeLabel, working = true)
                } else {
                    Text(
                        doneLabel,
                        style = DshType.supporting,
                        color = Dsh.labelSecondary,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(DshSpace.s8))
            Icon(
                ChevronRightOutline16,
                contentDescription = expandLabel,
                tint = Dsh.labelSecondary,
                modifier = Modifier
                    .size(DshIconSize.xs)
                    .graphicsLayer { rotationZ = rotation / 2 },
            )
        }
    }
}

/**
 * 文字扫光：动画值只在 Canvas 绘制阶段读取，避免每帧重组。
 * reduce-motion 时退化为静态次要色文本。
 */
/** 进行中的标签：v4 不做高光扫过，静态正文色 13sp；[working] 为假时次要色。 */
@Composable
internal fun ShimmerLabel(text: String, working: Boolean) {
    Text(text, style = DshType.supporting, color = if (working) Dsh.labelPrimary else Dsh.labelSecondary, maxLines = 1)
}

internal fun thoughtDoneLabel(durationMs: Long?, elapsedSec: Long?): String {
    val seconds = when {
        durationMs != null && durationMs > 0 -> (durationMs / 1000L).coerceAtLeast(1L)
        elapsedSec != null && elapsedSec > 0 -> elapsedSec
        else -> null
    }
    return if (seconds != null) L.thoughtForSeconds.format(seconds) else L.thoughtDone
}
