package dev.deeplinks.core

import dev.deeplinks.core.remote.HostRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingSessionTest {
    private val received = 1_760_000_000_000L
    private val session = PairingSession(
        requestId = "request", attemptId = "attempt",
        host = Host("computer", "https://fixture.invalid", "token", "device"),
        pairRoute = HostRoute.REMOTE,
        pendingExpiresAt = 30_000L, serverNow = 10_000L, receivedAtMs = received,
    )

    @Test
    fun `server duration ignores absolute phone and host clock skew`() {
        assertEquals(20_000L, session.remainingMs(received))
        assertEquals(15_000L, session.remainingMs(received + 5_000L))
        assertEquals(0L, session.remainingMs(received + 20_001L))
    }

    @Test
    fun `old plugin missing server time uses a finite local budget`() {
        val old = session.copy(serverNow = null)
        assertEquals(PairingSession.DEFAULT_WAIT_BUDGET_MS, old.remainingMs(received))
        assertEquals(0L, old.remainingMs(received + PairingSession.DEFAULT_WAIT_BUDGET_MS))
        assertEquals(0L, session.copy(pendingExpiresAt = null).remainingMs(received + PairingSession.DEFAULT_WAIT_BUDGET_MS))
    }

    @Test
    fun `restored session deducts wall time instead of reusing a boot clock`() {
        // A copied/persisted record keeps a wall timestamp, never an old process nanoTime.
        val restored = session.copy()
        assertEquals(8_000L, restored.remainingMs(received + 12_000L))
        assertEquals(0L, restored.remainingMs(received + 120_000L))
    }

    @Test
    fun `backwards phone clock and excessive server duration stay bounded`() {
        assertEquals(20_000L, session.remainingMs(received - 60_000L))
        assertEquals(PairingSession.DEFAULT_WAIT_BUDGET_MS, session.copy(pendingExpiresAt = Long.MAX_VALUE).remainingMs(received))
        assertEquals(0L, session.copy(pendingExpiresAt = 9_999L).remainingMs(received))
    }
}
