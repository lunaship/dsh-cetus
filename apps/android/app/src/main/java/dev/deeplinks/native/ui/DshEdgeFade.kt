package dev.deeplinks.native.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 边缘渐隐（2026-10-02 Lody 简化 4.5.3，L9）：替代全宽导航条。
 *
 * Lody 的顶部 / 底部没有条带，用滚动边缘效果让内容在边缘处柔和淡出，控件浮在内容之上。
 * 这里用画布色渐变实现：
 * - 顶部：高度 = 状态栏 + 顶栏区高度，从画布色 @ [EDGE_SOLID_ALPHA] 渐变到 0；
 * - 底部：高度 = 底部操作区实测高度 + 24dp，从 0 渐变到画布色 @ [EDGE_SOLID_ALPHA]。
 *
 * - 只在内容确实滚到边缘下方时显示（调用方传 [visible]，列表顶部时顶部渐隐为 0，避免顶部发灰）；
 * - 层级：内容（采样源）→ 渐隐层 → 玻璃控件；**渐隐不得进入采样源**，否则控件会采到渐隐色而发灰；
 * - API 31+ 的分段模糊（近似 Lody `.soft`）留作真机调校项，首轮只做渐变。
 */
enum class DshEdgeFadeEdge { Top, Bottom }

/** 渐隐最浓端的画布色浓度。 */
private const val EDGE_SOLID_ALPHA = 0.94f

/**
 * 在本节点绘制上 / 下边缘渐隐。[height] 是渐隐带总高（顶部调用方把状态栏高度算进去）。
 * [canvasColor] 是页面画布色：聊天页传 `bgCard`，其他页传 `bgBase`（4.5.3）。
 */
fun Modifier.dshEdgeFade(
    edge: DshEdgeFadeEdge,
    visible: Boolean,
    canvasColor: Color,
    height: Dp,
): Modifier = composed {
    val solid = canvasColor.copy(alpha = EDGE_SOLID_ALPHA)
    drawBehind {
        if (!visible || height <= 0.dp) return@drawBehind
        val h = height.toPx().coerceAtMost(size.height)
        val top = when (edge) {
            DshEdgeFadeEdge.Top -> 0f
            DshEdgeFadeEdge.Bottom -> size.height - h
        }
        val brush = when (edge) {
            DshEdgeFadeEdge.Top -> Brush.verticalGradient(
                colorStops = arrayOf(0f to solid, 1f to Color.Transparent),
                startY = top,
                endY = top + h,
            )
            DshEdgeFadeEdge.Bottom -> Brush.verticalGradient(
                colorStops = arrayOf(0f to Color.Transparent, 1f to solid),
                startY = top,
                endY = top + h,
            )
        }
        drawRect(brush = brush, topLeft = Offset(0f, top), size = Size(size.width, h))
    }
}

/**
 * 顶部渐隐的常用高度：状态栏 + 64dp（4.5.3）。在 Composable 里取当前窗口状态栏 inset。
 */
@Composable
fun rememberDshTopFadeHeight(extra: Dp = 64.dp): Dp = with(LocalDensity.current) {
    WindowInsets.statusBars.getTop(this).toDp() + extra
}
