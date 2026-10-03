package dev.deeplinks.core

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * v4 字阶（docs/visual-rules.md §5）：系统字体，只有 26 / 17 / 15 / 13 / 12 五档，
 * 字重只用 400 / 500 / 600。代码、路径、命令用 [FontFamily.Monospace]。
 */
fun dshTypography(): Typography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    displayMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

/**
 * 语义排版入口：业务代码只引用角色名，禁止再写裸 fontSize/lineHeight。
 *
 * 每个角色映射到 [dshTypography] 定义的字阶，因此应用内字号（FontScaleManager）
 * 与系统 fontScale 会自动生效，且全 App 排版收敛到同一套语义。
 * 尺寸→角色：12→bodySmall/labelMedium、13→titleSmall/labelLarge、15→bodyLarge/bodyMedium/titleMedium、
 * 17→titleLarge/headlineSmall/headlineMedium、26→displayLarge/displayMedium/headlineLarge。
 */
/**
 * 数字用等宽数位（tnum）而不是换成等宽字体：统计、计数、耗时、增删行数都走这里，
 * 字形与正文一致、列对齐稳定。等宽字体只留给代码、命令、路径和配对码这类标识符。
 */
fun TextStyle.tabularNums(): TextStyle = copy(fontFeatureSettings = "tnum")

object DshType {
    val caption: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodySmall

    val label: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelMedium

    val titleSmall: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.titleSmall

    val body: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyMedium

    /** 15/22 · SemiBold：正文强调（行内重点、待办标题）。Web 移植期的 13sp 粗体已并入此角色。 */
    val bodyStrong: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)

    val title: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.titleMedium

    val bodyLarge: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyLarge

    val titleLarge: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.titleLarge

    val headline: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.headlineSmall

    val headlineMedium: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.headlineMedium

    val display: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.displayMedium

    val displayLarge: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.displayLarge

    // ===== 密集档（dense tier）：聊天与列表的次级文本 =====
    // 与语义角色同一套 M3 字阶，只是更小的尺寸/更紧的行高。契约由
    // DshTypeScaleTest 守护：尺寸必须落在字阶表内、行高 >= 1.3x字号、
    // 且 >=13sp 的角色行高不得超过 18sp（防止 Web 移植期「小字号 + 松行高」的
    // 正文回流）。新增角色前先读该测试。

    /** 12/18：密集次要文本（比 caption 松一行）。 */
    val captionRelaxed: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.01.sp,
        )

    /** 12/16：微标签（比 caption 紧一行）。E7：原 11sp，正文辅助信息最小 12sp。 */
    val microRelaxed: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.01.sp,
        )

    /** 12/16 · Medium：微标签强调。E7：原 11sp，正文辅助信息最小 12sp。 */
    val microMedium: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.01.sp,
        )

    /** 12/16 · SemiBold：微标签强强调（徽章/计数）。E7：原 11sp，正文辅助信息最小 12sp。 */
    val microStrong: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.01.sp,
        )

    val labelLarge: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelLarge

    /** 12/18 · Medium：次级标签（附件名、压缩提示）。 */
    val captionMedium: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.01.sp,
        )

    /** 13/18：列表行副标题、弹层副标题、设备卡次行。 */
    val supporting: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.01.sp,
        )

    /** 15/22 · Normal：列表主标题。 */
    val listTitle: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.01.sp,
        )
}
