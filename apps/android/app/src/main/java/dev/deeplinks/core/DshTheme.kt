package dev.deeplinks.core

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import dev.deeplinks.R
import dev.deeplinks.native.DshRadius

/**
 * DeepSeek Harness 设计系统颜色 Token 接口定义与主题管理器。
 * 两套配色的每个角色都取自 [Dsw]（DSH Web 调色板镜像），偏离处逐行写明原因，
 * 并支持通过系统设置 / App 内部偏好进行动态切换。
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
    /**
     * 「等你批准 / 等你回答」胶囊的暖色容器（重设计稿 2026-09-28，方案 2.2）。
     * 与 [warnLabel] 成对，参照 [successContainer] 的容器/内容写法；DSH 没有 warning 容器档。
     */
    val warnContainer: Color = Color.Unspecified,
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
    val traceApproval: Color = Color.Unspecified,
    val traceTodo: Color = Color.Unspecified,
    // 品牌色调叠加层（按钮/标签底），跨主题稳定
    val brandTint: Color = Color.Unspecified,
    /** 品牌/语义实心底上的内容色（brand500 / error 等），替代散落的硬编码 Color.White。 */
    val onBrand: Color = Color.Unspecified,
    // 状态容器配对（M3 container/on-container 语义，保证 WCAG AA）
    val successContent: Color = Color.Unspecified,
    val successContainer: Color = Color.Unspecified,
    val cloudContent: Color = Color.Unspecified,
    val cloudContainer: Color = Color.Unspecified,
)

// ===== 色源：DeepSeek Harness（docs/visual-rules.md「色源」）=====
// 每个角色取自 Dsw（DSH 调色板镜像），行尾注释是对应的 --dsw-alias-* / --dsw-specific-*。
// 取不到 DSH 值的行必须写「偏离 DSH」和原因，由 DshPaletteProvenanceTest 强制。

