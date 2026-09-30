package dev.deeplinks.native.util

import dev.deeplinks.native.MobileSession

/**
 * 用户工作区过滤：排除隐藏目录与系统/依赖目录（DSH 只显示用户项目工作区）。
 */
fun isUserWorkspace(cwd: String?): Boolean {
    if (cwd.isNullOrBlank()) return true
    val parts = cwd.split('/').filter { it.isNotBlank() }
    if (parts.isEmpty()) return false
    return parts.none { part ->
        part.startsWith(".") ||
            part == "node_modules" ||
            part == ".npm" ||
            part == ".bin" ||
            part == "Library" ||
            part == "Applications" ||
            part == "System" ||
            part == "tmp" ||
            part == "private"
    }
}

fun normalizeWorkspacePath(path: String): String = path.trimEnd('/')

data class WorkspaceAccount(
    val path: String,
    val sessionIds: Collection<String>,
)

/**
 * Web 分组只认 workspace.sessionIds，不认 session.cwd。
 * cwd 碰巧等于某个已注册路径（例如进程启动目录）时仍是未分组。
 */
fun workspaceGroupKey(
    sessionId: String,
    accounts: Collection<WorkspaceAccount>,
    deletedWorkspaces: Set<String> = emptySet(),
): String? {
    val deleted = deletedWorkspaces.map(::normalizeWorkspacePath).toSet()
    val owned = accounts.firstOrNull { sessionId in it.sessionIds } ?: return null
    val path = normalizeWorkspacePath(owned.path)
    if (path.isBlank() || path in deleted || !isUserWorkspace(path)) return null
    return path
}

/** 胶囊 / 菜单上显示的工作区名：末段目录名，空则回退完整路径（去尾斜杠）。 */
fun workspaceDisplayName(path: String): String {
    val trimmed = path.trimEnd('/')
    return trimmed.substringAfterLast('/').ifBlank { path }
}

/**
 * 草稿画布工作区胶囊的显示名（与 [workspaceDisplayName] 同一口径，但按整批去重）：
 * 默认末级目录名；同一屏里末级同名时（/a/app 与 /b/app）逐级带上父级目录，直到在这批胶囊里唯一。
 * 选择器（[dev.deeplinks.native.WorkspacePickerSheet]）仍显示完整路径尾段，这里只解决胶囊撞名。
 */
fun draftWorkspaceChipLabels(paths: List<String>): List<String> {
    val segmentLists = paths.map { path ->
        path.trimEnd('/').split('/').filter { it.isNotBlank() }
    }
    return segmentLists.map { segments ->
        if (segments.isEmpty()) return@map ""
        var depth = 1
        while (depth <= segments.size) {
            val candidate = segments.takeLast(depth).joinToString("/")
            val duplicated = segmentLists.count { it.takeLast(depth).joinToString("/") == candidate } > 1
            if (!duplicated) return@map candidate
            depth++
        }
        segments.joinToString("/")
    }
}

/**
 * 首页工作区筛选：会话属于该工作区 = 在它的 sessionIds 里，或者 cwd 规范化后等于该路径、
 * 且没有被别的工作区的 sessionIds 认领。与 Web 分组的差别：Web 只认 sessionIds（侧栏树形分组），
 * 首页筛选是「这个文件夹里的任务」，按 cwd 兜底更符合用户预期。
 */
fun sessionsInWorkspace(
    sessions: List<MobileSession>,
    workspace: String,
    accounts: Collection<WorkspaceAccount>,
    deletedWorkspaces: Set<String>,
): List<MobileSession> {
    val target = normalizeWorkspacePath(workspace)
    val deleted = deletedWorkspaces.map(::normalizeWorkspacePath).toSet()
    if (target.isBlank() || target in deleted || !isUserWorkspace(target)) return emptyList()
    return sessions.filter { session ->
        val owner = accounts.firstOrNull { session.sessionId in it.sessionIds }
            ?.let { normalizeWorkspacePath(it.path) }
            ?.takeIf { it.isNotBlank() && it !in deleted && isUserWorkspace(it) }
        if (owner != null) owner == target
        else session.cwd?.let(::normalizeWorkspacePath) == target
    }
}

/**
 * 可见工作区列表：
 * - 侧栏 / 选择器在已拉到服务端注册表后：[requireRegistered]=true，只显示注册路径（对齐 Web）。
 * - 注册表尚未就绪时：可合并会话 cwd 作为临时回退。
 * 均排除本地已删与非用户目录。
 */
