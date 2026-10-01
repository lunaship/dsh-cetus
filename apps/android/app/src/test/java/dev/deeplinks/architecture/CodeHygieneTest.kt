package dev.deeplinks.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 代码卫生门禁（G3 巨型文件的机器判据）。
 *
 * 两层：
 * 1. 文件级——每文件行数预算（code-hygiene-baseline.txt）；
 * 2. 函数级——顶层函数体行数预算（function-hygiene-baseline.txt，默认 400 行）。
 *
 * 不引入 detekt（其与 AGP 9 内置 Kotlin 的兼容性曾有已知问题）。
 * 预算只允许下调。
 */
class CodeHygieneTest {

    private companion object {
        const val DEFAULT_MAX_FILE_LINES = 1500
        const val DEFAULT_MAX_FUNCTION_LINES = 400
    }

    private fun mainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "找不到 src/main/java" }
        return File(dir, "src/main/java")
    }

    private fun loadLimits(resource: String): Map<String, Int> {
        val stream = javaClass.getResourceAsStream(resource) ?: return emptyMap()
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

    @Test
    fun kotlinFilesStayWithinLineBudget() {
        val root = mainSourceRoot()
        val limits = loadLimits("/code-hygiene-baseline.txt")
        val violations = mutableListOf<String>()

        for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val rel = relative(root, file)
            val lines = file.readLines().size
            val max = limits[rel] ?: DEFAULT_MAX_FILE_LINES
            if (lines > max) {
                violations += rel + ": " + lines + " 行，超过预算 " + max + "（请拆解或调小基线，不要上调）"
            }
        }

        assertTrue(
            "文件体积超预算：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun topLevelFunctionsStayWithinBudget() {
        val root = mainSourceRoot()
        val limits = loadLimits("/function-hygiene-baseline.txt")
        val violations = mutableListOf<String>()

        for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val rel = relative(root, file)
            val lines = file.readLines()
            for (i in lines.indices) {
                val line = lines[i]
                if (!(line.startsWith("fun ") || line.startsWith("private fun ") || line.startsWith("internal fun "))) continue
                var brace = -1
                for (j in i until minOf(i + 40, lines.size)) {
                    if (lines[j].trimEnd().endsWith("{")) { brace = j; break }
                }
                if (brace < 0) continue
                var end = -1
                for (j in brace + 1 until lines.size) {
                    if (lines[j] == "}") { end = j; break }
                }
                if (end < 0) continue
                val length = end - i + 1
                val name = line.substringBefore("(").trim().split(" ").last()
                val key = rel + "::" + name
                val max = limits[key] ?: DEFAULT_MAX_FUNCTION_LINES
                if (length > max) {
                    violations += key + ": " + length + " 行，超过预算 " + max + "（请抽 UiState/子 composable）"
                }
            }
        }

        assertTrue(
            "函数体超预算：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    // ---- 图标规范（UI 精简整改第 6 步）----

    /** Icon(...) 的尺寸只能用 DshIconSize 四档 token，不许写裸 dp。 */
    @Test
    fun iconSizesUseDshIconSizeTokens() {
        val root = mainSourceRoot()
        val pattern = Regex("""Icon\((?:[^()]|\([^()]*\))*?Modifier\s*\.size\(\s*\d+(\.\d+)?\.dp""", RegexOption.DOT_MATCHES_ALL)
        val violations = mutableListOf<String>()
        for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val text = file.readText()
            for (m in pattern.findAll(text)) {
                val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                violations += relative(root, file) + ":" + line
            }
        }
        assertTrue(
            "Icon 尺寸必须用 DshIconSize.xs/sm/md/lg：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    /** 图标名末尾的数字必须等于视口；线宽统一 1.25（品牌 / 装饰件豁免）。 */
    @Test
    fun iconNamesMatchViewportAndStroke() {
        val file = File(mainSourceRoot(), "dev/deeplinks/native/DshIcons.kt")
        val text = file.readText()
        val exempt = setOf("FishLogo", "TreeCorner8x10", "ArchiveOutline20")
        val block = Regex("""val (\w+): ImageVector by lazy \{(.*?)\n\}""", RegexOption.DOT_MATCHES_ALL)
        val violations = mutableListOf<String>()
        for (m in block.findAll(text)) {
            val name = m.groupValues[1]
            if (name in exempt) continue
            val body = m.groupValues[2]
            val size = Regex("""(\d+)$""").find(name)?.groupValues?.get(1)
            val viewport = Regex("""viewportWidth = ([\d.]+)f""").find(body)?.groupValues?.get(1)?.toFloat()
            if (size == null || viewport == null || size.toFloat() != viewport) {
                violations += "$name: 视口 $viewport 与名称不符"
            }
            Regex("""strokeLineWidth = ([\d.]+)f""").findAll(body).map { it.groupValues[1] }
                .filter { it != "1.25" }
                .forEach { violations += "$name: 线宽 $it（应为 1.25）" }
        }
        assertTrue("图标规范违规：\n" + violations.joinToString("\n"), violations.isEmpty())
    }
}
