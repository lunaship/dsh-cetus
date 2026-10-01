package dev.deeplinks.core

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.deeplinks.R

/**
 * DeepLinks type system — Plus Jakarta Sans.
 * Latin/UI copy uses Jakarta; CJK glyphs fall back to the system sans.
 */
val DshFontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
)

fun dshTypography(family: FontFamily): Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.4).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.15).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.01.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.02.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.01.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.01.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.02.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.02.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.03.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.04.sp,
    ),
)

/**
 * 语义排版入口：业务代码只引用角色名，禁止再写裸 fontSize/lineHeight。
 *
 * 每个角色映射到 [dshTypography] 定义的字阶，因此应用内字号（FontScaleManager）
 * 与系统 fontScale 会自动生效，且全 App 排版收敛到同一套语义。
 * 尺寸→角色：12→bodySmall、13→titleSmall、15→bodyMedium、15→titleMedium、
 * 16→bodyLarge、17→titleLarge、18→headlineSmall、20→headlineMedium、24→headlineLarge、
 * 28→displayMedium、34→displayLarge。
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
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.01.sp,
        )

    /** 12/16：微标签（比 caption 紧一行）。E7：原 11sp，正文辅助信息最小 12sp。 */
    val microRelaxed: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.01.sp,
        )

    /** 12/16 · Medium：微标签强调。E7：原 11sp，正文辅助信息最小 12sp。 */
    val microMedium: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.01.sp,
        )

    /** 12/16 · SemiBold：微标签强强调（徽章/计数）。E7：原 11sp，正文辅助信息最小 12sp。 */
    val microStrong: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
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
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.01.sp,
        )

    /** 14/20：列表行副标题、弹层副标题、设备卡次行（M3 列表 supporting text 规格）。 */
    val supporting: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = LocalDshFontFamily.current,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.01.sp,
        )

}
