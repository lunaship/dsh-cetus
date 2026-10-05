package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMonitorStartTest {

    @Test
    fun `every foreground early exit satisfies then stops`() {
        val actions = listOf(null, MONITOR_ACTION_START, MONITOR_ACTION_ANSWER, MONITOR_ACTION_STOP, "other")
        var foregroundExits = 0
        for (action in actions) {
            for (hasHost in BOOLS) {
                for (slotMatches in BOOLS) {
                    for (hasSession in BOOLS) {
                        for (takeoverOn in BOOLS) {
                            for (isRestore in BOOLS) {
                                val decision = monitorStartDecision(
                                    action, hasHost, slotMatches, hasSession, takeoverOn, isRestore,
                                )
                                val foreground = action == MONITOR_ACTION_START || action == MONITOR_ACTION_ANSWER
                                val early = !hasHost || !slotMatches || !hasSession
                                if (action == MONITOR_ACTION_STOP || (isRestore && !takeoverOn)) {
                                    assertEquals(MonitorStartDecision.StopPlain, decision)
                                } else if (foreground && early) {
                                    foregroundExits++
                                    assertEquals(MonitorStartDecision.SatisfyThenStop, decision)
                                } else if (!early) {
                                    assertEquals(MonitorStartDecision.Continue, decision)
                                } else {
                                    assertEquals(MonitorStartDecision.StopPlain, decision)
                                    assertNotEquals(MonitorStartDecision.SatisfyThenStop, decision)
                                }
                                if (decision == MonitorStartDecision.SatisfyThenStop) {
                                    assertTrue(foreground && early)
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(foregroundExits > 0)
    }

    @Test
    fun `named exits match the service crash paths`() {
        assertEquals(
            MonitorStartDecision.SatisfyThenStop,
            monitorStartDecision(MONITOR_ACTION_START, hasHost = false, slotMatches = true, hasSession = true, takeoverOn = true, isRestore = false),
        )
        assertEquals(
            MonitorStartDecision.SatisfyThenStop,
            monitorStartDecision(MONITOR_ACTION_START, hasHost = true, slotMatches = true, hasSession = false, takeoverOn = true, isRestore = false),
        )
        assertEquals(
            MonitorStartDecision.SatisfyThenStop,
            monitorStartDecision(MONITOR_ACTION_ANSWER, hasHost = true, slotMatches = false, hasSession = true, takeoverOn = false, isRestore = false),
        )
        assertEquals(
            MonitorStartDecision.StopPlain,
            monitorStartDecision(MONITOR_ACTION_STOP, hasHost = false, slotMatches = false, hasSession = false, takeoverOn = false, isRestore = false),
        )
        assertEquals(
            MonitorStartDecision.StopPlain,
            monitorStartDecision(null, hasHost = false, slotMatches = true, hasSession = false, takeoverOn = true, isRestore = true),
        )
        assertEquals(
            MonitorStartDecision.Continue,
            monitorStartDecision(MONITOR_ACTION_START, hasHost = true, slotMatches = true, hasSession = true, takeoverOn = false, isRestore = false),
        )
    }

    private companion object {
        val BOOLS = listOf(false, true)
    }
}