val DarkDshColors = DshColors(
    isDark = true,
    // ===== 2026-09-28 重设计稿深色板（方案 2.2）=====
    // 画布压到近黑、卡片抬一档、输入与代码各占一层；这套取值与 DSH neutral-bluish 不同族，
    // 因此下面每一行都按 DshPaletteProvenanceTest 的要求标注偏离原因。
    bgBase = Color(0xFF121214),              // 偏离 DSH：2026-09-28 重设计稿（深色页面底 #121214）
    bgSidePanel = Color(0xFF121214),         // 偏离 DSH：同上（导航面与页面底同档）
    bgCard = Color(0xFF1C1D21),              // 偏离 DSH：重设计稿卡片/面板面 #1C1D21
    bgInput = Color(0xFF121214),             // 偏离 DSH：重设计稿输入条底 #121214（在 #1C1D21 对话页上凹进去）
    bgSubtle = Color(0xFF2A2B30),            // 偏离 DSH：重设计稿用户气泡与胶囊底 #2A2B30
    bgCode = Color(0xFF26272C),              // 偏离 DSH：重设计稿代码块底 #26272C
    bgCodeBanner = Color(0xFF222328),        // 偏离 DSH：重设计稿卡片头/弱底 #222328
    bgSelected = Color(0xFF26272C),          // 偏离 DSH：重设计稿选中态改中性灰（不再用品牌蓝 tonal）
    bgPressed = Dsw.interactiveHoverDark,    // alias-interactive-bg-hover
    bgDrawer = Color(0xFF121214),            // 偏离 DSH：重设计稿抽屉底与页面底同档
    bgNavSelected = Color(0xFF26272C),       // 偏离 DSH：与 bgSelected 同一 selection container
    bgTrack = Color(0xFF2E3036),             // 偏离 DSH：重设计稿深色进行中轨道 #2E3036（MainDark.dc.html 的转圈底）
    bgOverlay = Color(0x99000000),           // 偏离 DSH：重设计稿遮罩 rgba(0,0,0,.6)
    bgRecessed = Color(0xFF17181B),          // 偏离 DSH：重设计稿凹进面（思考轨迹）比卡片再暗一档
    bgSurface = Color(0xFF1C1D21),           // 偏离 DSH：重设计稿面板面 #1C1D21
    labelPrimary = Color(0xFFEDEDEF),        // 偏离 DSH：重设计稿主文字 #EDEDEF
    labelSecondary = Color(0xFFA3A7AE),      // 偏离 DSH：重设计稿次要文字 #A3A7AE
    labelTertiary = Color(0xFF8B8F96),       // 偏离 DSH：重设计稿第三级文字/箭头 #8B8F96
    labelDimmed = Color(0xFF4A4D53),         // 偏离 DSH：重设计稿禁用 #4A4D53
    borderSubtle = Color(0xFF2A2B30),        // 偏离 DSH：重设计稿分隔线 #2A2B30（只用于分隔线，不做容器描边）
    borderStrong = Dsw.borderL3Dark,         // alias-border-l3
    pressed = Dsw.interactiveHoverDark,      // alias-interactive-bg-hover
    activated = Dsw.interactiveActiveDark,   // alias-interactive-bg-active
    brand400 = Color(0xFF7C93FF),            // 偏离 DSH：重设计稿强调蓝（链接/次强调与主强调同族）
    brand500 = Color(0xFF7C93FF),            // 偏离 DSH：重设计稿强调蓝 #7C93FF（批准、发送）
    success = Color(0xFF3BC476),             // 偏离 DSH：重设计稿在线点 #3BC476
    warn = Dsw.amber500,                     // DSH 无 warning alias，取 static amber
    warnLabel = Color(0xFFF0B86A),           // 偏离 DSH：重设计稿「等你批准」胶囊文字 #F0B86A
    warnContainer = Color(0xFF3A2A12),       // 偏离 DSH：重设计稿「等你批准」胶囊底 #3A2A12
    error = Color(0xFFFF8A7E),               // 偏离 DSH：重设计稿危险文字 #FF8A7E
    errorBg = Dsw.interactiveHoverDangerDark, // alias-interactive-bg-hover-danger
    buttonElevated = Dsw.neutralBluish750,   // alias-button-elevated-fill
    buttonFloating = Dsw.neutralBluish850,   // alias-button-floating-fill
    shadowCard = Color(0x1F000000),          // 偏离 DSH：DSH 阴影不分深浅（0D），深色画布上看不见，加深到 1F
    systemAccent = Color(0xFF94A3B8),        // 偏离 DSH：无对应角色（Android 分段语义色）
    toolsAccent = Color(0xFF8BA3C7),         // 偏离 DSH：无对应角色；蓝灰弱强调，不与品牌蓝抢层级
    traceReasoning = Color(0xFF7B93F8),      // 偏离 DSH：无对应角色；推理轨蓝系弱强调（非紫）
    traceApproval = Color(0xFFE07A3A),       // 偏离 DSH：无对应角色；审批降噪橙
    traceTodo = Color(0xFF5BB8C9),           // 偏离 DSH：无对应角色；待办青
    brandTint = Color(0xFF7C93FF).copy(alpha = 0.1f), // 偏离 DSH：随重设计稿强调蓝
    // 重设计稿：深色强调底 #7C93FF 上必须写深字（白字只有 2.8:1），本测试下限是 3:1
    onBrand = Color(0xFF121214),             // 偏离 DSH：重设计稿深色强调底上的内容色（非白）
    successContent = Color(0xFF5CC38A),      // 偏离 DSH：重设计稿完成图标 #5CC38A（DSH 没有深底绿字档）
    successContainer = Color(0xFF16301F),    // 偏离 DSH：重设计稿完成图标圈底 #16301F
    cloudContent = Dsw.deepseek300,
    cloudContainer = Dsw.deepseek800,        // alias-state-business-tertiary
)

