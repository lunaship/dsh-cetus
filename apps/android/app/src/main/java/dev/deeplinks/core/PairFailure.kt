package dev.deeplinks.core

import dev.deeplinks.core.remote.RouteRejectedException
import dev.deeplinks.core.remote.RouteBusyException
import dev.deeplinks.core.remote.RouteOfflineException
import dev.deeplinks.core.remote.RouteOpenTimeoutException
import dev.deeplinks.core.remote.RouteProtocolException
import dev.deeplinks.core.remote.RouteUnreachableException

enum class PairFailureCode {
    QR_NO_REMOTE, QR_REMOTE_INVALID, PAIR_CODE_INVALID, BOOTSTRAP_UNAVAILABLE,
    RELAY_UNREACHABLE, RELAY_REJECTED, ROUTE_OFFLINE, RATE_LIMITED, CERT_MISMATCH,
    SAME_NAME, PENDING_REJECTED, NETWORK_FAILURE, WAIT_ENDED, PENDING_UNREADABLE,
    SAVE_FAILED, ROUTE_TIMEOUT, REMOTE_PROTOCOL, REMOTE_INTERRUPTED, UNKNOWN,
}

enum class PairRecovery { RESCAN, RETRY, BACK }

/** 原始异常、地址及凭据不进入 Intent 或用户文案。 */
data class PairFailure(
    val code: PairFailureCode,
    val recovery: PairRecovery,
    val message: String,
    val suggestion: String? = null,
) {
    companion object {
        fun network(): PairFailure = PairFailure(PairFailureCode.NETWORK_FAILURE, PairRecovery.RESCAN, L.allAddressesFailed)
        fun badQr(message: String): PairFailure = PairFailure(PairFailureCode.QR_REMOTE_INVALID, PairRecovery.RESCAN, message)
        fun waitingEnded(): PairFailure = PairFailure(PairFailureCode.WAIT_ENDED, PairRecovery.RETRY, L.pairWaitEnded)
        fun restored(session: PairingSession): PairFailure = when (session.pauseReason) {
            PairFailureCode.WAIT_ENDED -> waitingEnded()
            PairFailureCode.CERT_MISMATCH -> PairFailure(PairFailureCode.CERT_MISMATCH, PairRecovery.RESCAN, L.remoteCredentialInvalid)
            PairFailureCode.SAVE_FAILED -> saveFailed()
            PairFailureCode.RATE_LIMITED -> PairFailure(PairFailureCode.RATE_LIMITED, PairRecovery.RETRY, L.pairTooManyAttempts)
            PairFailureCode.ROUTE_OFFLINE -> PairFailure(PairFailureCode.ROUTE_OFFLINE, PairRecovery.RETRY, L.remoteLocalUnavailable)
            else -> PairFailure(session.pauseReason ?: PairFailureCode.UNKNOWN, PairRecovery.RETRY, L.pairStatusUnconfirmed)
        }
        fun unreadable(): PairFailure = PairFailure(PairFailureCode.PENDING_UNREADABLE, PairRecovery.RESCAN, L.pairPendingUnreadable)
        fun saveFailed(): PairFailure = PairFailure(PairFailureCode.SAVE_FAILED, PairRecovery.RETRY, L.credentialsSaveFailedToast)
        fun remoteInterrupted(): PairFailure = PairFailure(PairFailureCode.REMOTE_INTERRUPTED, PairRecovery.RESCAN, L.pairRemoteInterrupted)

        fun fromRelayCode(code: String, pending: Boolean): PairFailure {
            val recovery = if (pending) PairRecovery.RETRY else PairRecovery.RESCAN
            return when (code) {
                "BOOTSTRAP_EXPIRED", "BOOTSTRAP_USED", "BOOTSTRAP_UNKNOWN" ->
                    PairFailure(PairFailureCode.BOOTSTRAP_UNAVAILABLE, recovery, L.remoteQrExpired)
                "ROUTE_OFFLINE", "LOCAL_UNAVAILABLE" ->
                    PairFailure(PairFailureCode.ROUTE_OFFLINE, recovery, L.remoteLocalUnavailable)
                "SERVER_BUSY", "DEVICE_LIMIT", "RATE_LIMITED" ->
                    PairFailure(PairFailureCode.RATE_LIMITED, recovery, L.pairTooManyAttempts)
                else -> PairFailure(PairFailureCode.RELAY_REJECTED, recovery, L.pairStatusUnconfirmed)
            }
        }

        fun fromException(error: Throwable): PairFailure {
            if (PinnedSsl.unwrap(error) is PinnedSsl.CertChangedException) {
                return PairFailure(PairFailureCode.CERT_MISMATCH, PairRecovery.RESCAN, L.remoteCredentialInvalid)
            }
            // OkHttp 会包裹隧道异常；先认会合阶段，再认底层网络异常，不能丢掉所在链路。
            val causes = generateSequence(error) { it.cause }.take(16).toList()
            return when (val routeError = causes.firstOrNull { it is dev.deeplinks.core.remote.RouteConnectException }) {
                is RouteUnreachableException -> {
                    val message = when {
                        causes.any { it is java.net.UnknownHostException } -> L.pairRelayDnsFailed
                        causes.any { it is javax.net.ssl.SSLException } -> L.pairRelayTlsFailed
                        causes.any { it is java.net.ProtocolException } -> L.pairRelayUpgradeFailed
                        else -> L.remoteRelayUnreachable
                    }
                    PairFailure(PairFailureCode.RELAY_UNREACHABLE, PairRecovery.RESCAN, message)
                }
                is RouteOfflineException -> PairFailure(PairFailureCode.ROUTE_OFFLINE, PairRecovery.RESCAN, L.remoteHostOffline)
                is RouteOpenTimeoutException -> PairFailure(PairFailureCode.ROUTE_TIMEOUT, PairRecovery.RESCAN, L.pairRemoteTimeout)
                is RouteBusyException -> PairFailure(PairFailureCode.RATE_LIMITED, PairRecovery.RESCAN, L.remoteBusy)
                is RouteProtocolException -> PairFailure(PairFailureCode.REMOTE_PROTOCOL, PairRecovery.RESCAN, L.pairRemoteProtocolFailed)
                is RouteRejectedException -> fromRelayCode(routeError.code, pending = false)
                else -> causes.filterIsInstance<PairRequestException>().firstOrNull()?.failure ?: when (error) {
                    is java.net.SocketTimeoutException, is java.net.UnknownHostException ->
                        PairFailure(PairFailureCode.NETWORK_FAILURE, PairRecovery.RESCAN, L.pairConnectionFailed)
                    is java.io.IOException -> network()
                    else -> PairFailure(PairFailureCode.UNKNOWN, PairRecovery.RESCAN, L.pairStatusUnconfirmed)
                }
            }
        }
    }
}

class PairRequestException(val failure: PairFailure) : RuntimeException(failure.message)
