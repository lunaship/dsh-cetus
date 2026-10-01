package dev.deeplinks.native.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.kyant.backdrop.backdrops.LayerBackdrop
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.native.DshIconSize
import dev.deeplinks.native.DshRadius
import dev.deeplinks.native.DshSpace
import dev.deeplinks.native.DshTouch
import dev.deeplinks.native.isReduceMotionEnabled

/**
 * 悬浮玻璃控件（2026-10-02 Lody 简化 3.1/4.5，L10/L12）：
 * - [DshGlassCapsule]：48dp 高全圆胶囊，内部 1–2 个热区（图标或「图标 + 文字」）；
 * - [DshGlassCircle]：48dp 圆钮。
 *
 * 两者是所有页面顶栏操作、首页底部操作、聊天返回 / ⋯、输入区「+」的唯一形态：
 * Control 档液态玻璃（折射 + 边缘高光 + 柔和阴影，4.5.2）、按压回弹 + 高光增强
 * （4.5.4，无涟漪——玻璃上的涟漪像脏点）、48dp 热区、Button 语义。
 * 页面不得自行加 clickable 指示效果或再造形状。
 */

/** 按压高光增益（4.5.4：+0.25；reduce-motion / 回退态只改高光不缩放）。 */
private const val PRESS_HIGHLIGHT_BOOST = 0.25f

/** 按压回弹（对标 Lody isInteractive）：1 → 1.04 spring；系统「移除动画」时不动。 */
private fun Modifier.dshGlassPressBounce(
    interactionSource: MutableInteractionSource,
): Modifier = composed {
    if (isReduceMotionEnabled()) return@composed Modifier
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 1.04f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "dshGlassPressBounce",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Control 档玻璃 + 回弹 + 无涟漪点击的共用内层。
 * 圆钮与胶囊共用；48dp 胶囊 = 全圆，48dp 圆钮同形（全圆半径在方形上是圆）。
 */
@Composable
private fun Modifier.dshGlassControlSurface(
    backdrop: LayerBackdrop?,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    return modifier
        .dshGlassPressBounce(interactionSource)
        .dshGlass(
            tier = DshGlassTier.Control,
            backdrop = backdrop,
            shape = DshControlGlassShape,
            highlightBoost = if (pressed) PRESS_HIGHLIGHT_BOOST else 0f,
        )
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        )
        .semantics {
            role = Role.Button
            if (contentDescription != null) this.contentDescription = contentDescription
        }
}

/** 控件玻璃形状：DshRadius.full；满足 lens 的 CornerBasedShape 要求。 */
internal val DshControlGlassShape = RoundedCornerShape(DshRadius.full)

/**
 * 48dp 圆形玻璃钮：返回、⋯、圆形 +、关闭等顶栏 / 输入区动作的唯一形态。
 * 图标默认 labelPrimary；强调场景（圆形 +）由调用方传 [Dsh.accentIcon]。
 */
@Composable
fun DshGlassCircle(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconTint: Color = Dsh.labelPrimary,
    backdrop: LayerBackdrop? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    Box(
        modifier = modifier
            .size(DshTouch.min)
            .dshGlassControlSurface(
                backdrop = backdrop,
                interactionSource = interactionSource,
                onClick = onClick,
                enabled = enabled,
                contentDescription = contentDescription,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) iconTint else Dsh.labelDimmed,
            modifier = Modifier.size(DshIconSize.md),
        )
    }
}

/**
 * 48dp 高全圆玻璃胶囊：顶栏身份 / 操作组、首页搜索等悬浮操作的唯一形态。
 * [onClick] 为 null 时是纯展示胶囊（品牌位）：无点击语义，只剩玻璃表面。
 * [content] 里放 1–2 个 48dp 热区的图标（[DshGlassCapsuleIcon]）或「图标 + 文字」。
 */
@Composable
fun DshGlassCapsule(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    backdrop: LayerBackdrop? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .heightIn(min = DshTouch.min)
            .then(
                if (onClick != null) {
                    Modifier.dshGlassControlSurface(
                        backdrop = backdrop,
                        interactionSource = interactionSource,
                        onClick = onClick,
                        enabled = enabled,
                        contentDescription = contentDescription,
                    )
                } else {
                    Modifier.dshGlass(
                        tier = DshGlassTier.Control,
                        backdrop = backdrop,
                        shape = DshControlGlassShape,
                    )
                },
            )
            .padding(horizontal = DshSpace.s16),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 胶囊内单个 48dp 热区图标（父级 [DshGlassCapsule] 有整体语义时用它放并行动作）。 */
@Composable
fun RowScope.DshGlassCapsuleIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconTint: Color = Dsh.labelPrimary,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    Box(
        modifier = modifier
            .size(DshTouch.min)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) iconTint else Dsh.labelDimmed,
            modifier = Modifier.size(DshIconSize.md),
        )
    }
}

/** 胶囊内文字（「DeepLinks」品牌位 / 「搜索会话」占位）：15/22 中性。 */
@Composable
fun DshGlassCapsuleLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Dsh.labelPrimary,
) {
    Text(
        text,
        color = color,
        style = DshType.title,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
