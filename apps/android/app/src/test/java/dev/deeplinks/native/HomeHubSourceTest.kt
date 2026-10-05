package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeHubSourceTest {

    private fun mainRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        return File(requireNotNull(dir), "src/main/java")
    }

    private fun file(name: String): File {
        return mainRoot().resolve("dev/deeplinks/native/$name")
    }

    /** v4 2.1：首页不再用玻璃胶囊 / 悬浮条；顶栏是居中电脑名，不再是 DeepLinks 大标题。 */
    @Test
    fun `home files use v4 components instead of glass`() {
        for (name in listOf("HomeHub.kt", "HomeWorkspacePage.kt", "HomeWorkspaceChrome.kt", "WorkspaceSidebar.kt", "WorkspaceSidebarItems.kt")) {
            val text = file(name).readText()
            for (banned in listOf("DshGlass", "DshFloatingControls", "DshEdgeFade", "DshTranslucentBar", "DshCardRows")) {
                assertFalse("$name 仍引用 $banned", text.contains(banned))
            }
        }
        val chrome = file("HomeWorkspaceChrome.kt").readText()
        val header = chrome.substring(chrome.indexOf("internal fun HomeTopBar("), chrome.indexOf("internal fun HomeMoreSheet("))
        assertFalse(header.contains("large = true"))
        assertTrue(header.contains("onOpenMore"))
        assertTrue(file("HomeWorkspacePage.kt").readText().contains("HomeTopBar("))
    }

    /** v4 2.5：工作区只在首页文件夹里出现一次，不再有单独列出工作区的「电脑与工作区」弹层。 */
    @Test
    fun `workspaces are listed only in home folders`() {
        for (name in listOf("HomeHub.kt", "HomeWorkspacePage.kt", "WorkspaceSidebar.kt")) {
            val text = file(name).readText()
            assertFalse("$name 仍有 HomeComputerSheet", text.contains("HomeComputerSheet"))
            assertFalse("$name 仍有 HomeWorkspaceOption", text.contains("HomeWorkspaceOption"))
        }
        val page = file("HomeWorkspacePage.kt").readText()
        assertTrue(page.contains("HomeWorkspaceSheet("))
        assertTrue(page.contains("HOME_FOLDER_PREVIEW_ROWS"))
    }

    /** v4 2.6：长按菜单删除排最后且是危险色。 */
    @Test
    fun `long press sheet ends with danger delete`() {
        val text = file("HomeHub.kt").readText()
        val body = text.substring(text.indexOf("internal fun HomeSessionSheetContent("))
        val rows = Regex("""DlListRow\(title = s\.(\w+)""").findAll(body).map { it.groupValues[1] }.toList()
        assertEquals(listOf("rename", "homeForkAsNew", "homeShareSession", "archiveSession", "deleteSession"), rows.take(5))
        assertTrue(body.contains("title = s.deleteSession, leading = TrashOutline16, danger = true"))
    }
}
