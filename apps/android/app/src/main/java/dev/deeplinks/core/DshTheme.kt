package dev.deeplinks.core

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import dev.deeplinks.R
import dev.deeplinks.native.DshRadius

/**
 * DeepLinks 设计系统颜色角色与主题管理器。
 * 取值以 v4 色表为准（docs/visual-rules.md §2）；旧角色名映射到 v4 角色，迁移完成后删除。
 */
@Stable
data class DshColors(
    val isDark: Boolean,
    val bgBase: Color,
    val bgSidePanel: Color,
    val bgCard: Color,
    val bgInput: Color,
    val bgSubtle: Color,
    val bgCode: Color,
    val bgCodeBanner: Color,
    /**
     * 选中容器（selection container）：与 [bgNavSelected] 合并为同一角色——
     * 全 App 选中态统一 DSH Blue tonal，不用纯黑反色胶囊（docs/visual-rules.md 第二节）。
     * 兼容别名，迁移完成后删除。
     */
    val bgSelected: Color,
    val bgPressed: Color,
    // 原生抽屉（M3）：sheet 容器底 + 选中项胶囊底
    // 容器底与 bgSidePanel 同档（surfaceContainerLow，静态/动态取色一致），与内容面靠发丝线分层
    val bgDrawer: Color = Color.Unspecified,
    val bgNavSelected: Color = Color.Unspecified,
    val bgTrack: Color,
    val bgOverlay: Color,
    /** 思考轨迹等「凹进」面板：比画布更深一档（DeepSeek 签名块）。 */
    val bgRecessed: Color = Color.Unspecified,
    val labelPrimary: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
    val labelDimmed: Color,
    val borderSubtle: Color,
    val borderStrong: Color,
    val pressed: Color,
    val activated: Color,
    val brand400: Color,
    val brand500: Color,
    val success: Color,
    val warn: Color,
    val warnLabel: Color,
    val error: Color,
    val errorBg: Color,
    val buttonElevated: Color,
    val buttonFloating: Color,
    val bgSurface: Color = Color.Unspecified,
    val shadowCard: Color = Color.Unspecified,
    // 分段色：系统提示词 / 工具调用的语义色（跨主题稳定）
    val systemAccent: Color = Color.Unspecified,
    val toolsAccent: Color = Color.Unspecified,
    // 轨迹角色语义色（跨主题稳定，与 systemAccent/toolsAccent 同类）
    val traceReasoning: Color = Color.Unspecified,
    // 品牌色调叠加层（按钮/标签底），跨主题稳定
    val brandTint: Color = Color.Unspecified,
    /** 品牌/语义实心底上的内容色（brand500 / error 等），替代散落的硬编码 Color.White。 */
    val onBrand: Color = Color.Unspecified,
    /** 过程控制按钮（新任务、停止）的底。浅色等于 labelPrimary；深色为深灰，避免比批准更亮。 */
    val inkFill: Color = Color.Unspecified,
    /** [inkFill] 上的图标和文字。 */
    val onInk: Color = Color.Unspecified,
    // 状态容器配对（M3 container/on-container 语义，保证 WCAG AA）
    val successContent: Color = Color.Unspecified,
    val cloudContent: Color = Color.Unspecified,
    val cloudContainer: Color = Color.Unspecified,
    // ===== 2026-10-02 Lody 简化（v2 方案 3.3，L4/L5）：强调范围放宽的三个角色 =====
    /** 线性图标与主入口强调（设置分组图标、圆形 + 图标、发送钮底）：取 brand400。 */
    val accentIcon: Color = Color.Unspecified,
    /** 开关开启轨道：取 brand400；拇指浅色 bgCard / 深色 onInk（见 dshSwitchColors）。 */
    val switchOnTrack: Color = Color.Unspecified,
    /** 用户气泡底（v4：容器色 surface1）。 */
    val userBubble: Color = Color.Unspecified,
    // ===== v4 角色（docs/visual-rules.md §2）=====
    val surface1: Color = Color.Unspecified,
    val surface2: Color = Color.Unspecified,
    val outline: Color = Color.Unspecified,
    val tertiaryText: Color = Color.Unspecified,
    val primarySoft: Color = Color.Unspecified,
    /** 等你 / 风险：等你批准、等你回答、完全权限。 */
    val wait: Color = Color.Unspecified,
    val waitSoft: Color = Color.Unspecified,
    /** 成功 / diff 增行。 */
    val ok: Color = Color.Unspecified,
    val okSoft: Color = Color.Unspecified,
    /** 失败 / diff 删行 / 危险操作文字。 */
    val err: Color = Color.Unspecified,
    val errSoft: Color = Color.Unspecified,
)