val LightDshColors = DshColors(
    isDark = false,
    // ===== 2026-09-28 重设计稿浅色板（方案 2.2）=====
    // 与旧浅色板最大的差别：**页面底是灰的、卡片才白**。分层靠这一组 tonal 差，
    // 不给卡片加描边（docs/visual-rules.md 第二节）。
    bgBase = Color(0xFFF5F5F2),              // 偏离 DSH：2026-09-28 重设计稿（页面底 #F5F5F2）
    bgSidePanel = Color(0xFFF5F5F2),         // 偏离 DSH：同上（导航面与页面底同档）
    bgCard = Color(0xFFFFFFFF),              // 偏离 DSH：重设计稿卡片/面板面 #FFFFFF（与灰底成对）
    bgInput = Color(0xFFF5F5F2),             // 偏离 DSH：重设计稿输入条底 #F5F5F2（在白色对话页上凹进去）
    // 稿子写 #F1F1ED：与页面底 #F5F5F2 只差 1.04:1，过不了 DshContrastTest 的 1.05 分层下限，
    // 下压一档到 #EAEAE5（气泡与选中/胶囊共用这一层）
    bgSubtle = Color(0xFFEAEAE5),            // 偏离 DSH：重设计稿气泡 #F1F1ED 下压一档（见上）
    bgCode = Color(0xFFF3F3EF),              // 偏离 DSH：重设计稿代码块底 #F3F3EF
    bgCodeBanner = Color(0xFFFAFAF8),        // 偏离 DSH：重设计稿卡片头/弱底 #FAFAF8
    bgSelected = Color(0xFFE7E7E1),          // 偏离 DSH：重设计稿选中态改中性灰（不再用品牌蓝 tonal）
    bgPressed = Dsw.interactiveHoverLight,   // alias-interactive-bg-hover
    bgDrawer = Color(0xFFF5F5F2),            // 偏离 DSH：重设计稿抽屉底与页面底同档
    bgNavSelected = Color(0xFFE7E7E1),       // 偏离 DSH：与 bgSelected 同一 selection container
    bgTrack = Color(0xFFE6E6E1),             // 偏离 DSH：重设计稿进行中轨道 #E6E6E1（Main.dc.html 的转圈底）
    bgOverlay = Color(0x6B16171A),           // 偏离 DSH：重设计稿遮罩 rgba(22,23,26,.42)
    bgRecessed = Color(0xFFF3F3EF),          // 偏离 DSH：重设计稿凹进面（思考轨迹）#F3F3EF
    bgSurface = Color(0xFFFFFFFF),           // 偏离 DSH：重设计稿面板面 #FFFFFF
    labelPrimary = Color(0xFF16171A),        // 偏离 DSH：重设计稿主文字 #16171A
    labelSecondary = Color(0xFF5B5F66),      // 偏离 DSH：重设计稿次要文字 #5B5F66（灰底 5.9:1）
    labelTertiary = Color(0xFF6E7278),       // 偏离 DSH：重设计稿第三级文字/箭头 #6E7278
    labelDimmed = Color(0xFFB5B7BB),         // 偏离 DSH：重设计稿禁用 #B5B7BB
    borderSubtle = Color(0xFFEEEEE9),        // 偏离 DSH：重设计稿分隔线 #EEEEE9（只用于分隔线，不做容器描边）
    borderStrong = Dsw.borderL3Light,        // alias-border-l3
    pressed = Dsw.interactiveHoverLight,     // alias-interactive-bg-hover
    activated = Dsw.interactiveActiveLight,  // alias-interactive-bg-active
    brand400 = Color(0xFF3F5BD6),            // 偏离 DSH：重设计稿强调蓝（链接/次强调与主强调同族）
    brand500 = Color(0xFF3F5BD6),            // 偏离 DSH：重设计稿强调蓝 #3F5BD6（批准、发送）
    success = Color(0xFF1F9D55),             // 偏离 DSH：重设计稿在线点 #1F9D55
    warn = Dsw.amber600,                     // DSH 无 warning alias，取 static amber
    warnLabel = Color(0xFF8A4B00),           // 偏离 DSH：重设计稿「等你批准」胶囊文字 #8A4B00
    warnContainer = Color(0xFFFFF1DE),       // 偏离 DSH：重设计稿「等你批准」胶囊底 #FFF1DE
    error = Color(0xFFB42318),               // 偏离 DSH：重设计稿危险文字 #B42318（4.8:1）
    errorBg = Color(0x1AB42318),             // 偏离 DSH：随 error 同色相
    buttonElevated = Dsw.neutralBluish00,    // alias-button-elevated-fill
    buttonFloating = Dsw.neutralBluish00,    // alias-button-floating-fill
    shadowCard = Dsw.shadowLv1,              // shadow-lv1 的颜色分量
    systemAccent = Color(0xFF64748B),        // 偏离 DSH：无对应角色（Android 分段语义色）
    toolsAccent = Color(0xFF5B7A9D),         // 偏离 DSH：无对应角色；蓝灰，不与品牌蓝抢层级
    traceReasoning = Color(0xFF5B6FB8),      // 偏离 DSH：无对应角色；11sp 标签需 AA（4.8:1）
    traceApproval = Dsw.amber600,
    traceTodo = Color(0xFF0E8A9A),           // 偏离 DSH：无对应角色；待办青
    brandTint = Color(0xFF3F5BD6).copy(alpha = 0.1f), // 偏离 DSH：随重设计稿强调蓝
    onBrand = Dsw.neutralBluish00,           // 浅色强调底上写白字（#3F5BD6 上 5.7:1）
    successContent = Color(0xFF17753F),      // 偏离 DSH：重设计稿完成图标 #17753F（DSH 绿色族白底不达 AA）
    successContainer = Color(0xFFE6F4EC),    // 偏离 DSH：重设计稿完成图标圈底 #E6F4EC
    cloudContent = Dsw.deepseek600,          // 在 deepseek-100 上 4.6:1
    cloudContainer = Dsw.deepseek100,        // alias-state-business-tertiary
)

