package dev.deeplinks.native

import dev.deeplinks.core.MarkdownMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 远程图片的安全边界（跳过私网 / 回环 / 明文地址）。
 *
 * 第 2 步 B4：原先在测试里重写「autoLoad || url in allowed」的三个空转用例已删除——
 * 那段判断现在由 RemoteImagePolicyTest 直接调用产品代码覆盖。
 */
class MarkdownImageLoadTest {

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
