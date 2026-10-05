package dev.deeplinks.native

import dev.deeplinks.core.L
import dev.deeplinks.core.scheduledTasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话 ⋯ 菜单（v4 4.9）的纯逻辑测试：上组「查看」，下组「操作」；归档 / 删除不在这里。
 */
class WorkspaceMenuTest {

    private class Ctx {
        val log = mutableListOf<String>()
    }

    private fun menu(
        ctx: Ctx = Ctx(),
        browseFiles: Boolean = false,
        changes: WorkspaceChangesSummary? = null,
        subagents: Int = 0,
    ) = sessionMenu(
        onClose = { ctx.log += "close" },
        changes = changes,
        onChanges = { ctx.log += "changes" },
        canBrowseFiles = browseFiles,
        onBrowseFiles = { ctx.log += "files" },
        subagentCount = subagents,
        onUsage = { ctx.log += "usage" },
        onRename = { ctx.log += "rename" },
        onShare = { ctx.log += "share" },
    )

    @Test
    fun baseMenuHasTraceAndUsageThenRenameShare() {
        val m = menu()
        assertEquals(listOf(MenuL.menuTrace, L.translation("usageOpen")), m.view.map { it.label })
        assertEquals(listOf(MenuL.menuRename, MenuL.menuShare), m.actions.map { it.label })
    }

    @Test
    fun archiveAndDeleteAreNotInSessionMenu() {
        val labels = menu(browseFiles = true).let { it.view + it.actions }.map { it.label }
        assertTrue(L.archiveSession !in labels)
        assertTrue(L.deleteSession !in labels)
    }

    @Test
    fun changesComeFirstWithSummary() {
        val c = WorkspaceChangesSummary(seq = 1, turn = 2, total = 4, added = 62, deleted = 9, files = emptyList())
        val first = menu(changes = c).view.first()
        assertEquals(MenuL.menuChanges, first.label)
        assertEquals(MenuL.menuChangesSubtitle.format(4, 62, 9), first.subtitle)
    }

    @Test
    fun optionalEntriesFollowCapabilities() {
        assertEquals(MenuL.menuFiles, menu(browseFiles = true).view[0].label)
        assertTrue(menu(subagents = 2).view.any { it.label == L.subagents })
        assertTrue(menu().view.none { it.label == L.subagents })
        // 定时任务和分叉不上手机
        assertTrue(menu().actions.none { it.label == L.scheduledTasks })
    }

    @Test
    fun clickingClosesMenuBeforeActing() {
        val ctx = Ctx()
        menu(ctx).actions.first { it.label == MenuL.menuRename }.onClick()
        assertEquals(listOf("close", "rename"), ctx.log)
    }

    /** 首组轨迹项文字随当前视图切换。 */
    @Test
    fun `menu trace item follows view mode`() {
        assertEquals("查看轨迹", dev.deeplinks.native.util.viewModeToggleLabel("chat", "查看轨迹", "返回对话"))
        assertEquals("返回对话", dev.deeplinks.native.util.viewModeToggleLabel("trace", "查看轨迹", "返回对话"))
    }
}
