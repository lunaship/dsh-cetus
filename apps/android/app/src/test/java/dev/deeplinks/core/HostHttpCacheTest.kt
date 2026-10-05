package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HostHttpCacheTest {

    @Test
    fun `remote cache drops other offsets of the same route`() {
        val prefix = "https://host\u001ffinger\u001fwss://relay\u001fdlp"
        val old = "$prefix\u001f1"
        val current = "$prefix\u001f9"
        val otherRoute = "https://host\u001ffinger\u001fwss://other\u001fdlp\u001f1"
        assertEquals(setOf(old), HostHttp.staleRemoteKeys(setOf(old, current, otherRoute), current))
        assertEquals(emptySet<String>(), HostHttp.staleRemoteKeys(setOf(current), current))
    }

    @Test
    fun `lan cache drops other fingerprints of the same url`() {
        val old = "https://host\u001fold-pin"
        val current = "https://host\u001fnew-pin"
        val other = "https://other\u001fold-pin"
        assertEquals(setOf(old), HostHttp.staleLanKeys(setOf(old, current, other), current))
        assertEquals(emptySet<String>(), HostHttp.staleLanKeys(setOf(current), current))
    }
}
