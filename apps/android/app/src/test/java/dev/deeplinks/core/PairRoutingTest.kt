package dev.deeplinks.core

import dev.deeplinks.core.remote.HostRoute
import dev.deeplinks.core.remote.RemoteRoute
import dev.deeplinks.devices.PairingQr
import dev.deeplinks.devices.QrRemoteCapability
import java.net.ConnectException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairRoutingTest {
    private val remote = RemoteRoute("wss://fixture.invalid/ws", ByteArray(16), ByteArray(16), ByteArray(32), "bootstrap")
    private fun qr() = PairingQr("code", listOf("https://10.0.0.1:18640"), "fixture", "ab".repeat(32), remote)

    @Test fun `cellular with valid remote skips LAN and returns actual route`() {
        var probes = 0
        val routes = mutableListOf<HostRoute>()
        val result = PairClient.pairWithQr(qr(), "phone", "request", lanCapable = false,
            probe = { _, _ -> probes++; true },
            send = { base, _, _, _, _, route, _ -> routes += route; PairClient.Result(base, "fixture", "token", pairRoute = route) },
        )
        assertEquals(0, probes)
        assertEquals(listOf(HostRoute.REMOTE), routes)
        assertEquals(HostRoute.REMOTE, result.pairRoute)
    }

    @Test fun `LAN establishment failure uses same request on remote`() {
        val requests = mutableListOf<Pair<HostRoute, String>>()
        PairClient.pairWithQr(qr(), "phone", "same-request", lanCapable = true,
            probe = { _, _ -> true },
            send = { base, _, _, _, request, route, _ ->
                requests += route to request
                if (route == HostRoute.LAN) throw ConnectException("fixture")
                PairClient.Result(base, "fixture", "token", pairRoute = route)
            },
        )
        assertEquals(listOf(HostRoute.LAN to "same-request", HostRoute.REMOTE to "same-request"), requests)
    }

    @Test fun `LAN success is LAN even if device supports remote`() {
        val result = PairClient.pairWithQr(qr(), "phone", "request", lanCapable = null,
            probe = { _, _ -> true },
            send = { base, _, _, _, _, route, _ -> PairClient.Result(base, "fixture", "token", remote = remote, pairRoute = route) },
        )
        assertEquals(HostRoute.LAN, result.pairRoute)
    }

    @Test fun `explicit authentication failures never replay on other URLs or routes`() {
        for (remoteCapability in listOf(remote, null)) {
            var requests = 0
            val scanned = qr().copy(remote = remoteCapability, urls = listOf("https://10.0.0.1:18640", "https://10.0.0.2:18640"))
            val error = runCatching {
                PairClient.pairWithQr(scanned, "phone", lanCapable = true, probe = { _, _ -> true },
                    send = { _, _, _, _, _, _, _ -> requests++; throw PairRequestException(PairClient.pairFailureFromHttp(401, "")) },
                )
            }.exceptionOrNull()
            assertTrue(error is PairRequestException)
            assertEquals(1, requests)
        }
    }

    @Test fun `broken remote retains reason after all legitimate LAN addresses fail`() {
        val error = runCatching {
            PairClient.pairWithQr(qr().copy(remote = null, remoteCapability = QrRemoteCapability.INVALID), "phone",
                send = { _, _, _, _, _, _, _ -> throw ConnectException("private address must not appear in UI") },
            )
        }.exceptionOrNull() as PairRequestException
        assertEquals(PairFailureCode.QR_REMOTE_INVALID, error.failure.code)
        assertEquals(L.pairRemoteInvalid, error.failure.message)
    }
}