// ===== v4 色表（docs/visual-rules.md §2）=====
// Color(0x…) 只允许出现在本文件，取值必须在 v4 token 表里（DshPaletteProvenanceTest）。

private val LightBackground = Color(0xFFFFFFFF)
private val LightSurface1 = Color(0xFFF4F5F7)
private val LightSurface2 = Color(0xFFE9EBEF)
private val LightOutline = Color(0xFFE3E5E9)
private val LightOnSurface = Color(0xFF15171C)
private val LightOnSurfaceVariant = Color(0xFF555A64)
private val LightTertiaryText = Color(0xFF6E737D)
private val LightPrimary = Color(0xFF3F5BD6)
private val LightPrimarySoft = Color(0xFFECEFFC)
private val LightWait = Color(0xFFB25E0C)
private val LightOk = Color(0xFF1F7F4A)
private val LightErr = Color(0xFFC83A30)
private val LightWaitSoft = Color(0xFFFCF1E5)
private val LightOkSoft = Color(0xFFE6F3EB)
private val LightErrSoft = Color(0xFFFBEAE8)

private val DarkBackground = Color(0xFF121214)
private val DarkSurface1 = Color(0xFF1C1D21)
private val DarkSurface2 = Color(0xFF27292E)
private val DarkOutline = Color(0xFF2D2F35)
private val DarkOnSurface = Color(0xFFECEDF0)
private val DarkOnSurfaceVariant = Color(0xFFA9ADB6)
private val DarkTertiaryText = Color(0xFF8C9099)
private val DarkPrimary = Color(0xFF8B9DFF)
private val DarkWait = Color(0xFFE9A35B)
private val DarkOk = Color(0xFF62C28E)
private val DarkErr = Color(0xFFF07B70)

private val PureBlackBackground = Color(0xFF000000)
private val PureBlackSurface1 = Color(0xFF141416)

/** 遮罩（scrim）是唯一允许的半透明面。 */
private val Scrim = Color.Black.copy(alpha = 0.32f)

val DarkDshColors = DshColors(
    isDark = true,
    bgBase = DarkBackground,
    bgSidePanel = DarkBackground,
    bgCard = DarkSurface1,
    bgInput = DarkSurface1,
    bgSubtle = DarkSurface2,
    bgCode = DarkSurface1,
    bgCodeBanner = DarkSurface1,
    bgSelected = DarkPrimary.copy(alpha = 0.15f),
    bgPressed = DarkSurface2,
    bgDrawer = DarkBackground,
    bgNavSelected = DarkPrimary.copy(alpha = 0.15f),
    bgTrack = DarkOutline,
    bgOverlay = Scrim,
    bgRecessed = DarkSurface1,
    bgSurface = DarkBackground,
    labelPrimary = DarkOnSurface,
    labelSecondary = DarkOnSurfaceVariant,
    labelTertiary = DarkTertiaryText,
    labelDimmed = DarkTertiaryText,
    borderSubtle = DarkOutline,
    borderStrong = DarkOutline,
    pressed = DarkSurface2,
    activated = DarkPrimary.copy(alpha = 0.15f),
    brand400 = DarkPrimary,
    brand500 = DarkPrimary,
    success = DarkOk,
    warn = DarkWait,
    warnLabel = DarkWait,
    error = DarkErr,
    errorBg = DarkErr.copy(alpha = 0.13f),
    buttonElevated = DarkSurface1,
    buttonFloating = DarkSurface1,
    shadowCard = Color.Transparent,
    systemAccent = DarkTertiaryText,
    toolsAccent = DarkOutline,
    traceReasoning = DarkPrimary,
    brandTint = DarkPrimary.copy(alpha = 0.15f),
    onBrand = DarkBackground,
    inkFill = DarkSurface2,
    onInk = DarkOnSurface,
    successContent = DarkOk,
    cloudContent = DarkPrimary,
    cloudContainer = DarkPrimary.copy(alpha = 0.15f),
    accentIcon = DarkPrimary,
    switchOnTrack = DarkPrimary,
    userBubble = DarkSurface1,
    surface1 = DarkSurface1,
    surface2 = DarkSurface2,
    outline = DarkOutline,
    tertiaryText = DarkTertiaryText,
    primarySoft = DarkPrimary.copy(alpha = 0.15f),
    wait = DarkWait,
    waitSoft = DarkWait.copy(alpha = 0.13f),
    ok = DarkOk,
    okSoft = DarkOk.copy(alpha = 0.13f),
    err = DarkErr,
    errSoft = DarkErr.copy(alpha = 0.13f),
)

