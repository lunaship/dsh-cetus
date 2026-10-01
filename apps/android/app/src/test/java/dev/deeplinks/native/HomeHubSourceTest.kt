package dev.deeplinks.native

import org.junit.Assert.assertFalse
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

    @Test
    fun `HomeHeader has no HostBadge`() {
        val text = file("HomeHub.kt").readText()
        val start = text.indexOf("internal fun HomeHeader(")
        val end = text.indexOf("\n}\n", start) + 3
        val body = text.substring(start, end)
        assertFalse("HomeHeader should not contain HostBadge", body.contains("HostBadge"))
    }

    @Test
    fun `HomeHeader has no ChevronDownOutline16`() {
        val text = file("HomeHub.kt").readText()
        val start = text.indexOf("internal fun HomeHeader(")
        val end = text.indexOf("\n}\n", start) + 3
        val body = text.substring(start, end)
        assertFalse("HomeHeader should not contain ChevronDownOutline16", body.contains("ChevronDownOutline16"))
    }

    @Test
    fun `HomeHeader has no hostName parameter`() {
        val text = file("HomeHub.kt").readText()
        val start = text.indexOf("internal fun HomeHeader(")
        val end = text.indexOf("\n}\n", start) + 3
        val body = text.substring(start, end)
        assertFalse("HomeHeader should not have hostName parameter", body.contains("hostName"))
    }

    /** 2026-10-02 Lody 简化 4.1：黑色「+ 新任务」FAB 由底部玻璃操作行（HomeBottomBar）取代。 */
    @Test
    fun `HomeNewTaskFab is gone`() {
        assertFalse(
            "HomeNewTaskFab should no longer exist",
            file("HomeHub.kt").readText().contains("HomeNewTaskFab") ||
                file("WorkspaceSidebar.kt").readText().contains("HomeNewTaskFab"),
        )
    }
}
