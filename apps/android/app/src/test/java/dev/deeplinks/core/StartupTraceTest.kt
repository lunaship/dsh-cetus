package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** S7：启动打点行的格式（纯函数）。 */
class StartupTraceTest {

    @Test
    fun `无明细只有名称与毫秒`() {
        assertEquals("home_first_frame +312ms", formatStartupMark("home_first_frame", 312))
    }

    @Test
    fun `有明细时追加在括号里`() {
        assertEquals("first_route +150ms (LAN)", formatStartupMark("first_route", 150, "LAN"))
    }

    @Test
    fun `空白明细忽略`() {
        assertEquals("cache_list +40ms", formatStartupMark("cache_list", 40, "  "))
    }
}
