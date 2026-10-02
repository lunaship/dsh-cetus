package dev.deeplinks.native

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 方案 A（2026-10-02，小米 15 / Android 16 真机反馈）回归门禁：
 * - 顶部 chrome 回填的高度必须包含状态栏（onSizeChanged 在 statusBarsPadding 之前），
 *   否则内容区少让出一条状态栏，轨迹工具条 / 首条消息被压在标题下面；
 * - 顶 / 底 chrome 是实底，不再走半透明玻璃条；
 * - 聊天页不再叠白色边缘渐隐（画布是 bgBase，渐隐用 bgCard 会出现白雾带）。
 */
class OverlayChromeMeasureTest {

    private fun mainRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        return File(requireNotNull(dir), "src/main/java")
    }

    private fun source(path: String): String = mainRoot().resolve("dev/deeplinks/native/$path").readText()

    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue("找不到 $signature", start >= 0)
        val end = text.indexOf("\n}\n", start)
        return text.substring(start, if (end < 0) text.length else end)
    }

    @Test
    fun `top chrome measures height including status bar`() {
        val top = body(source("OverlayChrome.kt"), "internal fun Modifier.overlayTopChrome(")
        val measure = top.indexOf("onSizeChanged")
        val inset = top.indexOf("statusBarsPadding()")
        assertTrue(measure >= 0 && inset >= 0)
        assertTrue("onSizeChanged 必须在 statusBarsPadding() 之前", measure < inset)
    }

    @Test
    fun `top and bottom chrome are solid`() {
        val chrome = source("OverlayChrome.kt")
        val top = body(chrome, "internal fun Modifier.overlayTopChrome(")
        assertTrue(top.contains("drawRect(base)"))
        assertFalse(top.contains("dshTranslucent"))
        val bottom = body(chrome, "internal fun Modifier.overlayBottomChrome(")
        assertTrue(bottom.contains("drawRect(base)"))
        assertTrue(bottom.contains("BOTTOM_CHROME_FADE"))
    }

    @Test
    fun `chat page has no edge fades`() {
        assertFalse(source("WorkspaceActivity.kt").contains("DshEdgeFades("))
    }
}
