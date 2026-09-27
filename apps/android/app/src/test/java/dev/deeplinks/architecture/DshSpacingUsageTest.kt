package dev.deeplinks.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 间距刻度门禁（docs/visual-rules.md 第七节）。
 *
 * padding / spacedBy / PaddingValues / Spacer 里的非 0 裸 dp 按 spacing-baseline.txt 的
 * 每文件上限计数：超过即失败，未登记的文件上限为 0。刻度内的值写 DshSpace.sN。
 * 只看这四种间距调用：尺寸、描边、阴影里的 dp 不归这里管。
 */
class DshSpacingUsageTest {

    private val spacingCall = Regex(
        """(?:\b(?:padding|spacedBy|PaddingValues)\(|Spacer\(\s*(?:modifier\s*=\s*)?Modifier\s*\.\s*(?:height|width|size)\()[^()]*\)"""
    )
    private val rawDp = Regex("""(?<![\w.])(\d+(?:\.\d+)?)\.dp\b""")

    private fun mainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "找不到 src/main/java，user.dir=" + System.getProperty("user.dir") }
        return File(dir, "src/main/java")
    }

    private fun baselineLimits(): Map<String, Int> {
        val stream = javaClass.getResourceAsStream("/spacing-baseline.txt") ?: return emptyMap()
        return stream.bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val parts = line.split(Regex("\\s+"))
                parts[0] to parts[1].toInt()
            }
    }

    @Test
    fun spacingComesFromTheScale() {
        val root = mainSourceRoot()
        val limits = baselineLimits()
        val violations = mutableListOf<String>()
        for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val rel = file.relativeTo(root).path.replace(File.separatorChar, '/')
            if (rel == "dev/deeplinks/native/DshSpace.kt") continue
            val raw = spacingCall.findAll(file.readText())
                .flatMap { call -> rawDp.findAll(call.value) }
                .count { it.groupValues[1].toDouble() != 0.0 }
            val limit = limits[rel] ?: 0
            if (raw > limit) {
                violations += "$rel: 间距裸 dp $raw 处，超过预算 $limit"
            }
        }
        assertTrue(
            "间距没走刻度：\n" + violations.joinToString("\n") +
                "\n\n修复：改用 DshSpace.s2…s32（native/DshSpace.kt）；" +
                "迁移减少了余量时，请同步调小 app/src/test/resources/spacing-baseline.txt",
            violations.isEmpty(),
        )
    }
}
