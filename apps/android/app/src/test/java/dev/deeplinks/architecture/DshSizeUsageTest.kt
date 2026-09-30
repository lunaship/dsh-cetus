package dev.deeplinks.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 尺寸与触控热区门禁（docs/visual-rules.md 第五节，S1/S2 新增）。
 *
 * 规则：
 * - 间距继续走 DshSpace（已由 DshSpacingUsageTest 覆盖）；
 * - 图标尺寸走 DshIconSize，行高走 DshRowHeight，触控热区走 DshTouch，圆角走 DshRadius；
 * - padding / spacedBy / height / width / size 里出现新的裸 dp 时，必须同步更新 size-baseline.txt。
 *
 * 本测试只检查裸 dp 的**数量上限**，不强制全部替换成 token（存量历史债务留在 baseline 里）。
 */
class DshSizeUsageTest {

    private val sizeCall = Regex(
        """(?:\b(?:padding|spacedBy|height|width|size|clickable|padding)\(|Modifier\s*\.\s*(?:height|width|size|padding)\()"""
    )
    private val rawDp = Regex("""(?<![\w.])(\d+(?:\.\d+)?)\.dp\b""")

    private fun mainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "找不到 src/main/java，user.dir=" + System.getProperty("user.dir") }
        return File(dir, "src/main/java")
    }

    private fun baselineLimits(): Map<String, Int> {
        val stream = javaClass.getResourceAsStream("/size-baseline.txt") ?: return emptyMap()
        return stream.bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val parts = line.split(Regex("\\s+"))
                parts[0] to parts[1].toInt()
            }
    }

    @Test
    fun sizesComeFromTokens() {
        val root = mainSourceRoot()
        val limits = baselineLimits()
        val violations = mutableListOf<String>()
        for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val rel = file.relativeTo(root).path.replace(File.separatorChar, '/')
            if (rel == "dev/deeplinks/native/DshSpace.kt") continue
            if (rel == "dev/deeplinks/native/DshIconSize.kt") continue
            if (rel == "dev/deeplinks/native/DshTouch.kt") continue
            if (rel == "dev/deeplinks/native/DshRowHeight.kt") continue
            if (rel == "dev/deeplinks/core/DshRadius.kt") continue
            val raw = sizeCall.findAll(file.readText())
                .flatMap { call -> rawDp.findAll(call.value) }
                .count { it.groupValues[1].toDouble() != 0.0 }
            val limit = limits[rel] ?: 0
            if (raw > limit) {
                violations += "$rel: 裸 dp $raw 处，超过预算 $limit"
            }
        }
        assertTrue(
            "尺寸没走 token（S1/S2）：\n" + violations.joinToString("\n") +
                "\n\n修复：改用 DshIconSize / DshTouch / DshRowHeight / DshRadius；" +
                "存量债务留在 size-baseline.txt，新增必须同步更新 baseline。",
            violations.isEmpty(),
        )
    }
}
