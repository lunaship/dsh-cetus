package dev.deeplinks.native.util

import dev.deeplinks.native.MobileSession
import java.time.Instant
import java.time.ZoneId

/**
 * 首页分区：先看「等你」的，再看「在跑」的，其余按最近活动的日期分。
 * 顺序即展示顺序；同一分区内按更新时间倒序。
 */
enum class HomeSection { AWAITING, RUNNING, TODAY, YESTERDAY, EARLIER }

/** 会话时间戳归一到毫秒：旧 Host 给的是秒。 */
fun sessionMillis(ts: Long): Long = if (ts in 1 until 1_000_000_000_000L) ts * 1000 else ts

fun homeSections(
    sessions: List<MobileSession>,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<Pair<HomeSection, List<MobileSession>>> {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val buckets = LinkedHashMap<HomeSection, MutableList<MobileSession>>()
    sessions.sortedByDescending { sessionMillis(it.updatedAt) }.forEach { s ->
        val section = when {
            s.awaitingInput -> HomeSection.AWAITING
            s.running -> HomeSection.RUNNING
            s.updatedAt <= 0L -> HomeSection.EARLIER
            else -> {
                val day = Instant.ofEpochMilli(sessionMillis(s.updatedAt)).atZone(zone).toLocalDate()
                when {
                    !day.isBefore(today) -> HomeSection.TODAY
                    day == today.minusDays(1) -> HomeSection.YESTERDAY
                    else -> HomeSection.EARLIER
                }
            }
        }
        buckets.getOrPut(section) { mutableListOf() }.add(s)
    }
    return HomeSection.values().mapNotNull { key -> buckets[key]?.let { key to it.toList() } }
}
