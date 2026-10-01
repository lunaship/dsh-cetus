package dev.deeplinks.native.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import dev.deeplinks.core.Dsh

/**
 * 液态玻璃材质层（docs/visual-rules.md 2.1，2026-10-01 v2 方案第 4 节）。
 *
 * 两种档位（4.2 三种表面用途）：
 * - [DshGlassTier.Navigation]：较浓底色、适量模糊、无折射、无阴影——页面顶栏等导航操作区；
 * - [DshGlassTier.Floating]：圆角浮岛——外圈边缘轻高光、轻折射与柔和阴影（输入区外圈）。
 *
 * 回退阶梯（4.5）：API 33+ 模糊 + 折射 → API 31–32 模糊 → API 26–30 / 预览 / 省电 /
 * 关动画 / 无采样源 → 稳定纯色表面。回退不留透明空洞或读不清的文字。
 *
 * 实现基于 kyant0/backdrop 1.0.6（Apache-2.0）。lens 参数单位是 px（在效果作用域内由
 * dp 换算）、仅 API 33+、且形状必须是圆角（`CornerBasedShape`）——1.0.6 对
 * `RectangleShape` 直接抛异常，因此折射只对浮动档且形状为圆角时生效（R10）。
 */

enum class DshGlassTier {
    Navigation,
    Floating,
}

/**
 * R10：折射共用开关。关掉即回到普通模糊（第 10 节回退链第一级）；默认只在输入区
 * 外圈开轻档，导航档永远不折射。真机性能不达标时先关这里。
 */
@Volatile
var dshGlassRefractionEnabled: Boolean = true

// 4.3 首轮调参起点（进入真机调校的起点，不是已验证终值）：
private val NAV_BLUR = 16.dp
private val NAV_ALPHA_LIGHT = 0.84f
private val NAV_ALPHA_DARK = 0.88f
private val FLOAT_BLUR = 12.dp
private val FLOAT_ALPHA_LIGHT = 0.68f
private val FLOAT_ALPHA_DARK = 0.76f
private val FLOAT_REFRACTION_HEIGHT = 8.dp
private val FLOAT_REFRACTION_AMOUNT = 12.dp

/** D01：玻璃回退状态（省电或动画时长为 0）要跟手，预览（layoutlib）恒为 false。 */
@Composable
fun rememberDshReduceTransparency(): State<Boolean> {
    val state = remember { mutableStateOf(false) }
    if (LocalInspectionMode.current) return state
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(context, lifecycleOwner) {
        val resolver = context.contentResolver
        fun read(): Boolean {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val powerSave = powerManager?.isPowerSaveMode ?: false
            val animatorScale = Settings.Global.getFloat(
                resolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
            return powerSave || animatorScale == 0f
        }

        val lifecycleObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) state.value = read()
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        val powerSaveReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                state.value = read()
            }
        }
        val powerFilter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(powerSaveReceiver, powerFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(powerSaveReceiver, powerFilter)
        }

        val animatorObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                state.value = read()
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            animatorObserver,
        )

        state.value = read()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            context.unregisterReceiver(powerSaveReceiver)
            resolver.unregisterContentObserver(animatorObserver)
        }
    }
    return state
}

/**
 * 玻璃表面材质。[shape] 全宽导航用 [RectangleShape]，浮岛用圆角形状；
 * [base] 是叠加底色（与页面画布同族，浅色 `bgBase` / 聊天白底 `bgCard`）。
 */
@Composable
fun Modifier.dshGlass(
    tier: DshGlassTier,
    backdrop: LayerBackdrop?,
    shape: Shape,
    base: Color = Dsh.bgBase,
): Modifier = composed {
    val reduceTransparency by rememberDshReduceTransparency()
    val isPreview = LocalInspectionMode.current
    val dark = Dsh.isDark

    val canBlur = backdrop != null && !reduceTransparency && !isPreview &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // R10：折射三重门槛——共用开关、API 33+（RuntimeShader）、圆角形状（矩形会抛异常）。
    // 导航档永远不折射（4.2：全宽矩形不做透镜）。
    val canRefract = canBlur && dshGlassRefractionEnabled &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        tier == DshGlassTier.Floating && shape is CornerBasedShape

    // 回退（省电 / 关动画）完全实体化：信息与层级不受影响，不留透明空洞
    val surfaceAlpha = when {
        !canBlur -> if (reduceTransparency) 1f else 0.92f
        tier == DshGlassTier.Navigation -> if (dark) NAV_ALPHA_DARK else NAV_ALPHA_LIGHT
        else -> if (dark) FLOAT_ALPHA_DARK else FLOAT_ALPHA_LIGHT
    }

    val glass: Modifier = if (canBlur && backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur((if (tier == DshGlassTier.Navigation) NAV_BLUR else FLOAT_BLUR).toPx())
                if (canRefract) {
                    // 参数单位 px（R10）；高度必须适配最小圆角，避免边角不连续
                    lens(FLOAT_REFRACTION_HEIGHT.toPx(), FLOAT_REFRACTION_AMOUNT.toPx())
                }
            },
            // 浮动档边缘轻高光（避免完整亮白描边）与小范围柔和阴影（不扩散成灰雾）
            highlight = if (tier == DshGlassTier.Floating) {
                { Highlight(width = 0.8.dp, alpha = 0.14f) }
            } else {
                null
            },
            shadow = if (tier == DshGlassTier.Floating) {
                { Shadow(radius = 14.dp, alpha = 0.16f) }
            } else {
                null
            },
            onDrawSurface = { drawRect(base.copy(alpha = surfaceAlpha)) },
        )
    } else {
        Modifier.drawBehind { drawRect(base.copy(alpha = surfaceAlpha)) }
    }
    glass
}

/** 导航档全宽玻璃的常用形状；显式命名以免调用点各自写 RectangleShape。 */
val DshGlassFullWidthShape: Shape = RectangleShape
