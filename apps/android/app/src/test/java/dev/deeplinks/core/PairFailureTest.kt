package dev.deeplinks.core

import dev.deeplinks.core.remote.RouteBusyException
import dev.deeplinks.core.remote.RouteOfflineException
import dev.deeplinks.core.remote.RouteOpenTimeoutException
import dev.deeplinks.core.remote.RouteProtocolException
import dev.deeplinks.core.remote.RouteRejectedException
import dev.deeplinks.core.remote.RouteUnreachableException
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PairFailureTest {
    @Test fun `wrapped remote failures retain their route meaning`() {
        val cases = listOf(
            RouteUnreachableException(UnknownHostException("private-host")) to PairFailureCode.RELAY_UNREACHABLE,
            RouteOfflineException() to PairFailureCode.ROUTE_OFFLINE,
            RouteBusyException("DEVICE_LIMIT") to PairFailureCode.RATE_LIMITED,
            RouteRejectedException("BOOTSTRAP_USED") to PairFailureCode.BOOTSTRAP_UNAVAILABLE,
        )
        for ((error, code) in cases) {
            val failure = PairFailure.fromException(IOException("private-token", error))
            assertEquals(code, failure.code)
            assertFalse(failure.message.contains("private-"))
            assertFalse(failure.message == L.allAddressesFailed)
        }
    }

    @Test fun `different remote connection stages have different safe messages`() {
        val failures = listOf(
            RouteUnreachableException(UnknownHostException("private-host")),
            RouteUnreachableException(SSLHandshakeException("private-cert")),
            RouteUnreachableException(java.net.ProtocolException("private-http")),
            RouteOpenTimeoutException(),
            RouteProtocolException(),
        ).map(PairFailure::fromException)
        assertEquals(failures.size, failures.map { it.message }.distinct().size)
        failures.forEach { assertFalse(it.message == L.allAddressesFailed) }
    }

    @Test fun `certificate mismatch inside relay error always wins`() {
        val error = RouteUnreachableException(IOException("private-pin", PinnedSsl.CertChangedException()))
        assertEquals(PairFailureCode.CERT_MISMATCH, PairFailure.fromException(error).code)
    }
}
