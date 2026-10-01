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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow as ComposeShadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
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
 * 液态玻璃材质层（docs/visual-rules.md 2.1，2026-10-02 Lody 简化 4.5）。
 *
 * 三种档位（4.5.2）：
 * - [DshGlassTier.Control]：小尺寸圆角控件专用（胶囊 / 圆钮 / 输入胶囊），带折射、
 *   边缘高光、柔和阴影——对标 `UIGlassEffect(.regular)` + `isInteractive`（L10）；
 * - [DshGlassTier.Floating]：圆角浮岛——外圈边缘轻高光、轻折射与柔和阴影；
 * - [DshGlassTier.Navigation]：**已下线（L9）**。全宽导航条改为边缘渐隐（DshEdgeFade），
 *   档位只为本 PR 中间提交的存量调用点过渡期保留，随 PR 内后续提交删除。
 *
 * 回退阶梯（4.5.5）：API 33+ 模糊 + 折射 → API 31–32 模糊 → API 26–30 / 预览 / 省电 /
 * 关动画 / 无采样源 → 按 shape 绘制的实色表面（Control 档为拟物回退，见 [dshGlassFallback]）。
 * 回退不留透明空洞或读不清的文字，也不得把圆角控件画成方块（v3 修复）。
 *
 * 实现基于 kyant0/backdrop 1.0.6（Apache-2.0）。lens 参数单位是 px（在效果作用域内由
 * dp 换算）、仅 API 33+、且形状必须是圆角（`CornerBasedShape`）——1.0.6 对
 * `RectangleShape` 直接抛异常，因此折射只对圆角形状生效（R10）。
 * Shadow 的 alpha 参数是乘在 color 上的系数：浓度写进 color，alpha 恒为 1。
 */

enum class DshGlassTier {
    /** 小尺寸圆角控件：悬浮胶囊 / 圆钮 / 输入胶囊（2026-10-02 L10）。 */
    Control,

    /** 圆角浮岛：改动面板等大浮层。 */
    Floating,

    /** 全宽导航条：L9 已下线，过渡期保留给存量调用点。 */
    @Deprecated("全宽导航条已下线（L9），改用 DshEdgeFade + Control 档控件")
    Navigation,
}

/**
 * 玻璃表面浓度档（4.5.2）：
 * - [DshGlassSurface.Standard]：控件内只有图标 / 短文字，常规浓度；
 * - [DshGlassSurface.Strong]：输入胶囊等承载必读文字的表面（L11 可读性下限）。
 */
enum class DshGlassSurface { Standard, Strong }

/**
 * R10：折射共用开关。关掉即回到普通模糊（第 10 节回退链第一级）；真机性能不达标时先关这里。
 */
@Volatile
var dshGlassRefractionEnabled: Boolean = true

/**
 * 玻璃参数表（4.5.2，按 tier × 明暗 × 表面浓度）。
 * 所有数值集中在此，页面不得写数字（沿用门禁）；真机调校只改这里。
 */
data class DshGlassSpec(
    /** 背后内容的模糊半径。 */
    val blur: Dp,
    /** 折射透镜高度与强度（px 由调用处换算；height 不超过最小圆角半径）。 */
    val refractionHeight: Dp,
    val refractionAmount: Dp,
    /** 表面叠加底色与浓度。 */
    val surfaceColorLight: Color,
    val surfaceColorDark: Color,
    val surfaceAlphaLight: Float,
    val surfaceAlphaDark: Float,
    /** 边缘高光（亮边）。 */
    val highlightWidth: Dp,
    val highlightAlphaLight: Float,
    val highlightAlphaDark: Float,
    /** 柔和阴影（浮起感；alpha 参数恒 1，浓度写在 color 里）。 */
    val shadowRadius: Dp,
    val shadowAlphaLight: Float,
    val shadowAlphaDark: Float,
    /** 回退（API 26–30 / 无采样源）表面浓度；颜色取 bgCard（浅）/ bgSubtle（深）。 */
    val fallbackAlpha: Float,
) {
    fun surfaceColor(dark: Boolean): Color = if (dark) surfaceColorDark else surfaceColorLight
    fun surfaceAlpha(dark: Boolean): Float = if (dark) surfaceAlphaDark else surfaceAlphaLight
    fun highlightAlpha(dark: Boolean): Float = if (dark) highlightAlphaDark else highlightAlphaLight
    fun shadowAlpha(dark: Boolean): Float = if (dark) shadowAlphaDark else shadowAlphaLight
}

