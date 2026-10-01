package dev.deeplinks.native.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.native.DshRadius

/**
 * 白色分组卡表面（v3，docs/visual-rules.md 3.1）：bgCard + card 20dp 圆角 +
 * 0.6dp 发丝边 + 一级柔阴影（借鉴 ChunUI card：border .6 / shadow .07 r8 y3，MIT）。
 *
 * - 发丝边不是 1dp borderSubtle 容器描边（SurfaceHierarchyTest 禁的是那个）：
 *   它是极淡的黑 / 白轮廓，只在白卡落在近白画布上时帮助分层；
 * - 深色模式不画阴影（深底上阴影不可见且发脏），只留白色发丝边；
 * - [DshSectionContainer.Card] 与 [DshGroupCard] 共用，页面不各自拼。
 */
@Composable
fun Modifier.dshCardSurface(): Modifier {
    val shape = RoundedCornerShape(DshRadius.card)
    val dark = Dsh.isDark
    val shadowed = if (dark) {
        this
    } else {
        dropShadow(
            shape = shape,
            shadow = Shadow(
                radius = CARD_SHADOW_RADIUS,
                color = Color.Black.copy(alpha = CARD_SHADOW_ALPHA),
                offset = DpOffset(0.dp, CARD_SHADOW_Y),
            ),
        )
    }
    val hairline = if (dark) Color.White.copy(alpha = CARD_HAIRLINE_ALPHA_DARK) else Color.Black.copy(alpha = CARD_HAIRLINE_ALPHA)
    return shadowed
        .clip(shape)
        .background(Dsh.bgCard)
        .border(CARD_HAIRLINE, hairline, shape)
}

private val CARD_HAIRLINE = 0.6.dp
private const val CARD_HAIRLINE_ALPHA = 0.04f
private const val CARD_HAIRLINE_ALPHA_DARK = 0.06f
private val CARD_SHADOW_RADIUS = 8.dp
private val CARD_SHADOW_Y = 3.dp
private const val CARD_SHADOW_ALPHA = 0.07f
