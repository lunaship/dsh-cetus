package dev.deeplinks.native

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话级远程图片加载策略（第 2 步 B2 / B4）。
 *
 * 调用产品代码（[RemoteImagePolicy] / [shouldLoadRemoteImage]），不在测试里重写判断。
 */
class RemoteImagePolicyTest {

    private val a = "https://example.com/a.png"
    private val b = "https://cdn.example.org/b.png"

    @Test
    fun `off and not allowed - does not load`() {
        val policy = RemoteImagePolicy(autoLoad = false)
        assertFalse(policy.shouldLoad(a))
    }

    @Test
    fun `off but allowed - loads`() {
        val policy = RemoteImagePolicy(autoLoad = false)
        policy.allow(a)
        assertTrue(policy.shouldLoad(a))
    }

    @Test
    fun `on - loads without allow`() {
        val policy = RemoteImagePolicy(autoLoad = true)
        assertTrue(policy.shouldLoad(a))
    }

    @Test
    fun `allow is per url`() {
        val policy = RemoteImagePolicy(autoLoad = false)
        policy.allow(a)
        assertTrue(policy.shouldLoad(a))
        assertFalse(policy.shouldLoad(b))
    }

    @Test
    fun `autoLoad flips without losing allowed set`() {
        val policy = RemoteImagePolicy(autoLoad = false)
        policy.allow(a)
        policy.autoLoad = true
        assertTrue(policy.shouldLoad(a))
        assertTrue(policy.shouldLoad(b))
    }

    @Test
    fun `pure function matches policy`() {
        assertFalse(shouldLoadRemoteImage(autoLoad = false, allowed = emptySet(), url = a))
        assertTrue(shouldLoadRemoteImage(autoLoad = false, allowed = setOf(a), url = a))
        assertTrue(shouldLoadRemoteImage(autoLoad = true, allowed = emptySet(), url = a))
    }
}