private val NAVIGATION_SPEC = DshGlassSpec(
    blur = 16.dp,
    refractionHeight = 0.dp,
    refractionAmount = 0.dp,
    surfaceColorLight = Color.Unspecified, // 全宽条叠加画布色（base 参数），不用固定白
    surfaceColorDark = Color.Unspecified,
    surfaceAlphaLight = 0.84f,
    surfaceAlphaDark = 0.88f,
    highlightWidth = 0.dp,
    highlightAlphaLight = 0f,
    highlightAlphaDark = 0f,
    shadowRadius = 0.dp,
    shadowAlphaLight = 0f,
    shadowAlphaDark = 0f,
    fallbackAlpha = 0.92f,
)

private val FLOATING_SPEC = DshGlassSpec(
    blur = 12.dp,
    refractionHeight = 8.dp,
    refractionAmount = 12.dp,
    surfaceColorLight = Color.Unspecified,
    surfaceColorDark = Color.Unspecified,
    surfaceAlphaLight = 0.68f,
    surfaceAlphaDark = 0.76f,
    highlightWidth = 0.8.dp,
    highlightAlphaLight = 0.14f,
    highlightAlphaDark = 0.14f,
    shadowRadius = 14.dp,
    shadowAlphaLight = 0.16f,
    shadowAlphaDark = 0.16f,
    fallbackAlpha = 0.92f,
)

/** Control 档首轮参数（4.5.2 真机调校起点；浅 / 深分别定，A15/A16/A17 验收）。 */
@Composable
private fun controlSpec(surface: DshGlassSurface): DshGlassSpec {
    // 深色表面取 bgCard（#1C1D21）：与重设计稿面板面同源，避免新裸色值
    val darkBase = Dsh.bgCard
    return when (surface) {
        DshGlassSurface.Standard -> DshGlassSpec(
            blur = 4.dp,
            refractionHeight = 12.dp,
            refractionAmount = 24.dp,
            surfaceColorLight = Color.White,
            surfaceColorDark = darkBase,
            surfaceAlphaLight = 0.50f,
            surfaceAlphaDark = 0.55f,
            highlightWidth = 1.dp,
            highlightAlphaLight = 0.55f,
            highlightAlphaDark = 0.28f,
            shadowRadius = 16.dp,
            shadowAlphaLight = 0.08f,
            shadowAlphaDark = 0.32f,
            fallbackAlpha = 0.96f,
        )
        DshGlassSurface.Strong -> DshGlassSpec(
            blur = 4.dp,
            refractionHeight = 12.dp,
            refractionAmount = 24.dp,
            surfaceColorLight = Color.White,
            surfaceColorDark = darkBase,
            // L11 可读性下限：输入文字对比实测不达 4.5:1 时提到 0.82 / 0.84（A17）
            surfaceAlphaLight = 0.72f,
            surfaceAlphaDark = 0.74f,
            highlightWidth = 1.dp,
            highlightAlphaLight = 0.55f,
            highlightAlphaDark = 0.28f,
            shadowRadius = 16.dp,
            shadowAlphaLight = 0.08f,
            shadowAlphaDark = 0.32f,
            fallbackAlpha = 0.96f,
        )
    }
}

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
 * 玻璃表面材质。[shape] 全宽导航（过渡期）用 [RectangleShape]，控件与浮岛用圆角形状；
 * [base] 是叠加底色（与页面画布同族，浅色 `bgBase` / 聊天白底 `bgCard`）——
 * 仅 Navigation / Floating 使用；Control 档表面色固定由 [DshGlassSpec] 给出。
 * [surface] 选浓度档（输入胶囊等承载文字的表面用 Strong，L11）。
 */
