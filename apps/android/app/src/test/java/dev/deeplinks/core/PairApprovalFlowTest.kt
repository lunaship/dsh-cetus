package dev.deeplinks.core

import dev.deeplinks.core.remote.HostRoute
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairApprovalFlowTest {
    private val session = PairingSession(
        requestId = "request", attemptId = "attempt",
        host = Host("computer", "https://fixture.invalid", "token", "device"),
        pairRoute = HostRoute.REMOTE, pendingExpiresAt = null, serverNow = null,
        receivedAtMs = 1_760_000_000_000L,
    )

    private class VirtualTime {
        var millis = 1_000L
        val waits = mutableListOf<Long>()
        suspend fun sleep(duration: Long) {
            waits += duration
            millis += duration
        }
    }

    @Test
    fun `inner pending 403 followed by approved 200 completes on same candidate`() = runBlocking {
        val time = VirtualTime()
        val answers = ArrayDeque(listOf(
            PairClient.approvalFromResponseSealed(403, """{"pending":true}"""),
            PairClient.approvalFromResponseSealed(200, """{"sessions":[]}"""),
        ))
        val result = awaitPairApproval(session, poll = {
            assertEquals(session, it)
            answers.removeFirst()
        }, isCurrent = { true }, budgetMs = 20_000L, now = { time.millis }, sleep = time::sleep)
        assertEquals(PairWaitResult.Approved, result)
        assertEquals(listOf(2_000L), time.waits)
    }

    @Test
    fun `three consecutive network failures pause instead of looping forever`() = runBlocking {
        val time = VirtualTime()
        var calls = 0
        val result = awaitPairApproval(session, poll = {
            calls++
            PairApproval.Retryable(RetryProblem.NETWORK_FAILURE)
        }, isCurrent = { true }, budgetMs = 60_000L, now = { time.millis }, sleep = time::sleep)
        assertEquals(3, calls)
        assertEquals(2, time.waits.size)
        val failure = (result as PairWaitResult.Paused).failure
        assertEquals(PairRecovery.RETRY, failure.recovery)
        assertEquals(PairFailureCode.NETWORK_FAILURE, failure.code)
    }

    @Test
    fun `outer relay rejection pauses but never becomes inner credential rejection`() = runBlocking {
        val time = VirtualTime()
        var calls = 0
        val result = awaitPairApproval(session, poll = {
            calls++
            PairApproval.RelayReportedRejection("UNKNOWN_KEY")
        }, isCurrent = { true }, budgetMs = 60_000L, now = { time.millis }, sleep = time::sleep)
        assertEquals(3, calls)
        assertTrue(result is PairWaitResult.Paused)
        assertEquals(PairRecovery.RETRY, (result as PairWaitResult.Paused).failure.recovery)
    }

    @Test
    fun `legitimate pending resets consecutive transient error count`() = runBlocking {
        val time = VirtualTime()
        val error = PairApproval.Retryable(RetryProblem.NETWORK_FAILURE)
        val answers = ArrayDeque(listOf(error, error, PairApproval.Pending, error, error, PairApproval.Approved))
        val result = awaitPairApproval(session, poll = { answers.removeFirst() }, isCurrent = { true },
            budgetMs = 60_000L, now = { time.millis }, sleep = time::sleep)
        assertEquals(PairWaitResult.Approved, result)
        assertEquals(5, time.waits.size)
    }

    @Test
    fun `pending check stops at finite budget and retains retry action`() = runBlocking {
        val time = VirtualTime()
        var calls = 0
        val result = awaitPairApproval(session, poll = { calls++; PairApproval.Pending }, isCurrent = { true },
            budgetMs = 4_500L, now = { time.millis }, sleep = time::sleep)
        assertEquals(3, calls)
        assertEquals(listOf(2_000L, 2_000L, 500L), time.waits)
        val failure = (result as PairWaitResult.Paused).failure
        assertEquals(PairFailureCode.WAIT_ENDED, failure.code)
        assertEquals(PairRecovery.RETRY, failure.recovery)
    }

    @Test
    fun `exhausted restored budget still checks once for approval`() = runBlocking {
        val time = VirtualTime()
        var calls = 0
        val result = awaitPairApproval(session, poll = { calls++; PairApproval.Approved }, isCurrent = { true },
            budgetMs = 0L, now = { time.millis }, sleep = time::sleep)
        assertEquals(PairWaitResult.Approved, result)
        assertEquals(1, calls)
        assertTrue(time.waits.isEmpty())
    }

    @Test
    fun `expired restored budget with no approval pauses after one check`() = runBlocking {
        val time = VirtualTime()
        var calls = 0
        val result = awaitPairApproval(session, poll = { calls++; PairApproval.Pending }, isCurrent = { true },
            budgetMs = 0L, now = { time.millis }, sleep = time::sleep)
        assertTrue(result is PairWaitResult.Paused)
        assertEquals(1, calls)
        assertTrue(time.waits.isEmpty())
    }

    @Test
    fun `old asynchronous approval is ignored after another attempt begins`() = runBlocking {
        var current = true
        val result = awaitPairApproval(session, poll = {
            current = false
            PairApproval.Approved
        }, isCurrent = { current }, budgetMs = 60_000L)
        assertNull(result)
        var calls = 0
        assertNull(awaitPairApproval(session, poll = { calls++; PairApproval.Approved }, isCurrent = { false }))
        assertEquals(0, calls)
    }

    @Test
    fun `cancelled coroutine does not deliver completed approval`() = runBlocking {
        var delivered: PairWaitResult? = null
        val worker = launch {
            delivered = awaitPairApproval(session, poll = {
                currentCoroutineContext().cancel()
                PairApproval.Approved
            }, isCurrent = { true }, budgetMs = 60_000L)
        }
        worker.join()
        assertTrue(worker.isCancelled)
        assertNull(delivered)
    }

    @Test
    fun `tls identity mismatch stops immediately without fallback polling`() = runBlocking {
        val time = VirtualTime()
        var calls = 0
        val result = awaitPairApproval(session, poll = { calls++; PairApproval.IdentityMismatch }, isCurrent = { true },
            budgetMs = 60_000L, now = { time.millis }, sleep = time::sleep)
        val failure = (result as PairWaitResult.Paused).failure
        assertEquals(PairFailureCode.CERT_MISMATCH, failure.code)
        assertEquals(PairRecovery.RESCAN, failure.recovery)
        assertEquals(1, calls)
        assertTrue(time.waits.isEmpty())
    }

    @Test
    fun `only inner authentication rejection returns rejected`() = runBlocking {
        val result = awaitPairApproval(session, poll = { PairApproval.RejectedByHost }, isCurrent = { true }, budgetMs = 0L)
        assertEquals(PairWaitResult.Rejected, result)
    }
}
