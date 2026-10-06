package dev.deeplinks.native.util

import dev.deeplinks.native.MobileSession

/** v4 2.1：注册表中的空工作区也保留；每个会话只出现一次。 */
data class HomeWorkspaceGroup(val path: String?, val sessions: List<MobileSession>) {
    val key: String get() = path?.let { "workspace:$it" } ?: "ungrouped"
    val awaitingCount: Int get() = sessions.count { it.awaitingInput }
    val runningCount: Int get() = sessions.count { it.running && !it.awaitingInput }
}

fun homeWorkspaceGroups(
    sessions: List<MobileSession>,
    workspaces: List<String>,
    accounts: Collection<WorkspaceAccount>,
    registryReady: Boolean,
): List<HomeWorkspaceGroup> {
    val paths = workspaces.map(::normalizeWorkspacePath).distinct()
    val buckets = paths.associateWith { mutableListOf<MobileSession>() }
    val ungrouped = mutableListOf<MobileSession>()
    sessions.sortedByDescending { sessionMillis(it.updatedAt) }.distinctBy { it.sessionId }.forEach { session ->
        // 注册表就绪后以电脑端 sessionIds 为准，不能把进程 cwd 当成项目归属。
        val owner = workspaceGroupKey(session.sessionId, accounts)
            ?: if (!registryReady) session.cwd?.let(::normalizeWorkspacePath) else null
        (buckets[owner] ?: ungrouped).add(session)
    }
    return paths.map { HomeWorkspaceGroup(it, buckets.getValue(it)) } +
        if (ungrouped.isEmpty()) emptyList() else listOf(HomeWorkspaceGroup(null, ungrouped))
}