fun visibleUserWorkspaces(
    sessionCwds: Collection<String?>,
    deletedWorkspaces: Set<String>,
    registeredPaths: Collection<String> = emptyList(),
    requireRegistered: Boolean = false,
): List<String> {
    val deleted = deletedWorkspaces.map(::normalizeWorkspacePath).toSet()
    val registered = registeredPaths
        .map(::normalizeWorkspacePath)
        .filter { it.isNotBlank() }
    val fromSessions = sessionCwds
        .mapNotNull { it?.let(::normalizeWorkspacePath) }
        .filter { it.isNotBlank() }
    val candidates = if (requireRegistered) registered else registered + fromSessions
    return candidates
        .filter { it.isNotBlank() && isUserWorkspace(it) && it !in deleted }
        .distinct()
        .sorted()
}

/**
 * 侧栏工作区可见性：默认展示全部已注册工作区，包括尚无会话的新工作区。
 * 搜索或会话状态筛选时，才按匹配路径或可见会话收窄。
 */
fun visibleSidebarWorkspaces(
    knownWorkspaces: List<String>,
    workspacesWithVisibleSessions: Set<String>,
    searchQuery: String,
    sessionFilterActive: Boolean,
): List<String> {
    val needle = searchQuery.trim()
    return when {
        needle.isNotEmpty() -> knownWorkspaces.filter { path ->
            path.contains(needle, ignoreCase = true) ||
                path.substringAfterLast('/').contains(needle, ignoreCase = true) ||
                path in workspacesWithVisibleSessions
        }
        sessionFilterActive -> knownWorkspaces.filter { it in workspacesWithVisibleSessions }
        else -> knownWorkspaces
    }
}

/**
 * 会话是否应出现在侧栏：cwd 为空视为未绑定；已删或（注册表就绪且未注册）则隐藏。
 * 这样 Web 取消注册后，残留 session.cwd 不会再撑出工作区文件夹。
 */
fun isSessionWorkspaceVisible(
    cwd: String?,
    deletedWorkspaces: Set<String>,
    registeredPaths: Collection<String>,
    registryReady: Boolean,
): Boolean {
    if (cwd.isNullOrBlank()) return true
    val path = normalizeWorkspacePath(cwd)
    val deleted = deletedWorkspaces.map(::normalizeWorkspacePath).toSet()
    if (path in deleted) return false
    if (!registryReady) return isUserWorkspace(path)
    val registered = registeredPaths.map(::normalizeWorkspacePath).filter { it.isNotBlank() }.toSet()
    return path in registered && isUserWorkspace(path)
}

/**
 * 服务端注册表刷新后收敛本地 soft-hide：
 * - 仍在注册表中的路径 → 取消隐藏（Web/App 重新添加）
 * - 已不在注册表中的路径 → 保留隐藏（含本机乐观删除）
 */
fun reconcileDeletedWorkspaces(
    nextRegistered: Collection<String>,
    deletedWorkspaces: Set<String>,
): Set<String> {
    val next = nextRegistered.map(::normalizeWorkspacePath).filter { it.isNotBlank() }.toSet()
    return deletedWorkspaces
        .map(::normalizeWorkspacePath)
        .filter { it.isNotBlank() && it !in next }
        .toSet()
}

/**
 * S2 冷启动去重：回前台时是否跳过 `refreshSessions` / `refreshWorkspaces` / `refreshAppSettings`。
 *
 * 冷启动的 `bootstrap` + `getWorkspaces` 刚成功（[coldStartSyncAt] 为那次时间戳），或同步还在进行中
 * （[syncInFlight]）时，第一次 `ON_RESUME` 不再把同一份数据下载第二遍。
 */
fun shouldSkipResumeRefresh(
    now: Long,
    coldStartSyncAt: Long,
    syncInFlight: Boolean,
    windowMs: Long = COLD_START_REFRESH_WINDOW_MS,
): Boolean = syncInFlight || (coldStartSyncAt > 0L && now - coldStartSyncAt < windowMs)

/** 冷启动同步完成后，多久内回前台不再重复刷新。 */
const val COLD_START_REFRESH_WINDOW_MS = 10_000L

/**
 * S6：对话页当前是否可见。常驻侧栏（平板 / 宽屏）布局下对话页始终在场；
 * 手机布局下只有 `displayDest == chatDest` 时才算可见。首页阶段据此决定是否预加载。
 */
fun isChatVisible(persistentSidebar: Boolean, displayDest: String, chatDest: String): Boolean =
    persistentSidebar || displayDest == chatDest
