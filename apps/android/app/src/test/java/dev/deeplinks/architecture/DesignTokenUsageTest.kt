package dev.deeplinks.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设计 token 强制门禁：业务代码不得再写裸字号（N.sp）或裸色值（Color(0x...)）。
 *
 * 存量用 design-token-baseline.txt 记录为「每文件上限」：
 * - 任何非白名单文件出现裸色值 -> 失败；
 * - 任何文件的裸字号数量超过基线 -> 失败；
 * - 迁移使数量下降后应把基线调小（允许收敛，禁止回涨）。
 *
 * 白名单只放 token 定义文件（DshTheme / DshTypography / DshSyntaxPalette / DswPalette）。
 */
class DesignTokenUsageTest {

    private val fontRegex = Regex("""\b\d+(\.\d+)?\.sp\b""")
    private val colorRegex = Regex("""Color\(0x""")

    private val allowlist = setOf(
        "dev/deeplinks/core/DshTheme.kt",
        "dev/deeplinks/core/DshTypography.kt",
        "dev/deeplinks/core/DshSyntaxPalette.kt",
        "dev/deeplinks/core/DswPalette.kt",
    )

    private fun mainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "找不到 src/main/java，user.dir=" + System.getProperty("user.dir") }
        return File(dir, "src/main/java")
    }

    private fun baselineLimits(): Map<String, Int> {
        val stream = javaClass.getResourceAsStream("/design-token-baseline.txt")
            ?: return emptyMap()
        return stream.bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val parts = line.split(Regex("\\s+"))
                parts[0] to parts[1].toInt()
            }
    }

    private fun relative(root: File, file: File): String =
        file.relativeTo(root).path.replace(File.separatorChar, '/')

    private fun countMatches(file: File, regex: Regex): Int {
        var count = 0
        file.forEachLine { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("import ")) return@forEachLine
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEachLine
            if (regex.containsMatchIn(line)) count++
        }
        return count
    }

    @Test
    fun noRawFontSizesOrColorsOutsideTokenLayer() {
        val root = mainSourceRoot()
        val limits = baselineLimits()
        val violations = mutableListOf<String>()

        for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val rel = relative(root, file)
            if (rel in allowlist) continue
            var fontLines = 0
            var colorLines = 0
            file.forEachLine { line ->
                if (fontRegex.containsMatchIn(line)) fontLines++
                if (colorRegex.containsMatchIn(line)) colorLines++
            }
            if (colorLines > 0) {
                violations += rel + ": " + colorLines + " 处裸色值 Color(0x...)，请改用 Dsh 颜色角色"
            }
            val limit = limits[rel] ?: 0
            if (fontLines > limit) {
                violations += rel + ": 裸字号 " + fontLines + " 处，超过基线 " + limit + "，请改用 DshType"
            }
        }

        assertTrue(
            "设计 token 违规：\n" + violations.joinToString("\n") +
                "\n\n修复：改用 Dsh.* 颜色角色 / DshType.* 排版；" +
                "若迁移减少了存量，请同步调小 app/src/test/resources/design-token-baseline.txt",
            violations.isEmpty()
        )
    }

    /**
     * 弃用形状角色零容忍（docs/visual-rules.md 第三节）。
     *
     * 2026-09-27 批次 6：xs/sm/md/lg/xl/tail/sheet/dialog/group 已全部删除，
     * DshRadius 只剩 micro/control/container/composer/modal/full 六个用途角色。
     * 旧名（含定义处的别名）在任何源文件里出现都失败。
     */
    @Test
    fun retiredRadiusRolesStayDeleted() {
        val root = mainSourceRoot()
        val retired = setOf("xs", "sm", "md", "lg", "xl", "tail", "sheet", "dialog", "group")
        val violations = mutableListOf<String>()
        for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val rel = relative(root, file)
            for (role in retired) {
                val used = countMatches(file, Regex("""DshRadius\.$role\b"""))
                if (used > 0) {
                    violations += "$rel: DshRadius.$role $used 处——旧档位已删除，" +
                        "请改用六个用途角色（micro/control/container/composer/modal/full）"
                }
            }
        }
        assertTrue(
            "废弃形状名回潮（docs/visual-rules.md 第三节）：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    /**
     * 图标只有一套：Web 复刻集（DshIcons.kt）+ 同笔法自绘补充（DshGlyphs.kt）。
     * Material Icons 自带 24 格内边距、笔画粗细也不同，混用会让同一行里的图标大小不一。
     */
    @Test
    fun iconsComeFromTheInHouseSetOnly() {
        val root = mainSourceRoot()
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.readText().contains("androidx.compose.material.icons") }
            .map { relative(root, it) }
            .toList()
        assertTrue(
            "这些文件引入了 Material Icons，请改用 DshIcons / DshGlyphs（缺的图标在 DshGlyphs.kt 按同一笔法补）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /**
     * 圆角只能取 DshRadius / DshTileShape / CircleShape：细条与进度条用 full，
     * 色块用 xs，卡片用 group，图标底板用 DshTileShape。存量已清零，不设基线。
     */
    @Test
    fun cornerRadiiComeFromTokens() {
        val root = mainSourceRoot()
        val raw = Regex("""RoundedCornerShape\(\s*\d|(topStart|topEnd|bottomStart|bottomEnd)\s*=\s*\d+(\.\d+)?\.dp""")
        val offenders = mutableListOf<String>()
        for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val rel = relative(root, file)
            file.readLines().forEachIndexed { i, line ->
                if (raw.containsMatchIn(line)) offenders += rel + ":" + (i + 1) + "  " + line.trim()
            }
        }
        assertTrue(
            "裸圆角（请改用 DshRadius.* / DshTileShape / CircleShape）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
