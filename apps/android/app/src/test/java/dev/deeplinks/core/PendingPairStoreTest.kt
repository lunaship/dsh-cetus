package dev.deeplinks.core

import dev.deeplinks.core.remote.HostRoute
import dev.deeplinks.core.remote.RemoteRoute
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingPairStoreTest {
    /** Store ciphertext, so a repeated save must read through the decode callback. */
    private class MemoryStore {
        var encrypted: String? = null
        var failWrites = false
        val events = mutableListOf<String>()
        val repository = PendingPairRepository(
            read = { encrypted?.let { String(Base64.getDecoder().decode(it), Charsets.UTF_8) } },
            write = { plain ->
                events += "pending-write"
                if (failWrites) false else {
                    encrypted = plain?.let { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
                    true
                }
            },
        )
    }

    private fun session(attempt: String, device: String = "device") = PairingSession(
        requestId = "request", attemptId = attempt,
        host = Host("computer", "https://fixture.invalid", "token", device, "a".repeat(64)),
        pairRoute = HostRoute.LAN, pendingExpiresAt = 1_760_000_600_000L,
        serverNow = 1_760_000_000_000L, receivedAtMs = 1_760_000_000_000L,
    )

    @Test
    fun `encrypted pending can be saved repeatedly and cancelled before another pair`() {
        val store = MemoryStore()
        val first = session(store.repository.begin()!!)
        assertTrue(store.repository.save(first))
        val paused = first.copy(paused = true, pauseReason = PairFailureCode.CERT_MISMATCH)
        assertTrue(store.repository.save(paused))
        assertEquals(paused, store.repository.load())
        assertEquals(PairRecovery.RESCAN, PairFailure.restored(store.repository.load()!!).recovery)
        assertTrue(store.repository.abort(first.attemptId))
        assertNull(store.repository.load())
        val second = session(store.repository.begin()!!, "next-device")
        assertTrue(store.repository.save(second))
        assertEquals(second, store.repository.load())
    }

    @Test
    fun `full device capability survives storage including non utf8 binary fields`() {
        val store = MemoryStore()
        val remote = RemoteRoute(
            "wss://fixture.invalid/ws", ByteArray(16) { (128 + it).toByte() },
            ByteArray(16) { (240 - it).toByte() }, ByteArray(32) { (255 - it).toByte() },
            "device", "b".repeat(64),
        )
        val candidate = session(store.repository.begin()!!).let {
            it.copy(host = it.host.withRemote(remote).copy(tailnetUrl = "https://100.64.0.9:18640"), pairRoute = HostRoute.REMOTE)
        }
        assertTrue(store.repository.save(candidate))
        val restored = store.repository.load()!!
        assertEquals(candidate, restored)
        val route = restored.host.remoteRoute()!!
        assertArrayEquals(remote.routeId, route.routeId)
        assertArrayEquals(remote.keyId, route.keyId)
        assertArrayEquals(remote.key, route.key)
        assertEquals("device", route.kind)
        val plain = String(Base64.getDecoder().decode(store.encrypted), Charsets.UTF_8)
        val fields = JSONObject(plain).getJSONObject("session")
        assertFalse(fields.has("remoteSeed"))
        assertFalse(fields.has("code"))
        assertFalse(fields.has("monotonicMillis"))
    }

    @Test
    fun `failed formal save keeps candidate and original formal device`() {
        val store = MemoryStore()
        val candidate = session(store.repository.begin()!!)
        assertTrue(store.repository.save(candidate))
        val formal = candidate.host.copy(deviceId = "original", token = "original-token")
        assertFalse(store.repository.promote(candidate) { false })
        assertEquals(candidate, store.repository.load())
        assertEquals("original", formal.deviceId)
        assertFalse(store.repository.complete(candidate.attemptId, candidate.host) { false })
        assertEquals(candidate, store.repository.load())
    }

    @Test
    fun `old attempt cannot save cancel delete complete or promote a new candidate`() {
        val store = MemoryStore()
        val old = session(store.repository.begin()!!, "old-device")
        assertTrue(store.repository.save(old))
        val current = session(store.repository.begin()!!, "new-device")
        assertTrue(store.repository.save(current))
        var formalWrites = 0
        val saveHost: (Host) -> Boolean = { formalWrites++; true }
        assertFalse(store.repository.save(old))
        assertFalse(store.repository.abort(old.attemptId))
        assertFalse(store.repository.remove(old.attemptId, old.host.deviceId))
        assertFalse(store.repository.promote(old, saveHost))
        assertFalse(store.repository.complete(old.attemptId, old.host, saveHost))
        assertEquals(0, formalWrites)
        assertEquals(current, store.repository.load())
    }

    @Test
    fun `attempt match alone cannot delete or promote another device`() {
        val store = MemoryStore()
        val current = session(store.repository.begin()!!)
        assertTrue(store.repository.save(current))
        assertFalse(store.repository.remove(current.attemptId, "wrong-device"))
        assertFalse(store.repository.promote(current.copy(host = current.host.copy(deviceId = "wrong-device"))) {
            throw AssertionError("Stale device reached formal store")
        })
        assertEquals(current, store.repository.load())
    }

    @Test
    fun `promotion persists formal credentials before clearing pending`() {
        val store = MemoryStore()
        val candidate = session(store.repository.begin()!!)
        assertTrue(store.repository.save(candidate))
        store.events.clear()
        var formal: Host? = null
        assertTrue(store.repository.promote(candidate) {
            assertEquals(candidate, store.repository.load())
            store.events += "formal-write"
            formal = it
            true
        })
        assertEquals(listOf("formal-write", "pending-write"), store.events)
        assertEquals(candidate.host, formal)
        assertNull(store.repository.load())
    }

    @Test
    fun `already approved pair completion writes formal before closing attempt`() {
        val store = MemoryStore()
        val attempt = store.repository.begin()!!
        val host = session(attempt).host
        store.events.clear()
        assertTrue(store.repository.complete(attempt, host) {
            assertTrue(store.repository.isCurrent(attempt))
            store.events += "formal-write"
            assertEquals(host, it)
            true
        })
        assertEquals(listOf("formal-write", "pending-write"), store.events)
        assertNull(store.repository.load())
    }

    @Test
    fun `cleanup failure after formal save is reconciled on restart`() {
        val store = MemoryStore()
        val candidate = session(store.repository.begin()!!)
        assertTrue(store.repository.save(candidate))
        var formal: Host? = null
        store.failWrites = true
        assertTrue(store.repository.promote(candidate) { formal = it; true })
        assertEquals(candidate, store.repository.load())
        store.failWrites = false
        assertNull(store.repository.reconcile(formal))
        assertNull(store.repository.load())
        assertEquals(candidate.host, formal)
    }

    @Test
    fun `different formal device retains pending for startup recovery`() {
        val store = MemoryStore()
        val candidate = session(store.repository.begin()!!)
        assertTrue(store.repository.save(candidate))
        assertEquals(candidate, store.repository.reconcile(candidate.host.copy(deviceId = "original")))
        assertEquals(candidate, store.repository.reconcile(null))
    }

    @Test
    fun `damaged pending leaves the independent formal device untouched`() {
        val store = MemoryStore()
        val formal = Host("original", "https://original.invalid", "original-token", "original")
        store.encrypted = Base64.getEncoder().encodeToString("not json".toByteArray())
        assertTrue(runCatching { store.repository.reconcile(formal) }.isFailure)
        assertEquals("original-token", formal.token)
        assertEquals("original", formal.deviceId)
        val fresh = store.repository.begin()
        assertNotNull(fresh)
        assertTrue(store.repository.save(session(fresh!!)))
    }
    @Test
    fun `old removal callback cannot navigate while new attempt is reserved`() {
        val store = MemoryStore()
        val old = session(store.repository.begin()!!)
        assertTrue(store.repository.save(old))
        val next = store.repository.begin()!!
        assertFalse(store.repository.remove(old.attemptId, old.host.deviceId))
        assertTrue(store.repository.isCurrent(next))
    }
}
