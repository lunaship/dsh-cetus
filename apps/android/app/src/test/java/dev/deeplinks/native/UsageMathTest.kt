package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageMathTest {

    @Test
    fun `cache hit rate is zero when the denominator is zero`() {
        assertNull(cacheHitRate(0, 0))
        assertNull(cacheHitRate(-1, 0))
    }

    @Test
    fun `cache hit rate splits cached and uncached input`() {
        assertEquals(0.75, cacheHitRate(75, 25)!!, 0.0001)
        assertEquals(0.0, cacheHitRate(0, 10)!!, 0.0001)
        assertEquals(1.0, cacheHitRate(10, 0)!!, 0.0001)
    }

    @Test
    fun `huge token counts stay finite and do not overflow`() {
        val rate = cacheHitRate(Long.MAX_VALUE, Long.MAX_VALUE)
        assertEquals(0.5, rate!!, 0.0001)
        assertEquals(Long.MAX_VALUE, saturatingTokenSum(Long.MAX_VALUE, Long.MAX_VALUE, 1))
        val figures = usageFigures(
            MobileSessionStats(
                uncachedInputTokens = Long.MAX_VALUE,
                cacheReadTokens = Long.MAX_VALUE,
                outputTokens = Long.MAX_VALUE,
            ),
        )
        assertEquals(Long.MAX_VALUE, figures!!.totalTokens)
        assertTrue(figures.cacheHitRate!! in 0.0..1.0)
    }

    @Test
    fun `missing timing fields stay absent`() {
        assertNull(averageTtftMs(1200, 0))
        assertNull(averageTtftMs(-1, 2))
        assertEquals(400.0, averageTtftMs(1200, 3)!!, 0.001)
        assertNull(outputTokensPerSec(100, 0))
        assertNull(outputTokensPerSec(-1, 1000))
        assertEquals(50.0, outputTokensPerSec(100, 2000)!!, 0.001)
    }

    @Test
    fun `null stats means the host did not send usage`() {
        assertNull(usageFigures(null))
    }

    @Test
    fun `partial stats keep present numbers and drop empty breakdown`() {
        val figures = usageFigures(
            MobileSessionStats(
                turns = 2,
                uncachedInputTokens = 100,
                outputTokens = 40,
            ),
        )!!
        assertEquals(140, figures.totalTokens)
        assertEquals(0.0, figures.cacheHitRate!!, 0.0001)
        assertNull(figures.avgTtftMs)
        assertNull(figures.outputTokensPerSec)
        assertEquals(0, figures.contextWindowTokens)
        assertTrue(figures.breakdown.isEmpty())
    }

    @Test
    fun `breakdown keeps only positive slices`() {
        val figures = usageFigures(
            MobileSessionStats(
                systemTokens = 10,
                toolsTokens = 0,
                messageTokens = 30,
                contextWindow = 100,
                contextPressureTokens = 40,
            ),
        )!!
        assertEquals(listOf("system", "messages"), figures.breakdown.map { it.key })
    }
}
