package dev.deeplinks.native.util

import dev.deeplinks.native.MobileSession
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSectionsTest {
    private fun s(id: String, updated: Long = 1L, running: Boolean = false, awaiting: Boolean = false) =
        MobileSession(id, id, updated, running, blank = false, cwd = null, agentPreset = null, awaitingInput = awaiting)

    /** 2026-09-28 重设计：只有三段，日期不再是分区。 */
    @Test
    fun awaitingThenRunningThenRecent() {
        val out = homeSections(
            listOf(
                s("old", updated = 1_700_000_000_000L),
                s("today", updated = 1_790_600_000_000L),
                s("run", updated = 1_790_500_000_000L, running = true),
                s("wait", updated = 1_790_400_000_000L, running = true, awaiting = true),
            ),
        )
        assertEquals(
            listOf(HomeSection.AWAITING, HomeSection.RUNNING, HomeSection.RECENT),
            out.map { it.first },
        )
        assertEquals(listOf("wait"), out[0].second.map { it.sessionId })
        assertEquals(listOf("run"), out[1].second.map { it.sessionId })
        assertEquals(listOf("today", "old"), out[2].second.map { it.sessionId })
    }

    /** 秒级时间戳也要能正确排序（旧 Host 给的是秒）。 */
    @Test
    fun secondsTimestampsAndNewestFirst() {
        val secs = 1_790_600_000L
        val out = homeSections(listOf(s("a", updated = 1_790_599_000_000L), s("b", updated = secs)))
        assertEquals(HomeSection.RECENT, out.single().first)
        assertEquals(listOf("b", "a"), out.single().second.map { it.sessionId })
    }

    /** 等待中优先于运行中：一条会话只落一个分区。 */
    @Test
    fun awaitingWinsOverRunning() {
        val out = homeSections(listOf(s("both", updated = 1L, running = true, awaiting = true)))
        assertEquals(HomeSection.AWAITING, out.single().first)
    }

    @Test
    fun emptyInputGivesNoSections() {
        assertEquals(emptyList<Pair<HomeSection, List<MobileSession>>>(), homeSections(emptyList()))
    }
}
