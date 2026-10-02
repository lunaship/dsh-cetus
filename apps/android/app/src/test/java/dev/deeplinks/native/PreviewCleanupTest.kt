package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** P5.1 复审：退出预览只清本次预览源，不全局清 WebView 数据。 */
class PreviewCleanupTest {

    @Test
    fun `cookie names are parsed from header`() {
        assertEquals(listOf("a", "b"), previewCookieNames("a=1; b=2; a=3"))
        assertEquals(emptyList<String>(), previewCookieNames(null))
    }

    @Test
    fun `origins cover both loopback names`() {
        assertEquals(listOf("http://127.0.0.1:5173", "http://localhost:5173"), previewOrigins(5173))
    }

    @Test
    fun `preview cleanup is scoped`() {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        val text = File(requireNotNull(dir), "src/main/java/dev/deeplinks/native/PreviewScreen.kt").readText()
        assertFalse(text.contains("removeAllCookies"))
        assertFalse(text.contains("deleteAllData"))
        assertTrue(text.contains("deleteOrigin("))
    }
}