val LightDshColors = DshColors(
    isDark = false,
    bgBase = LightBackground,
    bgSidePanel = LightBackground,
    bgCard = LightSurface1,
    bgInput = LightSurface1,
    bgSubtle = LightSurface2,
    bgCode = LightSurface1,
    bgCodeBanner = LightSurface1,
    bgSelected = LightPrimarySoft,
    bgPressed = LightSurface2,
    bgDrawer = LightBackground,
    bgNavSelected = LightPrimarySoft,
    bgTrack = LightOutline,
    bgOverlay = Scrim,
    bgRecessed = LightSurface1,
    bgSurface = LightBackground,
    labelPrimary = LightOnSurface,
    labelSecondary = LightOnSurfaceVariant,
    labelTertiary = LightTertiaryText,
    labelDimmed = LightTertiaryText,
    borderSubtle = LightOutline,
    borderStrong = LightOutline,
    pressed = LightSurface2,
    activated = LightPrimarySoft,
    brand400 = LightPrimary,
    brand500 = LightPrimary,
    success = LightOk,
    warn = LightWait,
    warnLabel = LightWait,
    error = LightErr,
    errorBg = LightErrSoft,
    buttonElevated = LightBackground,
    buttonFloating = LightBackground,
    shadowCard = Color.Transparent,
    systemAccent = LightTertiaryText,
    toolsAccent = LightOutline,
    traceReasoning = LightPrimary,
    brandTint = LightPrimarySoft,
    onBrand = LightBackground,
    inkFill = LightOnSurface,
    onInk = LightBackground,
    successContent = LightOk,
    cloudContent = LightPrimary,
    cloudContainer = LightPrimarySoft,
    accentIcon = LightPrimary,
    switchOnTrack = LightPrimary,
    userBubble = LightSurface1,
    surface1 = LightSurface1,
    surface2 = LightSurface2,
    outline = LightOutline,
    tertiaryText = LightTertiaryText,
    primarySoft = LightPrimarySoft,
    wait = LightWait,
    waitSoft = LightWaitSoft,
    ok = LightOk,
    okSoft = LightOkSoft,
    err = LightErr,
    errSoft = LightErrSoft,
)

/** 纯黑背景（OLED）：画布改 #000000，容器改 #141416，其他不变。 */
fun DshColors.pureBlack(): DshColors = copy(
    bgBase = PureBlackBackground,
    bgSidePanel = PureBlackBackground,
    bgDrawer = PureBlackBackground,
    bgSurface = PureBlackBackground,
    onBrand = PureBlackBackground,
    bgCard = PureBlackSurface1,
    bgInput = PureBlackSurface1,
    bgCode = PureBlackSurface1,
    bgCodeBanner = PureBlackSurface1,
    bgRecessed = PureBlackSurface1,
    buttonElevated = PureBlackSurface1,
    buttonFloating = PureBlackSurface1,
    userBubble = PureBlackSurface1,
    surface1 = PureBlackSurface1,
)

val LocalDshColors = staticCompositionLocalOf { DarkDshColors }

// Shared Material shape roles. Screens may still use DSH-specific shapes for
// expressive details, but Material components now receive stable semantic
// defaults instead of falling back to the library's unrelated defaults.
// 全部由 DshRadius 推导（单一真源，docs/visual-rules.md 第三节）：
// extraSmall/small←control、medium/large←container、extraLarge←modal。
// 由 DshShapeRoleTest 锁死：标准组件不得回落到另一套形状。
private val DshMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(DshRadius.control),
    small = RoundedCornerShape(DshRadius.control),
    medium = RoundedCornerShape(DshRadius.container),
    large = RoundedCornerShape(DshRadius.container),
    extraLarge = RoundedCornerShape(DshRadius.modal),
)

/** 全局主题设置管理器 */
object ThemeManager {
    private const val PREFS_NAME = "dsh_settings"
    private const val KEY_THEME = "theme"
    private const val KEY_PURE_BLACK = "dark_pure_black"

    var currentThemeMode by mutableStateOf("system")
        private set

