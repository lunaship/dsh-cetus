package dev.deeplinks.architecture

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 形状门禁（docs/visual-rules.md §4）。
 *
 * 圆角只允许 8 / 12 / 16 / 28，外加全圆（[DshRadius.full]、CircleShape、50%）。
 * 还没改完的文件见 [V4MigrationAllowlist]。
 */
class DshShapeRoleTest {

    @Test
    fun cornersStayOnV4Steps() {
        val root = V4MigrationAllowlist.mainSourceRoot()
        val motion = root.resolve("dev/deeplinks/native/DshMotion.kt").readText()
        val roles = radiusRoles(motion)
        val violations = mutableListOf<String>()
        for (file in V4MigrationAllowlist.kotlinFiles(root)) {
            val rel = V4MigrationAllowlist.relative(root, file)
            if (V4MigrationAllowlist.allows(rel)) continue
            val text = V4MigrationAllowlist.codeLines(file).joinToString("\n")
            for (match in Regex("""DshRadius\.(\w+)""").findAll(text)) {
                val name = match.groupValues[1]
                val dp = roles[name]
                if (name == "full") continue
                if (dp == null || dp !in STEP_DP) {
                    violations += "$rel: DshRadius.$name = ${dp ?: "未知"}dp，只允许 8/12/16/28 或全圆"
                }
            }
            for (shape in Regex("""RoundedCornerShape\(([^)]*)\)""").findAll(text)) {
                val inner = shape.groupValues[1]
                for (dp in Regex("""(\d+(?:\.\d+)?)\.dp""").findAll(inner)) {
                    val value = dp.groupValues[1].toDouble()
                    if (value != FULL_DP && value !in STEP_DP.map { it.toDouble() }) {
                        violations += "$rel: RoundedCornerShape ${dp.value}，只允许 8/12/16/28 或全圆"
                    }
                }
                val percent = Regex("""percent\s*=\s*(\d+)""").find(inner)
                if (percent != null && percent.groupValues[1] != "50") {
                    violations += "$rel: 圆角 percent=${percent.groupValues[1]}，全圆只用 50% 或 CircleShape"
                }
            }
            if (Regex("""\bDshTileShape\b""").containsMatchIn(text)) {
                violations += "$rel: DshTileShape 不是 v4 的四档圆角"
            }
        }
        assertTrue(
            "圆角不在 v4 四档（docs/visual-rules.md §4）：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    private fun radiusRoles(motion: String): Map<String, Int> {
        val start = motion.indexOf("object DshRadius {")
        assertTrue("DshMotion.kt 找不到 object DshRadius", start >= 0)
        val end = motion.indexOf("\n}", start)
        assertTrue("object DshRadius 没有正常闭合", end > start)
        return Regex("""val\s+(\w+)\s*=\s*(\d+)\.dp""")
            .findAll(motion.substring(start, end))
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
    }

    private companion object {
        val STEP_DP = setOf(8, 12, 16, 28)
        const val FULL_DP = 999.0
    }
}
