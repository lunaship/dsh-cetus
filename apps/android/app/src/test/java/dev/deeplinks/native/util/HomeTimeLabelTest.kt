package dev.deeplinks.native.util

import dev.deeplinks.core.L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * 首页行尾时间（2026-09-28 重设计「最近」分组）。
 *
 * 与 relativeTime 的差别是自然日分段：稿子里最近分组的行尾写「昨天 / 周五」，
 * 而不是「1 天前 / 4 天前」。
 */
class HomeTimeLabelTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val locale = Locale.SIMPLIFIED_CHINESE
    private val now = LocalDateTime.of(2026, 9, 27, 15, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun at(day: Int, hour: Int) =
        LocalDateTime.of(2026, 9, day, hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun label(ts: Long) = homeTimeLabel(ts, now, zone, locale)

    @Test
    fun justNowAndMinutesAndHours() {
        assertEquals(L.justNowShort, label(now - 30_000))
        assertEquals(L.minutesShort.format(5), label(now - 5 * 60_000))
        assertEquals(L.hoursShort.format(3), label(now - 3 * 3_600_000))
    }

    @Test
    fun yesterdayUsesTheDayWord() {
        assertEquals(L.timeYesterday, label(at(26, 23)))
    }

    @Test
    fun twoToSixDaysAgoUsesWeekday() {
        // 2026-09-25 是周五
        val value = label(at(25, 9))
        assertTrue("weekday label was: $value", value.contains("五") || value.contains("Fri"))
    }

    @Test
    fun aWeekAndOlderFallsBackToDayCount() {
        assertEquals(L.daysShort.format(10), label(at(17, 9)))
    }

    @Test
    fun zeroOrNegativeTimestampIsEmpty() {
        assertEquals("", label(0L))
        assertEquals("", label(-1L))
    }

    /** 秒级时间戳（旧 Host）也要按同一天算，不能当成 1970 年。 */
    @Test
    fun secondsTimestampIsNormalised() {
        assertEquals(L.hoursShort.format(3), label((now - 3 * 3_600_000) / 1000))
    }
}
