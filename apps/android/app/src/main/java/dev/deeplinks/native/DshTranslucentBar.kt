package dev.deeplinks.native

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.os.Build
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalInspectionMode
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import dev.deeplinks.core.Dsh

/**
 * 顶栏 / 底部输入区共用：省电或减弱动画时退化为不透明，否则 92 % 半透明。
 * 可选的 0.5 dp 分隔线（顶栏画在顶部，输入区画在底部）。
 *
 * 传入 [backdrop]（由同级内容区 `layerBackdrop` 录制）且 API 31+ 时改为真毛玻璃：
 * 采样下方内容做模糊 + 提饱和，再叠一层 [GLASS_SURFACE_ALPHA] 的底色保证文字对比度。
 * 截图预览（layoutlib 不支持 RenderEffect）与省电 / 关动画时仍走纯色半透明。
 * 实现基于 kyant0/backdrop（Apache-2.0），用法参考 Clarklevis1995/dsh-mobile 的 DshLiquidGlass。
 */
@Composable
fun Modifier.dshTranslucent(
    base: Color = Dsh.bgBase,
    showDivider: Boolean = false,
    dividerAtTop: Boolean = false,
    backdrop: LayerBackdrop? = null,
): Modifier = composed {
    val context = LocalContext.current
    var reduceTransparency by remember(context) { mutableStateOf(false) }

    LaunchedEffect(context) {
        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager
        val powerSave = powerManager?.isPowerSaveMode ?: false
        val animatorScale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        reduceTransparency = powerSave || animatorScale == 0f
    }

    val alpha = if (reduceTransparency) 1f else 0.92f
    val dividerColor = Dsh.borderSubtle
    val glass = backdrop != null && !reduceTransparency && !LocalInspectionMode.current &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val surface = if (glass && backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { RectangleShape },
            effects = {
                vibrancy()
                blur(GLASS_BLUR_RADIUS.toPx())
            },
            highlight = null,
            shadow = null,
            onDrawSurface = { drawRect(base.copy(alpha = GLASS_SURFACE_ALPHA)) },
        )
    } else {
        Modifier.drawBehind { drawRect(base.copy(alpha = alpha)) }
    }
    surface.drawBehind {
        if (showDivider) {
            val y = if (dividerAtTop) 0f else size.height
            drawLine(
                color = dividerColor,
                start = androidx.compose.ui.geometry.Offset(0f, y),
                end = androidx.compose.ui.geometry.Offset(size.width, y),
                strokeWidth = (1.dp / 2).toPx(),
            )
        }
    }
}

private val GLASS_BLUR_RADIUS = 24.dp
private const val GLASS_SURFACE_ALPHA = 0.78f
