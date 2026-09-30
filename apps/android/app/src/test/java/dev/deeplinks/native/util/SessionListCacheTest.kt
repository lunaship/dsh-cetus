package dev.deeplinks.native.util

import dev.deeplinks.core.ByteCodec
import dev.deeplinks.native.MobileSession
import dev.deeplinks.native.MobileSessionActivity
import dev.deeplinks.native.MobileSessionResult
import dev.deeplinks.native.MobileWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** S1：会话列表缓存的序列化往返、版本丢弃、截断、主机隔离。 */
class SessionListCacheTest {

    private object IdentityCodec : ByteCodec {
        override fun encode(plain: ByteArray): ByteArray = plain
        override fun decode(blob: ByteArray): ByteArray? = blob
    }

    private fun tmpDir(): File = File.createTempFile("session-list", "").let { it.delete(); it.mkdirs(); it }

    private fun session(id: String, updatedAt: Long = 1L) = MobileSession(
        sessionId = id,
        title = "title-$id",
        updatedAt = updatedAt,
        running = false,
        blank = false,
        cwd = "/Users/me/$id",
        agentPreset = "standard",
        awaitingInput = true,
        activity = MobileSessionActivity(kind = "tool", label = "go test", step = 2L, startedAt = 9L),
        lastResult = MobileSessionResult(text = "done", files = 1L, added = 2L, deleted = 3L),
        stoppedReason = "completed",
    )

    @Test
    fun `序列化往返保持字段`() {
        val snapshot = SessionListSnapshot(
            sessions = listOf(session("a"), session("b", 2L)),
            archivedSessionIds = setOf("x", "y"),
            workspaces = listOf(MobileWorkspace("w1", "/Users/me/proj", "proj", listOf("a"))),
        )
        val decoded = decodeSessionListSnapshot(encodeSessionListSnapshot(snapshot))
        assertNotNull(decoded)
        assertEquals(2, decoded!!.sessions.size)
        assertEquals(setOf("x", "y"), decoded.archivedSessionIds)
        assertEquals("/Users/me/proj", decoded.workspaces.single().path)
        assertEquals("go test", decoded.sessions.first().activity?.label)
        assertEquals(1L, decoded.sessions.first().lastResult?.files)
        assertEquals("completed", decoded.sessions.first().stoppedReason)
    }

    @Test
    fun `版本不匹配时丢弃`() {
        val text = encodeSessionListSnapshot(
            SessionListSnapshot(listOf(session("a")), emptySet(), emptyList()),
        ).replace("\"version\":${SessionListCache.VERSION}", "\"version\":999")
        assertNull(decodeSessionListSnapshot(text))
    }

    @Test
    fun `超过 200 条按更新时间截断`() {
        val sessions = (1..250).map { session("s$it", updatedAt = it.toLong()) }
        val capped = capSessions(sessions)
        assertEquals(SessionListCache.MAX_SESSIONS, capped.size)
        // 保留最新的 200 个：updatedAt 51..250
        assertEquals("s250", capped.first().sessionId)
        assertEquals("s51", capped.last().sessionId)
    }

    @Test
    fun `主机隔离且 clearHost 只清一台`() {
        val cacheDir = tmpDir()
        try {
            val cacheRoot = SessionListCache.rootDir(cacheDir)
            val a = SessionListCache(cacheRoot, "host-a", IdentityCodec)
            val b = SessionListCache(cacheRoot, "host-b", IdentityCodec)
            a.write(SessionListSnapshot(listOf(session("a")), emptySet(), emptyList()))
            b.write(SessionListSnapshot(listOf(session("b")), emptySet(), emptyList()))
            assertEquals("a", a.read()!!.sessions.single().sessionId)
            assertEquals("b", b.read()!!.sessions.single().sessionId)

            SessionListCache.clearHost(cacheDir, "host-a")
            assertNull(a.read())
            assertNotNull(b.read())
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `未知版本文件读取返回 null 并删除`() {
        val cacheDir = tmpDir()
        try {
            val cacheRoot = SessionListCache.rootDir(cacheDir)
            val cache = SessionListCache(cacheRoot, "host-a", IdentityCodec)
            cache.write(SessionListSnapshot(listOf(session("a")), emptySet(), emptyList()))
            // 篡改盘上内容为不认识的版本
            val dir = File(cacheRoot, SessionListCache.hostDirName("host-a"))
            val file = dir.listFiles()!!.first()
            file.writeText("{\"version\":999}")
            assertNull(cache.read())
            assertTrue(dir.listFiles()!!.none { it.name.startsWith("list.json.cache") })
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}
