package dev.deeplinks.native.util

import dev.deeplinks.core.L
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 消息行的钟点时间（HH:mm），对齐 Web 助手行末尾的时间戳。 */
fun formatClockTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

/** 侧栏/历史相对时间（刚刚 / n分钟 / n小时 / n天）。 */
fun relativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    if (timestamp <= 0) return ""
    val diff = now - timestamp
    return when {
        diff < 60_000 -> L.justNowShort
        diff < 3_600_000 -> L.minutesShort.format(diff / 60_000)
        diff < 86_400_000 -> L.hoursShort.format(diff / 3_600_000)
        else -> L.daysShort.format(diff / 86_400_000)
    }
}

/**
 * 首页会话行尾的时间（2026-09-28 重设计「最近」分组）。
 *
 * 比 [relativeTime] 多一层自然日判断：稿子里最近分组的行尾写的是「昨天 / 周五」，
 * 而不是「1 天前 / 4 天前」——按天分段更符合「这是哪天干的活」这个问法。
 * 一周以外退回 [relativeTime] 的「n 天前」。
 */
fun homeTimeLabel(
    timestamp: Long,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    if (timestamp <= 0) return ""
    // 旧 Host 给的是秒：先归一到毫秒，否则 diff 会算成几十年
    val ts = sessionMillis(timestamp)
    val diff = now - ts
    if (diff < 60_000) return L.justNowShort
    if (diff < 3_600_000) return L.minutesShort.format(diff / 60_000)
    val day = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(day, today)
    return when {
        days <= 0L -> L.hoursShort.format(diff / 3_600_000)
        days == 1L -> L.timeYesterday
        days in 2..6 -> day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        else -> L.daysShort.format(days)
    }
}

/** 「上次在线」的时间戳 → 相对时间文案；0/负数表示没有记录，返回 null（不写假时间）。 */
fun lastOnlineLabel(lastOnlineAt: Long): String? = lastOnlineAt.takeIf { it > 0 }?.let(::relativeTime)
