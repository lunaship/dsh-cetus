package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v4 8.3 分享选会话：候选列表与副标题。 */
class SharePickerTest {

    private fun session(id: String, updatedAt: Long, origin: String? = null, blank: Boolean = false) =
        MobileSession(id, id, updatedAt, running = false, blank = blank, cwd = null, agentPreset = null, origin = origin)

    @Test
    fun `targets hide subagents and blanks, newest first, capped`() {
        val sessions = listOf(
            session("old", 1),
            session("sub", 9, origin = "subagent"),
            session("blank", 8, blank = true),
            session("new", 5),
            session("mid", 3),
        )
        assertEquals(listOf("new", "mid", "old"), shareTargets(sessions).map { it.sessionId })
        assertEquals(listOf("new", "mid"), shareTargets(sessions, limit = 2).map { it.sessionId })
    }

    @Test
    fun `summary joins image count and first text line`() {
        assertEquals("2 张图片 · 第一行", shareSummary("\n  第一行  \n第二行", 2, "%d 张图片"))
        assertEquals("hello", shareSummary("hello", 0, "%d images"))
        assertEquals("1 images", shareSummary(null, 1, "%d images"))
        assertNull(shareSummary("  ", 0, "%d images"))
    }
}
