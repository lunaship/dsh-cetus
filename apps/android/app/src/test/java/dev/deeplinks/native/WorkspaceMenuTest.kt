package dev.deeplinks.native

import dev.deeplinks.core.L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶栏「更多操作」菜单的纯逻辑测试。对话/轨迹切换在顶栏胶囊分段里，不属于溢出菜单。
 *
 * 这些规则原先埋在 WorkspaceScreen 的 composable 里，无法单测；
 * 抽成 workspaceHeaderMenuItems 后即可表驱动验证。
 */
class WorkspaceMenuTest {

    private class Ctx {
        val log = mutableListOf<String>()
    }

    private fun menu(
        ctx: Ctx,
        viewMode: String = "chat",
        toolSearchOpen: Boolean = false,
        subagents: Int = 0,
        turnJumps: Int = 0,
    ) = workspaceHeaderMenuItems(
        viewMode = viewMode,
        toolSearchOpen = toolSearchOpen,
        activeSubagentCount = subagents,
        turnJumpCount = turnJumps,
        onCloseMenu = { ctx.log += "close" },
        onOpenToolSearch = { ctx.log += "toolSearch" },
        onShowSubagents = { ctx.log += "subagents" },
        onShowTurnJump = { ctx.log += "turnJump" },
        onRename = { ctx.log += "rename" },
        onFork = { ctx.log += "fork" },
        onCopyTitle = { ctx.log += "copy" },
        onArchive = { ctx.log += "archive" },
        onShareImage = { ctx.log += "shareImage" },
        onExport = { ctx.log += "export" },
        onOpenDevice = { ctx.log += "device" },
        onDelete = { ctx.log += "delete" },
    )

    @Test
    fun baseMenuAlwaysHasEightActions() {
        assertEquals(8, menu(Ctx(), viewMode = "trace").size)
    }

    @Test
    fun contextualItemsAreGatedByState() {
        assertEquals(9, menu(Ctx(), viewMode = "chat").size)  // 对话视图含工具查找
        assertEquals(8, menu(Ctx(), viewMode = "trace").size)  // 轨迹视图无工具查找
    }

    @Test
    fun subagentsAndTurnJumpsAppearOnlyWhenRelevant() {
        assertEquals(8, menu(Ctx(), viewMode = "trace", subagents = 0, turnJumps = 2).size)
        assertEquals(9, menu(Ctx(), viewMode = "trace", subagents = 1).size)
        assertEquals(9, menu(Ctx(), viewMode = "trace", turnJumps = 3).size)
        assertEquals(10, menu(Ctx(), viewMode = "trace", subagents = 1, turnJumps = 3).size)
    }

    @Test
    fun clickingClosesMenuBeforeActing() {
        val ctx = Ctx()
        val delete = menu(ctx).last()
        assertEquals(L.deleteSession, delete.label)
        assertTrue(delete.danger)
        delete.onClick()
        assertEquals(listOf("close", "delete"), ctx.log)
    }

    @Test
    fun deviceEntrySitsAboveDelete() {
        val ctx = Ctx()
        val items = menu(ctx)
        val device = items[items.lastIndex - 1]
        assertEquals(L.deviceAndPairing, device.label)
        device.onClick()
        assertEquals(listOf("close", "device"), ctx.log)
    }

    @Test
    fun contextualActionClosesMenuToo() {
        val ctx = Ctx()
        val toolSearch = menu(ctx, toolSearchOpen = false).first { it.label == L.searchToolCalls }
        assertEquals(L.searchToolCalls, toolSearch.label)
        toolSearch.onClick()
        assertEquals(listOf("close", "toolSearch"), ctx.log)
    }

    @Test
    fun toolSearchLabelReflectsOpenState() {
        val open = menu(Ctx(), viewMode = "chat", toolSearchOpen = true).first { it.label == L.closeToolSearch }
        assertEquals(L.closeToolSearch, open.label)
    }
}
