package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostMonitorTest {
    @Test
    fun `an active computer starts a connection`() {
        val snapshot = stepHostMonitor(
            nowMs = 1_000,
            links = listOf(HostLink("a", active = true)),
            idleSince = emptyMap(),
        )
        assertEquals(setOf("a"), snapshot.connect)
        assertTrue(snapshot.idleSince.isEmpty())
    }

    @Test
    fun `all sessions idle keeps the connection for five minutes`() {
        val justIdle = stepHostMonitor(
            nowMs = 10_000,
            links = listOf(HostLink("a", active = false)),
            idleSince = emptyMap(),
        )
        assertEquals(setOf("a"), justIdle.connect)
        assertEquals(10_000L, justIdle.idleSince["a"])
        val still = stepHostMonitor(
            nowMs = 10_000 + HOST_MONITOR_IDLE_MS - 1,
            links = listOf(HostLink("a", active = false)),
            idleSince = justIdle.idleSince,
        )
        assertEquals(setOf("a"), still.connect)
        val stopped = stepHostMonitor(
            nowMs = 10_000 + HOST_MONITOR_IDLE_MS,
            links = listOf(HostLink("a", active = false)),
            idleSince = justIdle.idleSince,
        )
        assertTrue(stopped.connect.isEmpty())
    }

    @Test
    fun `two computers are watched independently`() {
        val snapshot = stepHostMonitor(
            nowMs = 50_000,
            links = listOf(
                HostLink("desk", active = true),
                HostLink("laptop", active = false),
            ),
            idleSince = mapOf("laptop" to 50_000 - HOST_MONITOR_IDLE_MS),
        )
        assertEquals(setOf("desk"), snapshot.connect)
        assertEquals(setOf("laptop"), snapshot.idleSince.keys)
    }

    @Test
    fun `a real network switch reconnects and a property change does not`() {
        assertTrue(hostMonitorShouldReconnect(NetworkChangeAction.ResetPool))
        assertFalse(hostMonitorShouldReconnect(NetworkChangeAction.None))
        assertFalse(hostMonitorShouldReconnect(NetworkChangeAction.InvalidateRoutes))
    }
}
