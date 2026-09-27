package dev.deeplinks.native

import dev.deeplinks.core.ByteCodec
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHistoryCacheTest {
    private val root: File = Files.createTempDirectory("hist-cache").toFile()

    /** 可逆但非恒等：确认落盘的不是明文。 */
    private object XorCodec : ByteCodec {
        override fun encode(plain: ByteArray) = plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun decode(blob: ByteArray): ByteArray? = encode(blob)
    }

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun history(vararg msgs: String, hasMore: Boolean = false) = """
        {"messages":[${msgs.joinToString(",")}],"hasMore":$hasMore,"nextBeforeSeq":${if (hasMore) 5 else "null"},"maxSeq":42,
         "stats":{"turns":3}}
    """.trimIndent()

    private val userMsg = """{"id":"u1","role":"user","text":"hello secret","seq":1}"""
    private val pendingApproval = """{"id":"a1","role":"approval","text":"","approvalId":"ap","requestStatus":"pending","seq":2}"""
    private val runningAnswer = """{"id":"m1","role":"assistant","text":"partial","running":true,"seq":3}"""

    @Test
    fun roundTripParsesWithSameParserAndSanitizes() {
        val cache = SessionHistoryCache(root, "slot-A", XorCodec)
        cache.write("s1", history(userMsg, pendingApproval, runningAnswer, hasMore = true))
        val back = cache.read("s1")!!
        assertEquals(listOf("u1", "a1", "m1"), back.messages.map { it.id })
        assertEquals(REQUEST_UNKNOWN, back.messages[1].requestStatus)
        assertEquals(false, back.messages[2].running)
        assertTrue(back.hasMore)
        assertEquals(5L, back.nextBeforeSeq)
        assertEquals(42L, back.maxSeq)
    }

    @Test
    fun fileOnDiskIsNotPlaintextAndNameHidesSessionId() {
        val cache = SessionHistoryCache(root, "slot-A", XorCodec)
        cache.write("session-visible-id", history(userMsg))
        val files = root.walkTopDown().filter { it.isFile }.toList()
        assertEquals(1, files.size)
        assertFalse(files[0].path.contains("session-visible-id"))
        assertFalse(files[0].path.contains("slot-A"))
        assertFalse(String(files[0].readBytes(), Charsets.ISO_8859_1).contains("hello secret"))
    }

    @Test
    fun hostsAreIsolatedAndClearHostOnlyDropsOne() {
        val a = SessionHistoryCache(root, "slot-A", XorCodec)
        val b = SessionHistoryCache(root, "slot-B", XorCodec)
        a.write("s1", history(userMsg))
        b.write("s1", history(runningAnswer))
        assertEquals("u1", a.read("s1")!!.messages.single().id)
        assertEquals("m1", b.read("s1")!!.messages.single().id)
        File(root, SessionHistoryCache.hostDirName("slot-A")).deleteRecursively()
        assertNull(a.read("s1"))
        assertEquals("m1", b.read("s1")!!.messages.single().id)
    }

    @Test
    fun corruptOrUndecodableFileIsDroppedNotThrown() {
        val cache = SessionHistoryCache(root, "slot-A", XorCodec)
        cache.write("s1", history(userMsg))
        val broken = SessionHistoryCache(root, "slot-A", object : ByteCodec {
            override fun encode(plain: ByteArray) = plain
            override fun decode(blob: ByteArray): ByteArray? = null
        })
        assertNull(broken.read("s1"))
        assertNull(cache.read("s1"))
    }

    @Test
    fun oversizedResponseIsNotCachedAndRemovesStaleCopy() {
        val cache = SessionHistoryCache(root, "slot-A", XorCodec)
        cache.write("s1", history(userMsg))
        val huge = """{"id":"big","role":"assistant","text":"${"x".repeat(SessionHistoryCache.MAX_BYTES)}"}"""
        cache.write("s1", history(huge))
        assertNull(cache.read("s1"))
    }

    @Test
    fun keepsOnlyMostRecentSessions() {
        val cache = SessionHistoryCache(root, "slot-A", XorCodec)
        val total = SessionHistoryCache.MAX_SESSIONS + 3
        for (i in 0 until total) {
            cache.write("s$i", history(userMsg))
            // 刚写的文件带真实时间戳：改成递增的小值，拉开先后（避免同一毫秒内写入）
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                if (f.lastModified() > 1_000_000L) f.setLastModified(1_000L * (i + 1))
            }
        }
        val left = root.walkTopDown().filter { it.isFile && it.name.endsWith(".hist") }.count()
        assertEquals(SessionHistoryCache.MAX_SESSIONS, left)
        assertNull(cache.read("s0"))
        assertTrue(cache.read("s${total - 1}") != null)
    }

    @Test
    fun liveAfterCacheKeepsOnlyStreamAdditions() {
        val msgs = listOf(MobileMessage("c1", "user", "a"), MobileMessage("sse", "assistant", "b"))
        assertEquals(listOf("sse"), liveAfterCache(msgs, setOf("c1")).map { it.id })
        assertEquals(msgs, liveAfterCache(msgs, emptySet()))
    }

    @Test
    fun sanitizeLeavesTerminalRequestsAlone() {
        val done = MobileMessage("q", "question", "", requestStatus = REQUEST_RESOLVED)
        assertTrue(sanitizeCachedMessages(listOf(done)).single() === done)
    }
}
