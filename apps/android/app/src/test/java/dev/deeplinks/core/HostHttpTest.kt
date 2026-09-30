package dev.deeplinks.core

import dev.deeplinks.core.remote.HostRoute
import dev.deeplinks.core.remote.ReleasingBody
import dev.deeplinks.core.remote.RemoteGate
import dev.deeplinks.core.remote.RouteOfflineException
import dev.deeplinks.core.remote.RouteRejectedException
import dev.deeplinks.core.remote.RouteUnreachableException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.ServerSocket
import java.net.SocketTimeoutException


/**
 * 换路规则（RFC §7.2 第 7、8 条）。传输本身（TLS / DLP 隧道）由 WebSocketTunnelSocketFactoryTest
 * 与真实中继联调覆盖，这里只验证 [attemptWithFailover] 与局域网探测的契约。
 */
class HostHttpTest {

    @Test
    fun `connect failure switches to the next route`() {
        val order = mutableListOf<String>()
        val result = attemptWithFailover("test", listOf(HostRoute.LAN, HostRoute.REMOTE), hasBody = false) { route ->
            order += route.name
            if (route == HostRoute.LAN) throw ConnectException("refused")
            "remote-response"
        }
        assertEquals(listOf("LAN", "REMOTE"), order)
        assertEquals("remote-response", result)
    }

    @Test
    fun `remote rendezvous failure before ready falls back to LAN`() {
        val order = mutableListOf<String>()
        val result = attemptWithFailover("test", listOf(HostRoute.REMOTE, HostRoute.LAN), hasBody = true) { route ->
            order += route.name
            if (route == HostRoute.REMOTE) throw RouteOfflineException()
            "lan-response"
        }
        assertEquals(listOf("REMOTE", "LAN"), order)
        assertEquals("lan-response", result)
    }

    @Test
    fun `when every route fails the preferred route's error is reported`() {
        val thrown = assertThrows(IOException::class.java) {
            attemptWithFailover("test", listOf(HostRoute.REMOTE, HostRoute.LAN), hasBody = false) { route ->
                if (route == HostRoute.REMOTE) throw RouteRejectedException("UNKNOWN_KEY")
                throw ConnectException("refused")
            }
        }
        assertTrue(thrown is RouteRejectedException)
    }

    @Test
    fun `pin change fails closed and never falls back`() {
        val order = mutableListOf<String>()
        assertThrows(PinnedSsl.CertChangedException::class.java) {
            attemptWithFailover("test", listOf(HostRoute.REMOTE, HostRoute.LAN), hasBody = false) { route ->
                order += route.name
                throw PinnedSsl.CertChangedException()
            }
        }
        assertEquals(listOf("REMOTE"), order)
    }

    @Test
    fun `failure after the connection is up never switches route`() {
        for (hasBody in listOf(true, false)) {
            val order = mutableListOf<String>()
            assertThrows(IOException::class.java) {
                attemptWithFailover("test", listOf(HostRoute.LAN, HostRoute.REMOTE), hasBody = hasBody) { route ->
                    order += route.name
                    throw SocketTimeoutException("Read timed out")
                }
            }
            assertEquals(listOf("LAN"), order)
        }
    }

    @Test
    fun `connect phase classification`() {
        assertTrue(isConnectPhaseFailure(ConnectException("refused")))
        assertTrue(isConnectPhaseFailure(SocketTimeoutException("connect timed out")))
        assertTrue(isConnectPhaseFailure(java.net.UnknownHostException("unable to resolve host")))
        assertTrue(isConnectPhaseFailure(IOException(javax.net.ssl.SSLException("handshake failed"))))
        // 远程会合阶段（ready 之前）的一切失败都是建立期
        assertTrue(isConnectPhaseFailure(RouteUnreachableException(ConnectException("refused"))))
        assertTrue(isConnectPhaseFailure(RouteRejectedException("UNKNOWN_KEY")))
        assertFalse(isConnectPhaseFailure(SocketTimeoutException("Read timed out")))
        assertFalse(isConnectPhaseFailure(IOException("write failed")))
        assertTrue(isConnectPhaseFailure(IOException(SocketTimeoutException("connect timed out"))))
    }

