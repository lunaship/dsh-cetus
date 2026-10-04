package dev.deeplinks.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

sealed interface PairApproval {
    data object Approved : PairApproval
    data object Pending : PairApproval
    data object RejectedByHost : PairApproval
    data class Retryable(val problem: RetryProblem) : PairApproval
    data class RelayReportedRejection(val code: String) : PairApproval
    data object IdentityMismatch : PairApproval
}

enum class RetryProblem { NETWORK_FAILURE, HOST_OFFLINE, BUSY, UNKNOWN }

internal sealed interface PairWaitResult {
    data object Approved : PairWaitResult
    data object Rejected : PairWaitResult
    data class Paused(val failure: PairFailure) : PairWaitResult
}

/** 单任务串行检查；恢复/重试先确认一次内层状态，即使本机估算预算已结束。 */
internal suspend fun awaitPairApproval(
    session: PairingSession,
    poll: suspend (PairingSession) -> PairApproval,
    isCurrent: () -> Boolean,
    budgetMs: Long = session.remainingMs(),
    now: () -> Long = { System.nanoTime() / 1_000_000 },
    sleep: suspend (Long) -> Unit = { delay(it) },
): PairWaitResult? {
    val deadline = now() + budgetMs.coerceAtLeast(0L)
    var failures = 0
    while (isCurrent()) {
        val result = poll(session)
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return null
        when (result) {
            PairApproval.Approved -> return PairWaitResult.Approved
            PairApproval.RejectedByHost -> return PairWaitResult.Rejected
            PairApproval.IdentityMismatch -> return PairWaitResult.Paused(
                PairFailure(PairFailureCode.CERT_MISMATCH, PairRecovery.RESCAN, L.remoteCredentialInvalid),
            )
            else -> Unit
        }
        if (now() >= deadline) return PairWaitResult.Paused(PairFailure.waitingEnded())
        val failure = when (result) {
            is PairApproval.RelayReportedRejection -> PairFailure.fromRelayCode(result.code, pending = true)
            is PairApproval.Retryable -> PairFailure(
                if (result.problem == RetryProblem.BUSY) PairFailureCode.RATE_LIMITED else PairFailureCode.NETWORK_FAILURE,
                PairRecovery.RETRY,
                if (result.problem == RetryProblem.BUSY) L.pairTooManyAttempts else L.pairStatusUnconfirmed,
            )
            else -> null
        }
        failures = if (failure == null) 0 else failures + 1
        if (failure != null && failures >= PairingSession.DEFAULT_RETRY_THRESHOLD) return PairWaitResult.Paused(failure)
        sleep(minOf(PairingSession.DEFAULT_POLL_MS, (deadline - now()).coerceAtLeast(0L)))
        if (now() >= deadline) return PairWaitResult.Paused(PairFailure.waitingEnded())
    }
    return null
}
