package dev.deeplinks.architecture

import dev.deeplinks.core.DarkDshColors
import dev.deeplinks.core.LightDshColors
import dev.deeplinks.core.contrastRatio
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 色源门禁（docs/visual-rules.md §2）。
 *
 * 硬编码颜色的 RGB 必须落在 v4 token 表里。不再要求颜色溯源到 Dsw / `--dsw-*`。
 * 还没改完的文件见 [V4MigrationAllowlist]。
 */
class DshPaletteProvenanceTest {

    @Test
    fun v4TokenTableRejectsArbitraryColors() {
        assertTrue(isV4ColorLiteral("FF3F5BD6"))
        assertTrue(isV4ColorLiteral("3F5BD6"))
        assertTrue(isV4ColorLiteral("FF121214"))
        assertTrue(isV4ColorLiteral("00000000"))
        assertFalse(isV4ColorLiteral("FFFF00FF"))
        assertFalse(isV4ColorLiteral("1234"))
    }

    @Test
    fun colorLiteralsStayOnTheV4TokenTable() {
        val root = V4MigrationAllowlist.mainSourceRoot()
        val offenders = mutableListOf<String>()
        for (file in V4MigrationAllowlist.kotlinFiles(root)) {
            val rel = V4MigrationAllowlist.relative(root, file)
            if (V4MigrationAllowlist.allows(rel)) continue
            V4MigrationAllowlist.codeLines(file).forEachIndexed { index, line ->
                for (match in COLOR_LITERAL.findAll(line)) {
                    if (!isV4ColorLiteral(match.groupValues[1])) {
                        offenders += "$rel:${index + 1}  ${line.trim()}"
                    }
                }
            }
        }
        assertTrue(
            "这些颜色不在 v4 token 表（docs/visual-rules.md §2）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun migrationAllowlistNamesRealFiles() {
        val root = V4MigrationAllowlist.mainSourceRoot()
        val missing = V4MigrationAllowlist.files.filterNot { File(root, it).isFile }
        assertTrue("白名单指向不存在的文件：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    @Test
    fun paletteMirrorHasNoHandWrittenRoles() {
        val root = V4MigrationAllowlist.mainSourceRoot()
        val code = File(root, "dev/deeplinks/core/DswPalette.kt").readLines()
            .filterNot { it.trimStart().let { line -> line.startsWith("//") || line.startsWith("*") || line.startsWith("/*") } }
        val offenders = code.filter { Regex("""\bDsh(Colors)?\.""").containsMatchIn(it) }
        assertTrue("DswPalette.kt 不得引用 DshColors 角色：\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun solidBrandFillCarriesItsIcon() {
        for ((name, colors) in listOf("light" to LightDshColors, "dark" to DarkDshColors)) {
            val ratio = contrastRatio(colors.onBrand, colors.brand500)
            assertTrue("$name onBrand/brand500 ratio=$ratio", ratio >= 3.0)
        }
    }

    private companion object {
        val COLOR_LITERAL = Regex("""Color\(0x([0-9A-Fa-f]{6,8})""")

        /** visual-rules-v4 §2，加上纯黑背景开关的 #000000 / #141416。 */
        val V4_RGB = setOf(
            "FFFFFF", "121214",
            "F4F5F7", "1C1D21",
            "E9EBEF", "27292E",
            "E3E5E9", "2D2F35",
            "15171C", "ECEDF0",
            "555A64", "A9ADB6",
            "6E737D", "8C9099",
            "3F5BD6", "8B9DFF",
            "ECEFFC",
            "B25E0C", "E9A35B",
            "1F7F4A", "62C28E",
            "C83A30", "F07B70",
            "FCF1E5", "E6F3EB", "FBEAE8",
            "000000", "141416",
        )

        fun isV4ColorLiteral(hex: String): Boolean {
            val normalized = hex.uppercase()
            val rgb = when (normalized.length) {
                6 -> normalized
                8 -> normalized.substring(2)
                else -> return false
            }
            return rgb in V4_RGB
        }
    }
}
