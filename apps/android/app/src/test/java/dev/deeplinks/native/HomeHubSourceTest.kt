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

    /** v4 2.1：首页不再用玻璃胶囊 / 悬浮条，顶栏是 DlTopBar 大标题。 */
    @Test
    fun `home files use v4 components instead of glass`() {
        for (name in listOf("HomeHub.kt", "WorkspaceSidebar.kt", "WorkspaceSidebarItems.kt")) {
            val text = file(name).readText()
            for (banned in listOf("DshGlass", "DshFloatingControls", "DshEdgeFade", "DshTranslucentBar", "DshCardRows")) {
                assertFalse("$name 仍引用 $banned", text.contains(banned))
            }
        }
        val header = file("HomeHub.kt").readText().let { it.substring(it.indexOf("internal fun HomeHeader(")) }
        assertTrue(header.contains("DlTopBar("))
        assertTrue(header.contains("large = true"))
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