    /** 深色背景用纯黑而不是近黑（仅本地，不进服务端 AppSettings）。 */
    var pureBlack by mutableStateOf(false)
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentThemeMode = prefs.getString(KEY_THEME, "system") ?: "system"
        pureBlack = prefs.getBoolean(KEY_PURE_BLACK, false)
    }

    fun setPureBlack(context: Context, enabled: Boolean) {
        pureBlack = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PURE_BLACK, enabled)
            .apply()
    }

    fun setThemeMode(context: Context, mode: String) {
        currentThemeMode = mode
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, mode)
            .apply()
    }

    fun toggleTheme(context: Context, isCurrentlyDark: Boolean) {
        val nextMode = if (isCurrentlyDark) "light" else "dark"
        setThemeMode(context, nextMode)
    }
}

/** 应用内字号（对照 DeepSeek 官方 App 1.2.6+；仅本地，不进服务端 AppSettings）。 */
object FontScaleManager {
    private const val PREFS_NAME = "dsh_settings"
    private const val KEY_FONT_SCALE = "font_scale"

    const val SMALL = "small"
    const val DEFAULT = "default"
    const val LARGE = "large"

    var currentScale by mutableStateOf(DEFAULT)
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentScale = canonicalizeFontScale(prefs.getString(KEY_FONT_SCALE, DEFAULT))
    }

    fun setScale(context: Context, id: String) {
        currentScale = canonicalizeFontScale(id)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FONT_SCALE, currentScale)
            .apply()
    }
}

fun canonicalizeFontScale(id: String?): String = when (id) {
    FontScaleManager.SMALL, FontScaleManager.LARGE -> id
    else -> FontScaleManager.DEFAULT
}

fun fontScaleMultiplier(id: String?): Float = when (canonicalizeFontScale(id)) {
    FontScaleManager.SMALL -> 0.88f
    FontScaleManager.LARGE -> 1.18f
    else -> 1f
}

/**
 * 透明系统栏；图标亮暗由 [DshTheme] 按当前主题写入 InsetsController。
 * 不用 SystemBarStyle.dark：浅色主题下会把状态栏图标固定成浅色。
 */
fun ComponentActivity.enableDshEdgeToEdge() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
        ),
        navigationBarStyle = SystemBarStyle.auto(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
        ),
    )
}

/**
 * DSH 根主题包装组件。
 * 负责：
 * 1. 提供 LocalDshColors（深浅色两套 1:1 DSH token）；
 * 2. 全局状态栏/导航栏图标颜色动态适配：深色主题 → 浅色图标（isAppearanceLight=false），
 *    浅色主题 → 深色图标（isAppearanceLight=true），随 App 内主题切换实时生效。
 */
@Composable
fun DshTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ThemeManager.init(context)
        LocaleManager.init(context)
        FontScaleManager.init(context)
    }

    val systemDark = isSystemInDarkTheme()
    val isDark = when (ThemeManager.currentThemeMode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    val palette = if (isDark) DarkDshColors else LightDshColors
    val colors = if (isDark && ThemeManager.pureBlack) palette.pureBlack() else palette
    val lang = LocaleManager.language
    val strings = if (lang == "en") DshStringsEn else DshStringsZh
    val recentsLabel = stringResource(R.string.app_name)

    // 系统栏图标亮暗随主题联动（背景保持透明，由 enableEdgeToEdge 设置）
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() ?: return@SideEffect
            val window = activity.window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
            val recentsColor = colors.bgBase.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.setTaskDescription(
                    ActivityManager.TaskDescription.Builder()
                        .setLabel(recentsLabel)
                        .setPrimaryColor(recentsColor)
                        .build(),
                )
            } else {
                @Suppress("DEPRECATION")
                activity.setTaskDescription(
                    ActivityManager.TaskDescription(recentsLabel, null, recentsColor),
                )
            }
        }
    }

    val materialColors = dshColorScheme(colors)

    val baseDensity = LocalDensity.current
    val fontMultiplier = fontScaleMultiplier(FontScaleManager.currentScale)
    val typography = remember { dshTypography() }
    MaterialTheme(
        colorScheme = materialColors,
        typography = typography,
        shapes = DshMaterialShapes,
    ) {
        CompositionLocalProvider(
            LocalDshColors provides colors,
            LocalDshStrings provides strings,
            LocalTextStyle provides typography.bodyMedium,
            LocalDensity provides Density(
                density = baseDensity.density,
                fontScale = baseDensity.fontScale * fontMultiplier,
            ),
        ) {
            CompositionLocalProvider(
                LocalIndication provides dshRipple(),
                LocalRippleConfiguration provides RippleConfiguration(
                    color = colors.labelPrimary,
                    rippleAlpha = RippleAlpha(
                        draggedAlpha = 0.08f,
                        focusedAlpha = 0.08f,
                        hoveredAlpha = 0.04f,
                        pressedAlpha = 0.06f,
                    ),
                ),
            ) {
                content()
            }
        }
    }
}

