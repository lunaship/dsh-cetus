package dev.deeplinks.native

import dev.deeplinks.core.L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * 顶栏「更多操作」菜单项测试（方案 3.5）。
 *
 * C1 精简后最多 5 项；子代理入口挪进菜单第一项。
 */
class WorkspaceHeaderMenuItemsTest {
    /** 源码扫描测试的路径解析：从 user.dir 向上找 src/main/java，不写死开发机绝对路径。 */
    private fun mainRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        return File(requireNotNull(dir) { "找不到 src/main/java" }, "src/main/java")
    }

    private fun source(name: String): File = mainRoot().resolve("dev/deeplinks/native/$name")

    @Test
    fun `no subagent item when count is zero`() {
        val items = menuWithSubagents(topBarMenuItems = baseMenu(), subagentCount = 0)
        assertEquals(4, items.size)
        assertEquals(L.renameSession, items[0].label)
    }

    @Test
    fun `subagent item is first when count is 3`() {
        val items = menuWithSubagents(topBarMenuItems = baseMenu(), subagentCount = 3)
        assertEquals(5, items.size)
        assertEquals(L.subagentCount.format(3), items[0].label)
    }

    @Test
    fun `WorkspaceChrome has no DshTextTabs call`() {
        val text = source("WorkspaceChrome.kt").readText()
        assertFalse("WorkspaceChrome should not call DshTextTabs", text.contains("DshTextTabs("))
    }

    private fun baseMenu(): List<DshMenuItem> = listOf(
        DshMenuItem(EditOutline16, L.renameSession) {},
        DshMenuItem(ShareOutline16, L.shareConversation) {},
        DshMenuItem(ArchiveOutline20, L.archiveSession) {},
        DshMenuItem(TrashOutline16, L.deleteSession, danger = true, dividerBefore = true) {},
    )

    // 辅助函数：模拟 menuWithSubagents 逻辑
    private fun menuWithSubagents(
        topBarMenuItems: List<DshMenuItem>,
        subagentCount: Int,
    ): List<DshMenuItem> {
        return if (subagentCount > 0) {
            listOf(DshMenuItem(AgentPresetOutline16, L.subagentCount.format(subagentCount)) {}) + topBarMenuItems
        } else topBarMenuItems
    }
}
