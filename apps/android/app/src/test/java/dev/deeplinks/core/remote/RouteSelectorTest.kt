package dev.deeplinks.core.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/** 自动选路（RFC §7.2）：探测、缓存时长、网络代、单飞。 */
class RouteSelectorTest {

    private var now = 1_000_000L
    private val selector = RouteSelector(clock = { now })

    @Test
    fun `host without remote never probes and only uses LAN`() {
        var probes = 0
        assertEquals(listOf(HostRoute.LAN), selector.order("k", hasRemote = false) { probes++; false })
        assertEquals(0, probes)
    }

    @Test
    fun `LAN reachable picks LAN first and caches it for 30 seconds`() {
        var probes = 0
        assertEquals(listOf(HostRoute.LAN, HostRoute.REMOTE), selector.order("k", true) { probes++; true })
        now += RouteSelector.LAN_TTL_MS - 1
        selector.order("k", true) { probes++; false }
        assertEquals(1, probes)
        now += 1
        assertEquals(listOf(HostRoute.REMOTE, HostRoute.LAN), selector.order("k", true) { probes++; false })
        assertEquals(2, probes)
    }

    @Test
    fun `remote result expires after 15 seconds so coming home switches back quickly`() {
        var lan = false
        assertEquals(HostRoute.REMOTE, selector.order("k", true) { lan }.first())
        lan = true
        now += RouteSelector.REMOTE_TTL_MS - 1
        assertEquals(HostRoute.REMOTE, selector.order("k", true) { lan }.first())
        now += 1
        assertEquals(HostRoute.LAN, selector.order("k", true) { lan }.first())
    }

    @Test
    fun `network change drops cached decisions`() {
        var lan = true
        selector.order("k", true) { lan }
        lan = false
        selector.onNetworkChanged()
        assertEquals(HostRoute.REMOTE, selector.order("k", true) { lan }.first())
    }

    @Test
    fun `success on the fallback route becomes the cached choice and the UI route`() {
        selector.order("k", true) { true }
        selector.forget("k")
        selector.noteSuccess("k", HostRoute.REMOTE)
        assertEquals(HostRoute.REMOTE, selector.lastRoute("k"))
        var probes = 0
        assertEquals(HostRoute.REMOTE, selector.order("k", true) { probes++; true }.first())
        assertEquals(0, probes)
    }

    @Test
    fun `clock offset follows the computer's hostNow`() {
        selector.noteHostNow("k", now / 1000 + 120)
        assertEquals(120L, selector.clockOffsetSec("k"))
        assertEquals(0L, selector.clockOffsetSec("other"))
    }

    @Test
    fun `concurrent requests share a single probe`() {
        var probes = 0
        val threads = (1..8).map {
            Thread { selector.order("k", true) { synchronized(this) { probes++ }; Thread.sleep(50); true } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, probes)
    }

    // ---------- S4：默认网络没有 Wi-Fi / 以太网时跳过局域网探测 ----------

    @Test
    fun `cellular only skips LAN probe and caches remote`() {
        var probes = 0
        assertEquals(
            listOf(HostRoute.REMOTE, HostRoute.LAN),
            selector.order("k", true, { false }, { probes++; true }),
        )
        assertEquals(0, probes)
        // 15 秒内用缓存的远程，不探测
        now += RouteSelector.REMOTE_TTL_MS - 1
        assertEquals(HostRoute.REMOTE, selector.order("k", true, { true }, { probes++; true }).first())
        assertEquals(0, probes)
        // 缓存过期、此时有 Wi-Fi → 恢复探测
        now += 1
        assertEquals(HostRoute.LAN, selector.order("k", true, { true }, { probes++; true }).first())
        assertEquals(1, probes)
    }

    @Test
    fun `unknown lan capability keeps probing`() {
        var probes = 0
        selector.order("k", true, { null }, { probes++; false })
        assertEquals(1, probes)
    }

    @Test
    fun `lan capable true probes`() {
        var probes = 0
        selector.order("k", true, { true }, { probes++; true })
        assertEquals(1, probes)
    }
}
