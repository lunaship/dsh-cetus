package dev.deeplinks.native.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** 电脑显示名优先级（方案 3/7：电脑名用用户起的名字）。 */
class HostDisplayLabelTest {

    @Test
    fun aliasWins() {
        assertEquals("我的 Mac", hostDisplayLabel("我的 Mac", "MacBook Pro", "192.168.1.8:18640"))
    }

    @Test
    fun fallsBackToHostNameThenAddress() {
        assertEquals("MacBook Pro", hostDisplayLabel("", "MacBook Pro", "192.168.1.8:18640"))
        assertEquals("192.168.1.8:18640", hostDisplayLabel(null, null, "192.168.1.8:18640"))
    }

    /** 全空时给空串而不是 "null"：顶栏宁可空白，也不要写个 null 出来。 */
    @Test
    fun allBlankGivesEmpty() {
        assertEquals("", hostDisplayLabel("  ", "", null))
        assertEquals("", hostDisplayLabel(null, null, null))
    }
}