/** M3 色槽全部从 v4 角色推导；不接系统动态取色。 */
fun dshColorScheme(colors: DshColors): ColorScheme {
    val scheme = if (colors.isDark) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = colors.brand400,
        onPrimary = colors.onBrand,
        primaryContainer = colors.primarySoft,
        onPrimaryContainer = colors.brand400,
        secondary = colors.brand400,
        onSecondary = colors.onBrand,
        secondaryContainer = colors.primarySoft,
        onSecondaryContainer = colors.labelPrimary,
        tertiary = colors.ok,
        onTertiary = colors.onBrand,
        tertiaryContainer = colors.okSoft,
        onTertiaryContainer = colors.ok,
        background = colors.bgBase,
        onBackground = colors.labelPrimary,
        surface = colors.bgBase,
        onSurface = colors.labelPrimary,
        surfaceVariant = colors.surface1,
        onSurfaceVariant = colors.labelSecondary,
        surfaceTint = Color.Transparent,
        surfaceDim = colors.bgBase,
        surfaceBright = colors.bgBase,
        surfaceContainerLowest = colors.bgBase,
        surfaceContainerLow = colors.surface1,
        surfaceContainer = colors.surface1,
        surfaceContainerHigh = colors.surface1,
        surfaceContainerHighest = colors.surface2,
        outline = colors.outline,
        outlineVariant = colors.outline,
        error = colors.err,
        onError = colors.onBrand,
        errorContainer = colors.errSoft,
        onErrorContainer = colors.err,
        inverseSurface = colors.labelPrimary,
        inverseOnSurface = colors.bgBase,
        inversePrimary = colors.brand400,
        scrim = Color.Black,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 兼容原有 Dsh 访问方式的代理对象，所有属性自动读取当前 Composable 上下文的真实主题色。
 */
object Dsh {
    val isDark: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.isDark

    val bgBase: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgBase

    val bgSidePanel: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgSidePanel

    val bgCard: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgCard

    val bgInput: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgInput

    val bgSubtle: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgSubtle

    val bgCode: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgCode

    val bgCodeBanner: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgCodeBanner

    val bgSelected: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgSelected

    val bgDrawer: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgDrawer

    val bgNavSelected: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgNavSelected

    val bgPressed: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgPressed

    val bgTrack: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgTrack

    val bgOverlay: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgOverlay

    val bgRecessed: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgRecessed

    val labelPrimary: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.labelPrimary

    val labelSecondary: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.labelSecondary

    val labelTertiary: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.labelTertiary

    val labelDimmed: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.labelDimmed

    val borderSubtle: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.borderSubtle

    val borderStrong: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.borderStrong

    val pressed: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.pressed

    val activated: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.activated

    val brand400: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.brand400

    val brand500: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.brand500

    val success: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.success

    val warn: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.warn

    val warnLabel: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.warnLabel

    val error: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.error

    val errorBg: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.errorBg

    val buttonElevated: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.buttonElevated

    val buttonFloating: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.buttonFloating

    val bgSurface: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.bgSurface

    val shadowCard: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.shadowCard

    val systemAccent: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.systemAccent

    val toolsAccent: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.toolsAccent

    val traceReasoning: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.traceReasoning

    val brandTint: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.brandTint

    val onBrand: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.onBrand

    val inkFill: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.inkFill

    val onInk: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.onInk

    val successContent: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.successContent

    val cloudContent: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.cloudContent

    val cloudContainer: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.cloudContainer

    val accentIcon: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.accentIcon

    val switchOnTrack: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.switchOnTrack

    val userBubble: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.userBubble

    val surface1: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.surface1

    val surface2: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.surface2

    val outline: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.outline

    val tertiaryText: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.tertiaryText

    val primarySoft: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.primarySoft

    val wait: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.wait

    val waitSoft: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.waitSoft

    val ok: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.ok

    val okSoft: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.okSoft

    val err: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.err

    val errSoft: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.errSoft
}