@Composable
fun Modifier.dshGlass(
    tier: DshGlassTier,
    backdrop: LayerBackdrop?,
    shape: Shape,
    base: Color = Dsh.bgBase,
    surface: DshGlassSurface = DshGlassSurface.Standard,
    /** 按压态边缘高光增益（4.5.4，DshGlassCapsule/Circle 传入；页面不直接用）。 */
    highlightBoost: Float = 0f,
): Modifier = composed {
    val reduceTransparency by rememberDshReduceTransparency()
    val isPreview = LocalInspectionMode.current
    val dark = Dsh.isDark
    val spec = when (tier) {
        DshGlassTier.Control -> controlSpec(surface)
        DshGlassTier.Floating -> FLOATING_SPEC
        @Suppress("DEPRECATION")
        DshGlassTier.Navigation -> NAVIGATION_SPEC
    }

    val canBlur = backdrop != null && !reduceTransparency && !isPreview &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // R10：折射三重门槛——共用开关、API 33+（RuntimeShader）、圆角形状（矩形会抛异常）。
    val canRefract = canBlur && dshGlassRefractionEnabled &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        tier != DshGlassTier.Navigation &&
        spec.refractionHeight > 0.dp && shape is CornerBasedShape

    // 回退（省电 / 关动画 / 旧 API）完全实体化：信息与层级不受影响，不留透明空洞。
    // Control 档回退色与画布脱钩（白卡 / 深灰面板），保证控件在任何画布上可辨（4.5.5）。
    val fallbackColor = when (tier) {
        DshGlassTier.Control -> if (dark) Dsh.bgSubtle else Dsh.bgCard
        else -> base
    }
    val surfaceAlpha = when {
        !canBlur -> if (reduceTransparency) 1f else spec.fallbackAlpha
        tier == DshGlassTier.Navigation || tier == DshGlassTier.Floating ->
            if (dark) spec.surfaceAlphaDark else spec.surfaceAlphaLight
        else -> spec.surfaceAlpha(dark)
    }
    val surfaceColor = if (tier == DshGlassTier.Control || tier == DshGlassTier.Floating) {
        // Floating 沿用画布族底色（base）；Control 用 spec 固定色
        if (tier == DshGlassTier.Control) spec.surfaceColor(dark) else base
    } else {
        base
    }

    val drawHighlight = tier != DshGlassTier.Navigation && spec.highlightWidth > 0.dp && canBlur
    val highlightAlpha = if (drawHighlight) {
        (spec.highlightAlpha(dark) + if (canBlur) highlightBoost else 0f).coerceAtMost(1f)
    } else {
        0f
    }

    val glass: Modifier = if (canBlur && backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(spec.blur.toPx())
                if (canRefract) {
                    // 参数单位 px（R10）；高度必须适配最小圆角，避免边角不连续
                    lens(spec.refractionHeight.toPx(), spec.refractionAmount.toPx())
                }
            },
            highlight = if (drawHighlight) {
                { Highlight(width = spec.highlightWidth, alpha = highlightAlpha) }
            } else {
                null
            },
            shadow = if (tier != DshGlassTier.Navigation && spec.shadowRadius > 0.dp) {
                {
                    Shadow(
                        radius = spec.shadowRadius,
                        color = Color.Black.copy(alpha = spec.shadowAlpha(dark)),
                        alpha = 1f,
                    )
                }
            } else {
                null
            },
            onDrawSurface = { drawRect(surfaceColor.copy(alpha = surfaceAlpha)) },
        )
    } else {
        // v3：回退按 shape 画（此前 drawRect 忽略 shape，胶囊 / 圆钮在 API 26–30、省电、预览里
        // 变成方块）。Control 档用拟物回退（借鉴 ChunUI，MIT）：对角渐变 + 顶光描边 + 柔阴影。
        Modifier.dshGlassFallback(
            shape = shape,
            color = fallbackColor.copy(alpha = surfaceAlpha),
            skeuomorphic = tier == DshGlassTier.Control,
            dark = dark,
        )
    }
    glass
}

