package dev.deeplinks.core

import dev.deeplinks.core.remote.RouteOfflineException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class PairFailureRouteOfflineRegressionTest {
    @Test fun `remote offline is never reported as all LAN addresses failed`() {
        val failure = PairFailure.fromException(RouteOfflineException())
        assertEquals(PairFailureCode.ROUTE_OFFLINE, failure.code)
        assertEquals(L.remoteHostOffline, failure.message)
    }

    @Test fun `wrapped remote offline preserves the failed route`() {
        val failure = PairFailure.fromException(IOException("fixture detail must stay private", RouteOfflineException()))
        assertEquals(PairFailureCode.ROUTE_OFFLINE, failure.code)
        assertEquals(L.remoteHostOffline, failure.message)
    }

    @Test fun `unclassified IO retains the existing network fallback`() {
        assertEquals(PairFailureCode.NETWORK_FAILURE, PairFailure.fromException(IOException("fixture")).code)
    }
}
