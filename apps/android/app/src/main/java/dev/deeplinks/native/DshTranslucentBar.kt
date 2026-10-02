package dev.deeplinks.native

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import dev.deeplinks.core.Dsh

/**
 * 顶栏 / 底部输入区共用底：v4 起为实色 [base]（无模糊、无半透明），
 * 可选 1dp 分隔线（顶栏画在底部，输入区画在顶部）。[backdrop] 不再生效，签名随 R4 清理。
 */
@Composable
fun Modifier.dshTranslucent(
    base: Color = Dsh.bgBase,
    showDivider: Boolean = false,
    dividerAtTop: Boolean = false,
    backdrop: LayerBackdrop? = null,
): Modifier = composed {
    val dividerColor = Dsh.outline
    this
        .background(base)
        .drawBehind {
            if (showDivider) {
                val y = if (dividerAtTop) 0f else size.height
                drawLine(
                    color = dividerColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
}
