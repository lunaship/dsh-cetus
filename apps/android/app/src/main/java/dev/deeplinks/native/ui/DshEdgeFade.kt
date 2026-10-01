package dev.deeplinks.native.ui

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import dev.deeplinks.native.DshDuration
import dev.deeplinks.native.motionDuration

/**
 * 边缘渐隐（2026-10-02 Lody 简化 4.5.3，L9；v3 渐进模糊）：替代全宽导航条。
 *
 * Lody 的顶部 / 底部没有条带，用滚动边缘效果让内容在边缘处柔和淡出，控件浮在内容之上。
 * 两层叠加（借鉴 ChunUI CCChromeBacking，MIT）：
 * 1. **渐进模糊**（API 31+、有采样源、未省电 / 关动画、非预览）：对背后内容做
 *    [EDGE_BLUR_MAX] 模糊，再用纵向蒙版让模糊从边缘处满强度过渡到 0——内容滑入时
 *    「先糊后隐」，近似 iOS `.soft` 滚动边缘；
 * 2. **画布色渐变**：边缘处画布色 @ [EDGE_SOLID_ALPHA] → 0。无模糊时只有这一层。
 *
 * - 只在内容确实滚到边缘下方时显示（调用方传 [visible]，切换时 200ms 淡入淡出）；
 * - 层级：内容（采样源）→ 渐隐层 → 玻璃控件；**渐隐不得进入采样源**，否则控件会采到渐隐色而发灰；
 * - 带区高度 = 本节点高度（调用方用 [height] 指定，顶部把状态栏与顶栏算进去，
 *   滚动态再加 [DshEdgeFadeDefaults.overhang]）。
 */
enum class DshEdgeFadeEdge { Top, Bottom }

/** 渐隐最浓端的画布色浓度（ChunUI 0.96）。 */
private const val EDGE_SOLID_ALPHA = 0.96f

/** 渐变在带区中段的浓度：让顶栏区基本实底、伸出部分柔和收尾。 */
private const val EDGE_MID_ALPHA = 0.78f

/** 渐进模糊的最大半径（ChunUI variable blur max 16）。 */
private val EDGE_BLUR_MAX = 16.dp

object DshEdgeFadeDefaults {
    /** 顶部渐隐在顶栏下沿之外再伸出的高度（滚动态）：内容在这里开始被柔化。 */
    val overhang: Dp = 48.dp
}

/**
 * 在本节点绘制上 / 下边缘渐隐。[height] 是渐隐带总高（顶部调用方把状态栏高度算进去）。
 * [canvasColor] 是页面画布色：聊天页传 `bgCard`，其他页传 `bgBase`（4.5.3）。
 * [backdrop] 是内容层的采样源；为空或不满足条件时退回纯渐变。
 */
fun Modifier.dshEdgeFade(
    edge: DshEdgeFadeEdge,
    visible: Boolean,
    canvasColor: Color,
    height: Dp,
    backdrop: LayerBackdrop? = null,
): Modifier = composed {
    val reduceTransparency by rememberDshReduceTransparency()
    val preview = LocalInspectionMode.current
    val shown by animateFloatAsState(
        targetValue = if (visible && height > 0.dp) 1f else 0f,
        animationSpec = tween(motionDuration(DshDuration.normal)),
        label = "dshEdgeFade",
    )
    val canBlur = backdrop != null && !reduceTransparency && !preview &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val solid = canvasColor.copy(alpha = EDGE_SOLID_ALPHA)
    val mid = canvasColor.copy(alpha = EDGE_MID_ALPHA)

    val tint = Modifier
        .graphicsLayer {
            alpha = shown
            // 模糊层要在独立离屏缓冲里做蒙版，整体透明度也一起作用在缓冲上
            compositingStrategy = if (canBlur) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        }
        .drawWithContent {
            if (shown <= 0f) return@drawWithContent
            drawContent()
            val brush = edgeBrush(edge, arrayOf(0f to solid, 0.5f to mid, 1f to Color.Transparent), size.height)
            drawRect(brush = brush, topLeft = Offset.Zero, size = Size(size.width, size.height))
        }
    if (!canBlur || backdrop == null) return@composed this.then(tint)

    val blurLayer = Modifier
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            // 渐进蒙版：边缘处保留满强度模糊，向内容侧过渡到 0
            val mask = edgeBrush(
                edge,
                arrayOf(0f to Color.Black, 0.45f to Color.Black, 1f to Color.Transparent),
                size.height,
            )
            drawRect(brush = mask, blendMode = BlendMode.DstIn)
        }
        .drawPlainBackdrop(
            backdrop = backdrop,
            shape = { RectangleShape },
            effects = { blur(EDGE_BLUR_MAX.toPx()) },
        )
    this.then(tint).then(blurLayer)
}

/** 从边缘向内容侧的纵向渐变：Top 自上而下，Bottom 自下而上。 */
private fun edgeBrush(edge: DshEdgeFadeEdge, stops: Array<Pair<Float, Color>>, height: Float): Brush =
    when (edge) {
        DshEdgeFadeEdge.Top -> Brush.verticalGradient(colorStops = stops, startY = 0f, endY = height)
        DshEdgeFadeEdge.Bottom -> Brush.verticalGradient(colorStops = stops, startY = height, endY = 0f)
    }

/**
 * 一对顶 / 底边缘渐隐（首页、聊天页共用）：zIndex 低于悬浮 chrome、高于内容层。
 * [topHeight] 是顶部带区总高（滚动态伸出的 [DshEdgeFadeDefaults.overhang] 由调用方算进去）；
 * [backdrop] 是内容层采样源（overlayBackdropSource），渐隐层本身不进采样源。
 */
@Composable
fun BoxScope.DshEdgeFades(
    topHeight: Dp,
    bottomHeight: Dp,
    topVisible: Boolean,
    bottomVisible: Boolean,
    canvasColor: Color,
    backdrop: LayerBackdrop?,
) {
    for (edge in DshEdgeFadeEdge.entries) {
        val top = edge == DshEdgeFadeEdge.Top
        val h = if (top) topHeight else bottomHeight
        Box(
            Modifier
                .align(if (top) Alignment.TopCenter else Alignment.BottomCenter)
                .fillMaxWidth()
                .height(h)
                .zIndex(0.5f)
                .dshEdgeFade(
                    edge = edge,
                    visible = if (top) topVisible else bottomVisible,
                    canvasColor = canvasColor,
                    height = h,
                    backdrop = backdrop,
                ),
        )
    }
}

/**
 * 顶部渐隐的常用高度：状态栏 + [extra]（默认 64dp 顶栏区）。在 Composable 里取当前窗口状态栏 inset。
 */
@Composable
fun rememberDshTopFadeHeight(extra: Dp = 64.dp): Dp = with(LocalDensity.current) {
    WindowInsets.statusBars.getTop(this).toDp() + extra
}
