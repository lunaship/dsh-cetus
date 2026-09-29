package dev.deeplinks.native.util

import dev.deeplinks.native.MobileSession

/**
 * 首页分区（2026-09-28 重设计）：三段——等你处理 → 进行中 → 最近。
 *
 * 原先把「今天 / 昨天 / 更早」也当分区，但首页只回答三个问题：
 * 「有没有在等我」「在跑什么」「最近干完了什么」。日期不再是分区，改成行尾的相对时间。
 * 顺序即展示顺序；同一分区内按更新时间倒序。
 */
enum class HomeSection { AWAITING, RUNNING, RECENT }

/** 会话时间戳归一到毫秒：旧 Host 给的是秒。 */
fun sessionMillis(ts: Long): Long = if (ts in 1 until 1_000_000_000_000L) ts * 1000 else ts

fun homeSections(sessions: List<MobileSession>): List<Pair<HomeSection, List<MobileSession>>> {
    val buckets = LinkedHashMap<HomeSection, MutableList<MobileSession>>()
    sessions.sortedByDescending { sessionMillis(it.updatedAt) }.forEach { s ->
        val section = when {
            s.awaitingInput -> HomeSection.AWAITING
            s.running -> HomeSection.RUNNING
            else -> HomeSection.RECENT
        }
        buckets.getOrPut(section) { mutableListOf() }.add(s)
    }
    return HomeSection.values().mapNotNull { key -> buckets[key]?.let { key to it.toList() } }
}
