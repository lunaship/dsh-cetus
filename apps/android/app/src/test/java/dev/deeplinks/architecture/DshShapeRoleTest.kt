package dev.deeplinks.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 形状角色门禁（docs/visual-rules.md 第三节）。
 *
 * DshRadius 收敛为 6 个用途命名角色；旧档位（xs/sm/md/lg/xl/tail/sheet/dialog）
 * 只是映射到新角色的弃用别名，页面级语义 group 已删除。Material Shapes 必须
 * 映射到同一套语义半径，标准组件不得回落到另一套形状。
 */
class DshShapeRoleTest {

    private companion object {
        /** 角色名 -> 数值（dp）。 */
        val ROLES = mapOf(
            "micro" to 2,
            "control" to 8,
            "container" to 12,
            "composer" to 22,
            "modal" to 28,
            "full" to 999,
        )

        /**
         * 已删除的旧档位名（2026-09-27 批次 6 起零容忍）：
         * 迁移完成后 DshRadius 只允许六个用途角色，旧名不得回潮。
         */
        val RETIRED_NAMES = setOf("xs", "sm", "md", "lg", "xl", "tail", "sheet", "dialog", "group")

        /** Material Shapes 角色 -> DSH 语义半径。 */
        val MATERIAL_SHAPES = mapOf(
            "extraSmall" to "control",
            "small" to "control",
            "medium" to "container",
            "large" to "container",
            "extraLarge" to "modal",
        )
    }

    private fun mainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "找不到 src/main/java，user.dir=" + System.getProperty("user.dir") }
        return File(dir, "src/main/java")
    }

    private fun radiusBlock(motion: String): String {
        val start = motion.indexOf("object DshRadius {")
        assertTrue("DshMotion.kt 找不到 object DshRadius", start >= 0)
        val end = motion.indexOf("\n}", start)
        assertTrue("object DshRadius 没有正常闭合", end > start)
        return motion.substring(start, end)
    }

    @Test
    fun radiusRolesAreFixed() {
        val motion = File(mainSourceRoot(), "dev/deeplinks/native/DshMotion.kt").readText()
        val block = radiusBlock(motion)
        val violations = mutableListOf<String>()
        for ((role, dp) in ROLES) {
            val match = Regex("""val $role = (\d+)\.dp""").find(block)
            if (match == null) {
                violations += "$role: 缺失（六个用途角色一个都不能少）"
            } else if (match.groupValues[1].toInt() != dp) {
                violations += "$role: ${match.groupValues[1]}dp，应为 ${dp}dp"
            }
        }
        assertTrue(
            "DshRadius 角色面与合同不符（docs/visual-rules.md 第三节）：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun retiredRadiusNamesStayDeleted() {
        val motion = File(mainSourceRoot(), "dev/deeplinks/native/DshMotion.kt").readText()
        val block = radiusBlock(motion)
        val violations = mutableListOf<String>()
        for (name in RETIRED_NAMES) {
            if (Regex("""val $name\s*=""").containsMatchIn(block)) {
                violations += "$name: 旧档位/页面级形状名已删除，不得回潮（只用六个用途角色）"
            }
        }
        assertTrue(
            "废弃形状名回潮（docs/visual-rules.md 第三节）：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun materialShapesMapToDshRoles() {
        val theme = File(mainSourceRoot(), "dev/deeplinks/core/DshTheme.kt").readText()
        val start = theme.indexOf("private val DshMaterialShapes = Shapes(")
        assertTrue("DshTheme.kt 找不到 DshMaterialShapes", start >= 0)
        val end = theme.indexOf("\n)", start)
        assertTrue("DshMaterialShapes 没有正常闭合", end > start)
        val block = theme.substring(start, end)
        val violations = mutableListOf<String>()
        for ((slot, role) in MATERIAL_SHAPES) {
            val match = Regex("""$slot = RoundedCornerShape\(DshRadius\.(\w+)\)""").find(block)
            if (match == null) {
                violations += "$slot: 未映射到 DshRadius"
            } else if (match.groupValues[1] != role) {
                violations += "$slot: 映射到 DshRadius.${match.groupValues[1]}，应为 DshRadius.$role"
            }
        }
        assertTrue(
            "Material Shapes 必须映射到 DSH 语义半径：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }
}
