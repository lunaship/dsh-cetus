package dev.deeplinks.native.util

import dev.deeplinks.native.MobileSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** K4：首页会话列表按 sessionId 去重，避免 LazyColumn 重复 key。 */
class HomeSectionsTest {

    private fun session(id: String, updatedAt: Long, running: Boolean = false, awaiting: Boolean = false) =
        MobileSession(
            sessionId = id,
            title = "t-$id",
            updatedAt = updatedAt,
            running = running,
            blank = false,
            cwd = null,
            agentPreset = null,
            awaitingInput = awaiting,
        )

    @Test
    fun `重复 sessionId 只保留最新的一条`() {
        val sections = homeSections(
            listOf(
                session("a", 100, running = true),
                session("a", 200),
                session("b", 150),
            ),
        )
        val all = sections.flatMap { it.second }
        assertEquals(2, all.size)
        assertEquals(setOf("a", "b"), all.map { it.sessionId }.toSet())
        // 保留的是 updatedAt 更大的那条（200 → RECENT，而不是 100 → RUNNING）
        val a = all.first { it.sessionId == "a" }
        assertEquals(200L, a.updatedAt)
    }

    @Test
    fun `分区顺序固定 等你处理 进行中 最近`() {
        val sections = homeSections(
            listOf(
                session("recent", 50),
                session("running", 60, running = true),
                session("awaiting", 70, awaiting = true),
            ),
        )
        assertEquals(
            listOf(HomeSection.AWAITING, HomeSection.RUNNING, HomeSection.RECENT),
            sections.map { it.first },
        )
        assertTrue(sections.first().second.single().sessionId == "awaiting")
    }
}
