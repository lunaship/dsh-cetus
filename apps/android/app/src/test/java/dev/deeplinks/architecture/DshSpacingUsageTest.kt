package dev.deeplinks.architecture

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 间距门禁（docs/visual-rules.md §4）。
 *
 * padding / spacedBy / PaddingValues / Spacer 里的裸 dp，以及 DshSpace.sN，
 * 只允许 4 的倍数，范围 4–32（0 表示不留白）。
 */
class DshSpacingUsageTest {

    private val spacingCall = Regex(
        """(?:\b(?:padding|spacedBy|PaddingValues)\(|Spacer\(\s*(?:modifier\s*=\s*)?Modifier\s*\.\s*(?:height|width|size)\()[^()]*\)"""
    )
    private val rawDp = Regex("""(?<![\w.])(\d+(?:\.\d+)?)\.dp\b""")
    private val spaceToken = Regex("""DshSpace\.s(\d+)\b""")

    @Test
    fun spacingStaysOnTheV4Scale() {
        val root = ArchitectureSources.mainSourceRoot()
        val violations = mutableListOf<String>()
        for (file in ArchitectureSources.kotlinFiles(root)) {
            val rel = ArchitectureSources.relative(root, file)
            val text = ArchitectureSources.codeLines(file).joinToString("\n")
            val raw = spacingCall.findAll(text)
                .flatMap { call -> rawDp.findAll(call.value) }
                .map { it.groupValues[1].toDouble() }
                .filter { it !in ALLOWED_DP }
                .toList()
            if (raw.isNotEmpty()) {
                violations += "$rel: 间距裸 dp ${raw.distinct()}，只允许 4 的倍数（4–32，或 0）"
            }
            val tokens = spaceToken.findAll(text)
                .map { it.groupValues[1].toInt() }
                .filter { it !in ALLOWED_STEPS }
                .toList()
            if (tokens.isNotEmpty()) {
                violations += "$rel: DshSpace.s${tokens.distinct().joinToString("/s")} 不在 4–32 的 4 倍刻度上"
            }
        }
        assertTrue(
            "间距不在 v4 刻度（docs/visual-rules.md §4）：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    private companion object {
        val ALLOWED_STEPS = setOf(4, 8, 12, 16, 20, 24, 28, 32)
        val ALLOWED_DP = ALLOWED_STEPS.map { it.toDouble() }.toSet() + 0.0
    }
}
