package dev.deeplinks.native

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import dev.deeplinks.core.Dsh
import dev.deeplinks.native.ui.DshGlassFullWidthShape
import dev.deeplinks.native.ui.DshGlassTier
import dev.deeplinks.native.ui.dshGlass

/**
 * 顶栏 / 底部输入区共用：省电或减弱动画时退化为不透明，否则 92 % 半透明。
 * 可选的 0.5 dp 分隔线（顶栏画在顶部，输入区画在底部）。
 *
 * 传入 [backdrop]（由同级内容区 `layerBackdrop` 录制）且 API 31+ 时改为真毛玻璃：
 * 采样下方内容做模糊 + 提饱和，再叠一层 [GLASS_SURFACE_ALPHA] 的底色保证文字对比度。
 * 截图预览（layoutlib 不支持 RenderEffect）与省电 / 关动画时仍走纯色半透明。
 * 实现基于 kyant0/backdrop（Apache-2.0），用法参考 Clarklevis1995/dsh-mobile 的 DshLiquidGlass。
 */
/**
 * 迁移适配（2026-10-01 v2 方案 6.2）：材质与回退实现已收敛到
 * [dev.deeplinks.native.ui.dshGlass]（Navigation 档），本入口只保留兼容签名——
 * 现有调用点（聊天顶栏 overlayTopChrome、设置/设备 DshPageScaffold）自动获得
 * 导航玻璃参数（模糊 16dp、底色 0.84/0.88），不需要各自改动。
 * 底部输入区请直接用 dshGlass(Floating, 圆角形状)。
 */
@Composable
fun Modifier.dshTranslucent(
    base: Color = Dsh.bgBase,
    showDivider: Boolean = false,
    dividerAtTop: Boolean = false,
    backdrop: LayerBackdrop? = null,
): Modifier = composed {
    val dividerColor = Dsh.borderSubtle
    this
        .then(
            Modifier.dshGlass(
                tier = DshGlassTier.Navigation,
                backdrop = backdrop,
                shape = DshGlassFullWidthShape,
                base = base,
            ),
        )
        .drawBehind {
            if (showDivider) {
                val y = if (dividerAtTop) 0f else size.height
                drawLine(
                    color = dividerColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = (1.dp / 2).toPx(),
                )
            }
        }
}
