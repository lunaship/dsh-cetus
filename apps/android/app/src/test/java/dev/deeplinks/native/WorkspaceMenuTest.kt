package dev.deeplinks.native

import dev.deeplinks.core.L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶栏「更多操作」菜单的纯逻辑测试。
 *
 * C1（2026-09-30）把菜单精简到最多 5 项：重命名 / 浏览文件（按能力）/ 分享 / 归档 / 删除。
 * 工具查找、跳转轮次、复制标题、设备入口、分叉都移除；子智能体挪到 Tab 行右侧。
 */
class WorkspaceMenuTest {

    private class Ctx {
        val log = mutableListOf<String>()
    }

    private fun menu(
        ctx: Ctx,
        browseFiles: Boolean = false,
    ) = workspaceHeaderMenuItems(
        canBrowseFiles = browseFiles,
        onCloseMenu = { ctx.log += "close" },
        onBrowseFiles = { ctx.log += "browseFiles" },
        onRename = { ctx.log += "rename" },
        onShare = { ctx.log += "share" },
        onArchive = { ctx.log += "archive" },
        onDelete = { ctx.log += "delete" },
    )

    @Test
    fun baseMenuHasFourActionsWithoutFileBrowsing() {
        assertEquals(4, menu(Ctx()).size)
    }

    @Test
    fun browseFilesAppearsOnlyWhenPluginSupportsTree() {
        assertEquals(5, menu(Ctx(), browseFiles = true).size)
        val items = menu(Ctx(), browseFiles = true)
        assertEquals(L.renameSession, items[0].label)
        assertEquals(L.browseFiles, items[1].label)
        assertEquals(L.shareConversation, items[2].label)
    }

    @Test
    fun shareOpensSubSheetAndClosesMenu() {
        val ctx = Ctx()
        val share = menu(ctx).first { it.label == L.shareConversation }
        share.onClick()
        assertEquals(listOf("close", "share"), ctx.log)
    }

    @Test
    fun clickingClosesMenuBeforeActing() {
        val ctx = Ctx()
        val rename = menu(ctx).first { it.label == L.renameSession }
        rename.onClick()
        assertEquals(listOf("close", "rename"), ctx.log)
    }

    @Test
    fun deleteIsLastWithDangerAndDivider() {
        val items = menu(Ctx())
        val delete = items.last()
        assertEquals(L.deleteSession, delete.label)
        assertTrue(delete.danger)
        assertTrue(delete.dividerBefore)
    }
}