/**
 * 深色「纯黑」背景（OLED）：只压画布、侧栏、代码底这几层；卡片 / 输入 / 气泡保持原色阶，
 * 分层靠卡片比底亮而不是底比卡片暗。DSH 没有纯黑模式，这几档都是本端独有。
 */
fun DshColors.pureBlack(): DshColors = copy(
    bgBase = Color.Black,
    bgSidePanel = Color(0xFF0A0A0B),         // 偏离 DSH：OLED 纯黑模式独有
    bgDrawer = Color(0xFF0A0A0B),            // 偏离 DSH：OLED 纯黑模式独有
    bgCode = Color(0xFF0A0A0B),              // 偏离 DSH：OLED 纯黑模式独有
    bgRecessed = Color.Black,
    bgSurface = Color(0xFF111113),           // 偏离 DSH：OLED 纯黑模式独有
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
    private const val KEY_DYNAMIC = "dynamic_color"
    private const val KEY_PURE_BLACK = "dark_pure_black"

    var currentThemeMode by mutableStateOf("system")
        private set

    /** Material You 动态取色开关（Android 12+ 才有效）。 */
    var dynamicColor by mutableStateOf(false)
        private set

    /** 深色背景用纯黑而不是近黑（仅本地，不进服务端 AppSettings）。 */
    var pureBlack by mutableStateOf(false)
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentThemeMode = prefs.getString(KEY_THEME, "system") ?: "system"
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC, false)
        pureBlack = prefs.getBoolean(KEY_PURE_BLACK, false)
    }

    fun setPureBlack(context: Context, enabled: Boolean) {
        pureBlack = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PURE_BLACK, enabled)
            .apply()
    }

    fun setDynamicColor(context: Context, enabled: Boolean) {
        dynamicColor = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DYNAMIC, enabled)
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
 * UI 字体偏好：默认跟随系统字体（原生观感，中文用户基线稳）；
 * 可选关闭改用 Plus Jakarta Sans 品牌字（拉丁 UI / Logo 位）。
 */
object UiFontManager {
    private const val PREFS_NAME = "dsh_settings"
    private const val KEY_SYSTEM_FONT = "ui_system_font"

    /** 默认 true：系统字体更像原生 App；Jakarta 仅作可选品牌字。 */
    var useSystemFont by mutableStateOf(true)
        private set

