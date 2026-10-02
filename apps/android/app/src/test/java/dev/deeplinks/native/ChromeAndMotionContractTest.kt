package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 从玻璃回归里留下的非玻璃断言：顶栏回填高度、实底 chrome、入场揭示、座位行让位。
 */
class ChromeAndMotionContractTest {

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

    @Test
    fun `reveal stagger is capped`() {
        assertEquals(0, dshRevealDelayMs(0))
        assertEquals(DshDuration.revealStagger, dshRevealDelayMs(1))
        val cap = DshDuration.revealStaggerMaxIndex * DshDuration.revealStagger
        assertEquals(cap, dshRevealDelayMs(DshDuration.revealStaggerMaxIndex))
        assertEquals(cap, dshRevealDelayMs(100))
        assertEquals(0, dshRevealDelayMs(-3))
    }

    @Test
    fun `reveal is one shot and skips previews`() {
        val reveal = body(source("DshMotion.kt"), "fun Modifier.dshReveal(")
        assertTrue(reveal.contains("LocalInspectionMode"))
        assertTrue(reveal.contains("isReduceMotionEnabled()"))
        assertFalse(reveal.contains("infiniteRepeatable"))
        assertFalse(reveal.contains("rememberInfiniteTransition"))
    }

    @Test
    fun `model seat yields width before access seat`() {
        val seats = body(source("ComposerBar.kt"), "internal fun ComposerSeatsRow(")
        val model = seats.indexOf("ComposerModelSeat(")
        val access = seats.indexOf("ComposerAccessSeat(")
        assertTrue(model in 0 until access)
        assertTrue(
            "模型座需要 weight(fill = false) 才能先收缩",
            seats.substring(model, access).contains("Modifier.weight(1f, fill = false)"),
        )
    }
}