/**
 * 无模糊回退表面（v3，docs/visual-rules.md 2.1.1）：一律按 [shape] 的 outline 绘制。
 *
 * [skeuomorphic] = true（Control 档）时借鉴 ChunUI（liseami/ChunUI，MIT）的拟物降级：
 * - 填充：左上 → 右下对角渐变，底色 → 底色混入黑（浅 6% / 深 10%）；
 * - 描边：1dp 顶光渐变（白 → 透明 → 黑），把控件从任意画布上衬出来；
 * - 阴影：柔和投影（radius 8 / y 2），深色加浓。
 * 否则（Floating / Navigation）只画按 shape 裁剪的实色。
 */
private fun Modifier.dshGlassFallback(
    shape: Shape,
    color: Color,
    skeuomorphic: Boolean,
    dark: Boolean,
): Modifier {
    val shadow = if (skeuomorphic) {
        dropShadow(
            shape = shape,
            shadow = ComposeShadow(
                radius = FALLBACK_SHADOW_RADIUS,
                color = Color.Black.copy(alpha = if (dark) FALLBACK_SHADOW_ALPHA_DARK else FALLBACK_SHADOW_ALPHA),
                offset = DpOffset(0.dp, FALLBACK_SHADOW_Y),
            ),
        )
    } else {
        this
    }
    return shadow.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val fill: Brush = if (skeuomorphic) {
            Brush.linearGradient(
                colors = listOf(color, lerp(color, Color.Black.copy(alpha = color.alpha), if (dark) FALLBACK_SHADE_DARK else FALLBACK_SHADE)),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
        } else {
            SolidColor(color)
        }
        val strokeWidth = 1.dp.toPx()
        val stroke = Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (dark) FALLBACK_TOP_LIGHT_DARK else FALLBACK_TOP_LIGHT),
            // 浅色中段补一丝暗边：白控件落在聊天白画布上也有轮廓
            0.5f to if (dark) Color.Transparent else Color.Black.copy(alpha = FALLBACK_MID_SHADE),
            1f to Color.Black.copy(alpha = if (dark) FALLBACK_BOTTOM_SHADE_DARK else FALLBACK_BOTTOM_SHADE),
        )
        onDrawBehind {
            drawOutline(outline, fill)
            if (skeuomorphic) {
                // 描边内缩半个线宽，避免被裁到 shape 外
                inset(strokeWidth / 2f) {
                    drawOutline(shape.createOutline(size, layoutDirection, this), stroke, style = Stroke(strokeWidth))
                }
            }
        }
    }
}

/** 拟物回退参数（ChunUI skeuomorphic fallback 的 Android 换算；调校只改这里）。 */
// ChunUI 原值 10%；Android 宽胶囊上对角渐变近似横向渐变，浅色 10% 显脏，取 6%
private const val FALLBACK_SHADE = 0.06f
private const val FALLBACK_SHADE_DARK = 0.10f
private const val FALLBACK_TOP_LIGHT = 0.5f
private const val FALLBACK_TOP_LIGHT_DARK = 0.14f
private const val FALLBACK_MID_SHADE = 0.03f
private const val FALLBACK_BOTTOM_SHADE = 0.07f
private const val FALLBACK_BOTTOM_SHADE_DARK = 0.24f
private val FALLBACK_SHADOW_RADIUS = 8.dp
private val FALLBACK_SHADOW_Y = 2.dp
private const val FALLBACK_SHADOW_ALPHA = 0.08f
private const val FALLBACK_SHADOW_ALPHA_DARK = 0.30f

/** 全宽导航条（过渡期）的常用形状；显式命名以免调用点各自写 RectangleShape。 */
@Deprecated("全宽导航条已下线（L9）")
val DshGlassFullWidthShape: Shape = RectangleShape
