package dev.deeplinks.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 表面角色门禁（docs/visual-rules.md 第二节）。
 *
 * 1. 旧 grouped 别名（bgGrouped / bgGroupedCard）在 2026-09-27 批次 6 已删除，
 *    本测试零容忍其回潮（含 DshColors 字段与 Dsh.* 代理）；
 * 2. bgSelected 与 bgNavSelected 必须是同一个 selection container 值
 *    （选中态统一 DSH Blue tonal，不用纯黑反色）；
 * 3. 浮层阴影只允许真正的浮层（Dialog / Sheet / Menu / 命令面板 / FAB）；
 *    普通列表与行内卡片不得用阴影假装层级，存量按预算收敛。
 */
class DshSurfaceRoleTest {

    private companion object {
        /** token 定义文件不参与调用统计。 */
        val TOKEN_FILES = setOf(
            "dev/deeplinks/core/DshTheme.kt",
        )

        /**
         * bgGrouped 调用存量（逐批收敛到 bgBase）。
         * 批次 5：底部面板与统计弹窗都迁到 bgBase / bgCard，预算清零，零容忍。
         */
        val BG_GROUPED_BUDGET: Map<String, Int> = emptyMap()

        /**
         * bgGroupedCard 调用存量（逐批收敛到 bgSubtle / bgInput）。
         * 批次 2-5：设置、设备、首页、调色板、输入面全部清零，零容忍。
         */
        val BG_GROUPED_CARD_BUDGET: Map<String, Int> = emptyMap()

        /** 真正的浮层：允许阴影。 */
        val OVERLAY_FILES = setOf(
            "dev/deeplinks/native/WorkspaceDialogs.kt",
        )

        /**
         * 其余文件的阴影存量：只留真正悬浮的面——composer 输入卡、命令面板浮卡、
         * 回到底部 FAB、统计弹窗。批次 5 已清零 Approval/Question 卡的行内阴影。
         */
        val SHADOW_BUDGET = mapOf(
            "dev/deeplinks/native/ComposerBar.kt" to 1,
            "dev/deeplinks/native/WorkspaceChrome.kt" to 3,
        )

        val SHADOW = Regex("""\.shadow\(""")
    }

    private fun mainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "找不到 src/main/java，user.dir=" + System.getProperty("user.dir") }
        return File(dir, "src/main/java")
    }

    private fun relative(root: File, file: File): String =
        file.relativeTo(root).path.replace(File.separatorChar, '/')

    private fun count(file: File, regex: Regex): Int {
        var n = 0
        file.forEachLine { if (regex.containsMatchIn(it)) n++ }
        return n
    }

    @Test
    fun groupedAliasesOnlyShrink() {
        val root = mainSourceRoot()
        val violations = mutableListOf<String>()
        for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val rel = relative(root, file)
            if (rel in TOKEN_FILES) continue
            for ((name, budget) in listOf(
                "bgGrouped" to BG_GROUPED_BUDGET,
                "bgGroupedCard" to BG_GROUPED_CARD_BUDGET,
            )) {
                val used = count(file, Regex("""Dsh\.$name\b"""))
                val limit = budget[rel] ?: 0
                if (used > limit) {
                    violations += "$rel: Dsh.$name $used 处，超过预算 $limit。" +
                        "请收敛为 " + (if (name == "bgGrouped") "bgBase" else "bgSubtle")
                }
            }
        }
        assertTrue(
            "旧 grouped 别名只降不升（docs/visual-rules.md 第二节）：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun selectionContainerIsOneRole() {
        val theme = File(mainSourceRoot(), "dev/deeplinks/core/DshTheme.kt").readText()
        val violations = mutableListOf<String>()
        for (palette in listOf("val DarkDshColors", "val LightDshColors")) {
            val start = theme.indexOf(palette)
            assertTrue("DshTheme.kt 找不到 $palette", start >= 0)
            val end = theme.indexOf("\n)", start)
            val block = theme.substring(start, if (end > start) end else theme.length)
            fun value(name: String) =
                Regex("""$name = Color\((0x[0-9A-Fa-f]+)\)""").find(block)?.groupValues?.get(1)
            val selected = value("bgSelected")
            val navSelected = value("bgNavSelected")
            if (selected == null || navSelected == null) {
                violations += "$palette: 找不到 bgSelected / bgNavSelected 定义"
            } else if (selected != navSelected) {
                violations += "$palette: bgSelected=$selected 与 bgNavSelected=$navSelected 不一致。" +
                    "选中态必须合并为一个 DSH Blue tonal selection container"
            }
        }
        assertTrue(
            "selection container 合并（docs/visual-rules.md 第二节）：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun shadowsOnlyOnRealOverlays() {
        val root = mainSourceRoot()
        val violations = mutableListOf<String>()
        for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val rel = relative(root, file)
            if (rel in TOKEN_FILES || rel in OVERLAY_FILES) continue
            val used = count(file, SHADOW)
            val limit = SHADOW_BUDGET[rel] ?: 0
            if (used > limit) {
                violations += "$rel: .shadow( $used 处，超过预算 $limit。" +
                    "普通列表/行内卡片用 tonal 分层，阴影只留给真正悬浮的浮层"
            }
        }
        assertTrue(
            "浮层阴影越界（docs/visual-rules.md 第二节）：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }
}
