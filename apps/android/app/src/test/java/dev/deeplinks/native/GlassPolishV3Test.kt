package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v3 玻璃打磨的回归门禁：
 * - 无模糊回退必须按 shape 绘制（此前 drawRect 把胶囊 / 圆钮画成方块）；
 * - 边缘渐隐有渐进模糊路径且不进采样源；
 * - 入场揭示的错峰有上限、不做无限循环；
 * - 座位行窄屏时模型座先让位。
 */
class GlassPolishV3Test {

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
    fun `glass fallback draws the shape outline instead of a rect`() {
        val glass = source("ui/DshGlass.kt")
        val fallback = body(glass, "private fun Modifier.dshGlassFallback(")
        assertTrue(fallback.contains("shape.createOutline("))
        assertTrue(fallback.contains("drawOutline("))
        assertFalse("回退不得用 drawRect（会忽略 shape）", fallback.contains("drawRect("))
        val entry = body(glass, "fun Modifier.dshGlass(")
        assertTrue(entry.contains("dshGlassFallback("))
        assertFalse("dshGlass 不得再在 drawBehind 里画方形回退", entry.contains("drawBehind"))
    }

    @Test
    fun `edge fade has a progressive blur path`() {
        val fade = source("ui/DshEdgeFade.kt")
        assertTrue(fade.contains("drawPlainBackdrop("))
        assertTrue(fade.contains("BlendMode.DstIn"))
        assertTrue(fade.contains("Build.VERSION_CODES.S"))
        // 调用方都把内容层采样源传进来（聊天页 2026-10-02 方案 A 改为实底 chrome，不再用边缘渐隐）
        for (caller in listOf("WorkspaceSidebar.kt")) {
            val text = source(caller)
            val call = text.substring(text.indexOf("DshEdgeFades(").also { assertTrue("$caller 未用 DshEdgeFades", it >= 0) })
            assertTrue("$caller 未接入采样源", call.substringBefore('\n').contains("chrome.backdrop)"))
        }
        assertTrue(source("ui/DshPageScaffold.kt").contains("backdrop = backdrop,"))
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
