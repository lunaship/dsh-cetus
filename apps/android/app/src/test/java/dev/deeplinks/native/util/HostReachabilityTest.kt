package dev.deeplinks.native.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 在线判定计划（R4）：连续失败次数 → 在线状态与下次探测延迟。 */
class HostReachabilityTest {

    @Test
    fun `success is online and waits 30 seconds`() {
        val plan = probePlan(0, everOnline = true)
        assertFalse(plan.offline)
        assertEquals(30_000L, plan.nextDelayMs)
    }

    @Test
    fun `first failure while online does not declare offline and rechecks in 3s`() {
        val plan = probePlan(1, everOnline = true)
        assertFalse(plan.offline)
        assertEquals(3_000L, plan.nextDelayMs)
    }

    @Test
    fun `first failure without ever being online declares offline immediately`() {
        val plan = probePlan(1, everOnline = false)
        assertTrue(plan.offline)
        assertEquals(5_000L, plan.nextDelayMs)
    }

    @Test
    fun `second failure while online declares offline and rechecks in 5s`() {
        val plan = probePlan(2, everOnline = true)
        assertTrue(plan.offline)
        assertEquals(5_000L, plan.nextDelayMs)
    }

    @Test
    fun `three and four failures back off to 10 seconds`() {
        assertEquals(10_000L, probePlan(3, everOnline = true).nextDelayMs)
        assertEquals(10_000L, probePlan(4, everOnline = true).nextDelayMs)
        assertTrue(probePlan(3, everOnline = true).offline)
    }

    @Test
    fun `five or more failures back off to 30 seconds`() {
        assertEquals(30_000L, probePlan(5, everOnline = true).nextDelayMs)
        assertEquals(30_000L, probePlan(50, everOnline = true).nextDelayMs)
        assertTrue(probePlan(50, everOnline = false).offline)
    }
}