    @Test
    fun `busy failures never switch route and never get forgotten`() {
        val order = mutableListOf<String>()
        assertThrows(dev.deeplinks.core.remote.RouteBusyException::class.java) {
            attemptWithFailover("test", listOf(HostRoute.LAN, HostRoute.REMOTE), hasBody = false) { route ->
                order += route.name
                throw dev.deeplinks.core.remote.RouteBusyException("DEVICE_LIMIT")
            }
        }
        assertEquals(listOf("LAN"), order)
    }

    @Test
    fun `isBusy sees through the cause chain`() {
        assertTrue(isBusy(dev.deeplinks.core.remote.RouteBusyException("DEVICE_LIMIT")))
        assertTrue(isBusy(dev.deeplinks.core.remote.RouteServerBusyException()))
        assertTrue(isBusy(dev.deeplinks.core.remote.RouteRateLimitedException()))
        assertTrue(isBusy(IOException(dev.deeplinks.core.remote.RouteServerBusyException())))
        assertFalse(isBusy(ConnectException("refused")))
        assertFalse(isBusy(RouteRejectedException("UNKNOWN_KEY")))
    }

    @Test
    fun `LAN probe needs a pin and a reachable TLS peer`() {
        val pin = "ab".repeat(32)
        // 没有指纹：无法确认是这台电脑，不算通
        assertFalse(HostHttp.probeLanUrl("https://127.0.0.1:9", ""))
        // 端口没人听：不通
        val closedPort = ServerSocket(0).use { it.localPort }
        assertFalse(HostHttp.probeLanUrl("https://127.0.0.1:$closedPort", pin))
        // 有人听但不是 TLS（或证书不符）：也不通，交给远程
        ServerSocket(0).use { server ->
            Thread { runCatching { server.accept().use { it.getOutputStream().write("nope".toByteArray()) } } }.start()
            assertFalse(HostHttp.probeLanUrl("https://127.0.0.1:${server.localPort}", pin))
        }
    }
}

/** R2: 远程请求并发闸门（同机最多 4 个在途短请求）+ 名额在响应体关闭时归还。 */
class RemoteGateTest {
    @Test
    fun `at most four concurrent acquires per host`() {
        val key = "gate-" + System.nanoTime()
        val granted = (1..10).map { RemoteGate.acquire(key, 100) }
        assertEquals(4, granted.count { it })
        repeat(4) { RemoteGate.release(key) }
        assertTrue(RemoteGate.acquire(key, 100))
        RemoteGate.release(key)
    }

    @Test
    fun `queued thread gets the permit after release`() {
        val key = "gate-wait-" + System.nanoTime()
        repeat(RemoteGate.PERMITS) { assertTrue(RemoteGate.acquire(key, 100)) }
        val got = java.util.concurrent.atomic.AtomicBoolean(false)
        val thread = Thread {
            if (RemoteGate.acquire(key, 5_000)) {
                got.set(true)
                RemoteGate.release(key)
            }
        }
        thread.start()
        Thread.sleep(50)
        RemoteGate.release(key)
        thread.join(3_000)
        assertTrue("排队的线程应在释放后拿到名额", got.get())
    }

    @Test
    fun `releasing body returns the permit exactly once`() {
        val key = "gate-body-" + System.nanoTime()
        assertTrue(RemoteGate.acquire(key, 100))
        val released = java.util.concurrent.atomic.AtomicInteger(0)
        val body = ReleasingBody("x".toResponseBody("text/plain".toMediaType())) {
            released.incrementAndGet()
            RemoteGate.release(key)
        }
        body.close()
        body.close()
        assertEquals(1, released.get())
        assertTrue("名额应已归还", RemoteGate.acquire(key, 100))
        RemoteGate.release(key)
    }
}
