package dev.deeplinks.native.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.backdrops.LayerBackdrop

/**
 * 旧边缘渐隐入口，v4 起改为实色底带（无渐变、无模糊）：内容滚到边缘下方时画一条
 * 画布色的实底，挡住悬浮控件背后的内容。签名保留给尚未迁移的调用点，随 R4 删除。
 */
enum class DshEdgeFadeEdge { Top, Bottom }

object DshEdgeFadeDefaults {
    val overhang: Dp = 0.dp
}

fun Modifier.dshEdgeFade(
    edge: DshEdgeFadeEdge,
    visible: Boolean,
    canvasColor: Color,
    height: Dp,
    backdrop: LayerBackdrop? = null,
): Modifier = if (visible && height > 0.dp) this.background(canvasColor) else this

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

/** 顶部底带的常用高度：状态栏 + [extra]（默认 64dp 顶栏区）。 */
@Composable
fun rememberDshTopFadeHeight(extra: Dp = 64.dp): Dp = with(LocalDensity.current) {
    WindowInsets.statusBars.getTop(this).toDp() + extra
}
