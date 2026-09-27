package dev.deeplinks.native.util

import dev.deeplinks.native.MobileSession
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class HomeSectionsTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 9, 27, 15, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 9, day, hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun s(id: String, updated: Long, running: Boolean = false, awaiting: Boolean = false) =
        MobileSession(id, id, updated, running, blank = false, cwd = null, agentPreset = null, awaitingInput = awaiting)

    @Test
    fun awaitingThenRunningThenByDay() {
        val out = homeSections(
            listOf(
                s("old", at(20, 9)),
                s("today", at(27, 9)),
                s("yday", at(26, 23)),
                s("run", at(25, 9), running = true),
                s("wait", at(24, 9), running = true, awaiting = true),
            ),
            now,
            zone,
        )
        assertEquals(
            listOf(HomeSection.AWAITING, HomeSection.RUNNING, HomeSection.TODAY, HomeSection.YESTERDAY, HomeSection.EARLIER),
            out.map { it.first },
        )
        assertEquals(listOf("wait"), out[0].second.map { it.sessionId })
    }

    @Test
    fun secondsTimestampsAndNewestFirst() {
        val secs = at(27, 10) / 1000
        val out = homeSections(listOf(s("a", at(27, 8)), s("b", secs)), now, zone)
        assertEquals(HomeSection.TODAY, out.single().first)
        assertEquals(listOf("b", "a"), out.single().second.map { it.sessionId })
    }

    @Test
    fun emptyInputGivesNoSections() {
        assertEquals(emptyList<Pair<HomeSection, List<MobileSession>>>(), homeSections(emptyList(), now, zone))
    }
}
