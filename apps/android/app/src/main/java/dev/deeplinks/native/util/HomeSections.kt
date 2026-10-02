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
    // K4：服务端偶尔会返回重复 sessionId；先按更新时间倒序再 distinctBy，保留最新的那条，
    // 否则同一 key 会在 LazyColumn 里重复，抛「Key was already used」闪退。
    sessions.sortedByDescending { sessionMillis(it.updatedAt) }
        .distinctBy { it.sessionId }
        .forEach { s ->
            val section = when {
                s.awaitingInput -> HomeSection.AWAITING
                s.running -> HomeSection.RUNNING
                else -> HomeSection.RECENT
            }
            buckets.getOrPut(section) { mutableListOf() }.add(s)
        }
    return HomeSection.values().mapNotNull { key -> buckets[key]?.let { key to it.toList() } }
}

/** 2.4 搜索结果：标题命中在前，其余（服务端全文检索命中）带摘要放「内容匹配」。 */
data class HomeSearchGroups(
    val titleMatches: List<MobileSession>,
    val contentMatches: List<Pair<MobileSession, String?>>,
)

fun homeSearchGroups(
    sessions: List<MobileSession>,
    needle: String,
    snippets: Map<String, String>,
): HomeSearchGroups {
    val ordered = sessions.sortedByDescending { sessionMillis(it.updatedAt) }.distinctBy { it.sessionId }
    if (needle.isBlank()) return HomeSearchGroups(ordered, emptyList())
    val (title, rest) = ordered.partition { it.title.contains(needle, ignoreCase = true) }
    return HomeSearchGroups(title, rest.map { it to snippets[it.sessionId]?.takeIf { s -> s.isNotBlank() } })
}
