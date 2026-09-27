package dev.deeplinks.core

import androidx.compose.ui.graphics.Color

// 本文件由 DSH Web 主题包逐字镜像，勿手改；DSH 升级后按 docs/visual-rules.md「色源」一节重新生成。
// 来源：@deepseek-ai/dsh-client-ui-theme 的 --dsw-static-* 与 --dsw-alias-*（半透明值）。

/**
 * DeepSeek Harness 调色板（--dsw-static-*）。
 * DshColors 的每个角色从这里取值；取不到的值必须在 DshTheme.kt 里写「偏离 DSH」和原因。
 */
object Dsw {
    // amber
    val amber100 = Color(0xFFFEF5E7)
    val amber400 = Color(0xFFF7AD31)
    val amber500 = Color(0xFFF59E0B)
    val amber600 = Color(0xFFDD8629)
    val amber900 = Color(0xFF27241F)

    // blue
    val blue50 = Color(0xFFEFF6FF)
    val blue50p = Color(0xFFEAF3FF)
    val blue75 = Color(0xFFE5F0FF)
    val blue100 = Color(0xFFDBEAFE)
    val blue300 = Color(0xFF93C5FD)
    val blue400 = Color(0xFF60A5FA)
    val blue450 = Color(0xFF4D93F8)
    val blue500 = Color(0xFF3B82F6)
    val blue600 = Color(0xFF2563EB)
    val blue800 = Color(0xFF1E40AF)
    val blue900 = Color(0xFF0E3074)
    val blue950 = Color(0xFF172554)

    // deepseek
    val deepseek50 = Color(0xFFEDF3FE)
    val deepseek100 = Color(0xFFE4EDFD)
    val deepseek200 = Color(0xFFD3E2FF)
    val deepseek300 = Color(0xFFB7C8FE)
    val deepseek400 = Color(0xFF7AAAFF)
    val deepseek450 = Color(0xFF5686FE)
    val deepseek500 = Color(0xFF4176E6)
    val deepseek600 = Color(0xFF4868B2)
    val deepseek800 = Color(0xFF34415B)
    val deepseek900 = Color(0xFF283142)

    // green
    val green100 = Color(0xFFE6FAED)
    val green400 = Color(0xFF4ED17E)
    val green500 = Color(0xFF22C55E)
    val green900 = Color(0xFF233C2C)

    // neutral
    val neutral00 = Color(0xFFFFFFFF)
    val neutral50 = Color(0xFFFAFAFA)
    val neutral100 = Color(0xFFF5F5F5)
    val neutral150 = Color(0xFFEDEDED)
    val neutral200 = Color(0xFFE5E5E5)
    val neutral250 = Color(0xFFDCDCDC)
    val neutral300 = Color(0xFFD4D4D4)
    val neutral400 = Color(0xFFA2A4A6)
    val neutral500 = Color(0xFF7F8287)
    val neutral550 = Color(0xFF65676B)
    val neutral600 = Color(0xFF545557)
    val neutral700 = Color(0xFF3C3C3D)
    val neutral800 = Color(0xFF292929)
    val neutral850 = Color(0xFF212123)
    val neutral900 = Color(0xFF0F0F0F)
    val neutral1000 = Color(0xFF000000)

    // neutral-bluish
    val neutralBluish00 = Color(0xFFFFFFFF)
    val neutralBluish50 = Color(0xFFF9FAFB)
    val neutralBluish60 = Color(0xFFF5F6F7)
    val neutralBluish75 = Color(0xFFF1F3F5)
    val neutralBluish100 = Color(0xFFEBEEF2)
    val neutralBluish150 = Color(0xFFE9ECF2)
    val neutralBluish200 = Color(0xFFE1E5EE)
    val neutralBluish300 = Color(0xFFCFD3D6)
    val neutralBluish400 = Color(0xFFADB2B8)
    val neutralBluish500 = Color(0xFF979DA6)
    val neutralBluish600 = Color(0xFF81858C)
    val neutralBluish700 = Color(0xFF61666B)
    val neutralBluish750 = Color(0xFF43454A)
    val neutralBluish800 = Color(0xFF353638)
    val neutralBluish850 = Color(0xFF2C2C2E)
    val neutralBluish875 = Color(0xFF232324)
    val neutralBluish900 = Color(0xFF1B1B1C)
    val neutralBluish950 = Color(0xFF151517)
    val neutralBluish1000 = Color(0xFF0F1115)

    // red
    val red50 = Color(0xFFFEF2F2)
    val red100 = Color(0xFFFEE2E2)
    val red400 = Color(0xFFF25A5A)
    val red500 = Color(0xFFEF4444)
    val red600 = Color(0xFFEC1313)
    val red900 = Color(0xFF570C0C)

    // 半透明 alias（--dsw-alias-*，Light / Dark 两档）
    val borderL1Light = Color(0x0A000000)
    val borderL1Dark = Color(0x0FFFFFFF)
    val borderL2Light = Color(0x1A000000)
    val borderL2Dark = Color(0x1FFFFFFF)
    val borderL3Light = Color(0x1F000000)
    val borderL3Dark = Color(0x29FFFFFF)
    val borderL4Light = Color(0x29000000)
    val borderL4Dark = Color(0x33FFFFFF)
    val interactiveHoverLight = Color(0x0F263148)
    val interactiveHoverDark = Color(0x14FFFFFF)
    val interactiveActiveLight = Color(0x1A263148)
    val interactiveActiveDark = Color(0x24FFFFFF)
    val interactiveHoverDangerLight = Color(0x0DEC1313)
    val interactiveHoverDangerDark = Color(0x26F25A5A)
    val mask1Light = Color(0x3D000000)
    val mask1Dark = Color(0x80000000)
    val mask2Light = Color(0x1F000000)
    val mask2Dark = Color(0x33000000)
    val skeletonLight = Color(0x0A000000)
    val skeletonDark = Color(0x14FFFFFF)

    // --dsw-shadow-lv1 的颜色分量（0 2px 4px #0000000d）
    val shadowLv1 = Color(0x0D000000)
}
