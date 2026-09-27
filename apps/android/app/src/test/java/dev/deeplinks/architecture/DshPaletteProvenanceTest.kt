package dev.deeplinks.architecture

import dev.deeplinks.core.DarkDshColors
import dev.deeplinks.core.LightDshColors
import dev.deeplinks.core.contrastRatio
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 色源门禁（docs/visual-rules.md「色源」）：DSH 是唯一参照。
 *
 * DshTheme.kt 里的颜色一律取自 Dsw（DSH Web 调色板镜像，core/DswPalette.kt）；
 * 仍写 Color(0x...) 字面量的行，必须在同一行写「偏离 DSH」和原因。
 * 这样每一处跟 DSH 不一样的地方都能 grep 出来，下一轮改动不会在不知情时推翻它。
 */
class DshPaletteProvenanceTest {

    private fun coreFile(name: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "找不到 src/main/java，user.dir=" + System.getProperty("user.dir") }
        return File(dir, "src/main/java/dev/deeplinks/core/$name")
    }

    @Test
    fun everyThemeLiteralDeclaresItsDeviation() {
        val offenders = coreFile("DshTheme.kt").readLines()
            .mapIndexedNotNull { i, line ->
                if ("Color(0x" in line && "偏离 DSH" !in line) "DshTheme.kt:${i + 1}  ${line.trim()}" else null
            }
        assertTrue(
            "这些颜色既没取自 Dsw，也没写「偏离 DSH」原因：\n" + offenders.joinToString("\n") +
                "\n\n修复：优先改用 Dsw.*（core/DswPalette.kt）；确需偏离时在行尾写「// 偏离 DSH：原因」",
            offenders.isEmpty(),
        )
    }

    @Test
    fun paletteMirrorHasNoHandWrittenRoles() {
        // Dsw 只放 DSH 的原始色阶和半透明 alias；角色语义属于 DshColors，不能混进镜像文件。
        val code = coreFile("DswPalette.kt").readLines()
            .filterNot { it.trimStart().let { l -> l.startsWith("//") || l.startsWith("*") || l.startsWith("/*") } }
        val offenders = code.filter { Regex("""\bDsh(Colors)?\.""").containsMatchIn(it) }
        assertTrue("DswPalette.kt 不得引用 DshColors 角色：\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun solidBrandFillCarriesItsIcon() {
        // 发送 / 停止 / 确认槽：onBrand 图标在 brand500 实心底上至少 3:1（WCAG 非文字对比度）。
        for ((name, colors) in listOf("light" to LightDshColors, "dark" to DarkDshColors)) {
            val ratio = contrastRatio(colors.onBrand, colors.brand500)
            assertTrue("$name onBrand/brand500 ratio=$ratio", ratio >= 3.0)
        }
    }
}
