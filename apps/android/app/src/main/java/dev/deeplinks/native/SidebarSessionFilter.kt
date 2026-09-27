package dev.deeplinks.native

/**
 * 侧边栏会话过滤（从 WorkspaceScreen 抽出，COM-001 拆解）。
 *
 * 规则（原本内联在 composable 里、无法单测）：
 * 1. 排除归档 / 已删除 / 子智能体会话；
 * 2. 排除超过 24h 的空白会话（陈旧占位）；
 * 3. 无查询 → 原样返回；有查询 → 只保留标题或路径命中，或服务端检索命中的会话。
 *
 * 纯函数、无 Compose 依赖。
 */
internal fun filterSidebarSessions(
    sessions: List<MobileSession>,
    archivedIds: Set<String>,
    deletedIds: Set<String>,
    searchNeedle: String,
    searchResultIds: List<String>,
    nowMillis: Long,
): List<MobileSession> {
    val staleCutoff = nowMillis - 24 * 3600_000L
    val candidates = sessions.filter {
        it.sessionId !in archivedIds && it.sessionId !in deletedIds &&
            it.origin != "subagent" &&
            !(it.blank && it.updatedAt < staleCutoff)
    }
    if (searchNeedle.isEmpty()) return candidates
    val matched = (
        candidates.filter {
            it.title.contains(searchNeedle, ignoreCase = true) ||
                (it.cwd?.contains(searchNeedle, ignoreCase = true) == true)
        }.map { it.sessionId } + searchResultIds
    ).toSet()
    return candidates.filter { it.sessionId in matched }
}
/**
 * 当前会话的活跃子智能体数量（从 WorkspaceScreen 抽出）。
 * 优先用会话自带的计数；为 0/缺失时回退到「按 parentSessionId 统计子会话」。
 */
internal fun resolveActiveSubagentCount(
    sessions: List<MobileSession>,
    currentSessionId: String?,
    currentSubagentCount: Int?,
): Int {
    val sid = currentSessionId ?: return 0
    return currentSubagentCount?.takeIf { it > 0 }
        ?: sessions.count { it.origin == "subagent" && it.parentSessionId == sid }
}

/**
 * 会话标题的显示形态：开头是 `@/…` / `@~/…` 文件引用时只留路径最后一段，
 * 例如 `@/Users/me/Desktop/notes.md 帮我看看` → `notes.md 帮我看看`。
 * 只影响显示；复制标题、导出、搜索仍用原文。
 */
internal fun displaySessionTitle(title: String): String {
    val trimmed = title.trimStart()
    if (!trimmed.startsWith("@/") && !trimmed.startsWith("@~/")) return title
    val end = trimmed.indexOfFirst { it.isWhitespace() }.let { if (it < 0) trimmed.length else it }
    val last = trimmed.substring(1, end).trimEnd('/').substringAfterLast('/')
    if (last.isBlank() || last == "~") return title
    return last + trimmed.substring(end)
}

/**
 * 格式化会话列表副标题。状态只写在这一行灰字里：
 * 等待确认优先于运行中；运行中有目标就写目标，否则写运行文案；其余是项目 · 时间。
 */
internal fun formatSessionSubtitle(
    session: MobileSession,
    goalSummary: String? = null,
    runningLabel: String = "运行中",
    awaitingLabel: String = "等待确认",
    relativeTimeFormatted: String = "",
): String {
    val project = session.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    return when {
        session.awaitingInput -> {
            if (project != null) "$project · $awaitingLabel" else awaitingLabel
        }
        session.running && !goalSummary.isNullOrBlank() -> {
            if (project != null) "$project · $goalSummary" else goalSummary
        }
        session.running -> {
            if (project != null) "$project · $runningLabel" else runningLabel
        }
        else -> {
            listOfNotNull(project, relativeTimeFormatted.takeIf { it.isNotBlank() }).joinToString(" · ")
        }
    }
}

