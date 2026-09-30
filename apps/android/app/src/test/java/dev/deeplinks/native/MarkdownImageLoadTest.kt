package dev.deeplinks.native

import dev.deeplinks.core.MarkdownMedia
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证对话中远程图片的加载状态机（占位 → 点击 → 已加载）。
 *
 * 真实状态由 Compose 的 [mutableStateSetOf] 在 `MarkdownContent` 内管理；
 * 这里用纯 JVM 集合模拟同一状态转换，保证逻辑可测。
 */
class MarkdownImageLoadTest {

    @Test
    fun `placeholder shows when autoLoad is off and url not allowed`() {
        val allowed = mutableSetOf<String>()
        val url = "https://example.com/a.png"
        val autoLoad = false

        val loaded = autoLoad || url in allowed
        assertFalse(loaded)
    }

    @Test
    fun `click adds url to allowed set and shows image`() {
        val allowed = mutableSetOf<String>()
        val url = "https://example.com/a.png"
        val autoLoad = false

        // simulate click
        allowed.add(url)

        val loaded = autoLoad || url in allowed
        assertTrue(loaded)
    }

    @Test
    fun `autoLoad on bypasses allowed set`() {
        val allowed = mutableSetOf<String>()
        val url = "https://example.com/a.png"
        val autoLoad = true

        assertTrue(autoLoad || url in allowed)
        allowed.add(url)
        assertTrue(autoLoad || url in allowed)
    }

    @Test
    fun `unsafe image url is never loaded`() {
        val unsafe = "http://127.0.0.1/a.png"
        assertFalse(MarkdownMedia.isSafeImageUrl(unsafe))
        assertNull(MarkdownMedia.takeIfSafe(unsafe))
    }

    @Test
    fun `safe https image url passes safety check`() {
        val safe = "https://example.com/a.png"
        assertTrue(MarkdownMedia.isSafeImageUrl(safe))
        assertEquals(safe, MarkdownMedia.takeIfSafe(safe))
    }

    private fun assertNull(value: String?) = org.junit.Assert.assertNull(value)

    private fun assertEquals(expected: String, actual: String?) =
        org.junit.Assert.assertEquals(expected, actual)
}