    fun init(context: Context) {
        useSystemFont = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SYSTEM_FONT, true)
    }

    fun setUseSystemFont(context: Context, enabled: Boolean) {
        useSystemFont = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SYSTEM_FONT, enabled)
            .apply()
    }
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
        UiFontManager.init(context)
    }

    val systemDark = isSystemInDarkTheme()
    val isDark = when (ThemeManager.currentThemeMode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    // Material You：开启且系统支持时用动态取色，否则回退静态调色板
    val palette = if (ThemeManager.dynamicColor) {
        dynamicDshColors(context, isDark) ?: if (isDark) DarkDshColors else LightDshColors
    } else if (isDark) {
        DarkDshColors
    } else {
        LightDshColors
    }
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

    val materialColors = if (isDark) {
        darkColorScheme(
            primary = colors.brand400,
            onPrimary = Dsw.neutralBluish1000,
            // container/on-container 配对：深蓝容器 + 浅字（DSH deepseek 族）
            primaryContainer = Dsw.deepseek800,
            onPrimaryContainer = Dsw.deepseek100,
            secondary = colors.brand400,
            onSecondary = Dsw.neutralBluish1000,
            secondaryContainer = colors.bgSubtle,
            onSecondaryContainer = colors.labelPrimary,
            tertiary = colors.successContent,
            onTertiary = colors.successContainer,
            tertiaryContainer = colors.successContainer,
            onTertiaryContainer = colors.successContent,
            background = colors.bgBase,
            onBackground = colors.labelPrimary,
            surface = colors.bgSurface,
            onSurface = colors.labelPrimary,
            surfaceVariant = colors.bgSubtle,
            onSurfaceVariant = colors.labelSecondary,
            outline = colors.labelTertiary,
            outlineVariant = colors.borderStrong,
            error = colors.error,
            onError = Dsw.red900,
            errorContainer = Dsw.red900,
            onErrorContainer = Dsw.red100,
            inverseSurface = colors.labelPrimary,
            inverseOnSurface = colors.bgBase,
            inversePrimary = colors.brand400,
            scrim = Color.Black,
        )
    } else {
        lightColorScheme(
            primary = colors.brand400,
            onPrimary = Color.White,
            // 浅蓝容器 + 深蓝文字（alias-label-primary-bluish，≥ 9:1）
            primaryContainer = Dsw.deepseek200,
            onPrimaryContainer = Dsw.blue900,
            secondary = colors.brand400,
            onSecondary = Color.White,
            secondaryContainer = Dsw.deepseek100,
            onSecondaryContainer = colors.labelPrimary,
            tertiary = colors.successContent,
            onTertiary = Color.White,
            tertiaryContainer = colors.successContainer,
            onTertiaryContainer = colors.successContent,
            background = colors.bgBase,
            onBackground = colors.labelPrimary,
            surface = colors.bgSurface,
            onSurface = colors.labelPrimary,
            surfaceVariant = colors.bgSubtle,
            onSurfaceVariant = colors.labelSecondary,
            outline = colors.labelSecondary,
            outlineVariant = colors.borderStrong,
            error = colors.error,
            onError = Color.White,
            errorContainer = Dsw.red100,
            onErrorContainer = Dsw.red900,
            inverseSurface = colors.labelPrimary,
            inverseOnSurface = colors.bgBase,
            inversePrimary = colors.brand500,
            scrim = Color.Black,
        )
    }

    val baseDensity = LocalDensity.current
    val fontMultiplier = fontScaleMultiplier(FontScaleManager.currentScale)
    val uiFontFamily = if (UiFontManager.useSystemFont) FontFamily.Default else DshFontFamily
    val typography = remember(uiFontFamily) { dshTypography(uiFontFamily) }
    MaterialTheme(
        colorScheme = materialColors,
        typography = typography,
        shapes = DshMaterialShapes,
    ) {
        CompositionLocalProvider(
            LocalDshColors provides colors,
            LocalDshStrings provides strings,
            LocalTextStyle provides typography.bodyMedium,
            LocalDshFontFamily provides uiFontFamily,
            LocalDensity provides Density(
                density = baseDensity.density,
                fontScale = baseDensity.fontScale * fontMultiplier,
            ),
        ) {
            content()
        }
    }
}

/** App-wide UI font (Plus Jakarta Sans). Code blocks keep [FontFamily.Monospace]. */
val LocalDshFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Default }

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

    /** 「等你批准 / 等你回答」胶囊的暖色容器（与 [warnLabel] 成对）。 */
    val warnContainer: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.warnContainer

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

    val traceApproval: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.traceApproval

    val traceTodo: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.traceTodo

    val brandTint: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.brandTint

    val onBrand: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.onBrand

    val successContent: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.successContent

    val successContainer: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.successContainer

    val cloudContent: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.cloudContent

    val cloudContainer: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalDshColors.current.cloudContainer
}
