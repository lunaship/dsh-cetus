package dev.deeplinks.native

import dev.deeplinks.core.DshS
import dev.deeplinks.native.ui.DshEmptyState
import dev.deeplinks.native.util.homeSections
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.L
import dev.deeplinks.native.util.SessionListKind
import dev.deeplinks.native.util.WorkspaceAccount
import dev.deeplinks.native.util.sessionListKind
import dev.deeplinks.native.util.sessionShowsRefreshBanner
import dev.deeplinks.native.util.visibleSidebarWorkspaces
import dev.deeplinks.native.util.visibleUserWorkspaces
import dev.deeplinks.native.util.workspaceGroupKey
import dev.deeplinks.core.DshType

/** 侧栏回调集合（对齐 [ChatFeedActions] 模式：状态由参数注入，动作由此承载）。 */
internal class WorkspaceSidebarActions(
    val onOpenDevice: () -> Unit,
    val onNewSession: () -> Unit,
    val onSelectSession: (String) -> Unit,
    val onRenameSession: (MobileSession) -> Unit,
    val onArchiveSession: (MobileSession) -> Unit,
    val onDeleteSession: (MobileSession) -> Unit,
    val onForkSession: (String) -> Unit,
    val onCreateSessionIn: (String?) -> Unit,
    val onDeleteWorkspace: (String) -> Unit,
    val onToggleSearch: () -> Unit,
    val onSearchQueryChange: (String) -> Unit,
    val onClearSearch: () -> Unit,
    val onRetrySearch: () -> Unit,
    val onRetrySessions: () -> Unit,
    val onAddWorkspace: () -> Unit,
    val onOpenSettings: () -> Unit,
)

@Composable
internal fun WorkspaceSidebar(
    sessions: List<MobileSession>,
    archivedIds: Set<String>,
    deletedIds: Set<String>,
    currentSessionId: String?,
    searchQuery: String,
    searchState: SearchUiState,
    searchResults: List<MobileSearchResult>,
    sidebarSearchOpen: Boolean,
    workspaceAccounts: List<WorkspaceAccount>,
    deletedWorkspaces: Set<String>,
    workspaceRegistry: List<String>,
    workspaceRegistryReady: Boolean,
    sessionsInitialLoad: Boolean,
    sessionsLoadError: String?,
    hostName: String,
    selectedWorkspace: String?,
    onSelectWorkspace: (String?) -> Unit,
    containerColor: Color,
    collapsed: Boolean = false,
    goalSummaries: Map<String, String> = emptyMap(),
    actions: WorkspaceSidebarActions,
) {
    if (collapsed) {
        WorkspaceSidebarCollapsed(actions = actions)
        return
    }

    // 首页 = 任务中心：顶栏（电脑 · 搜索 · 设置）→ 工作区筛选条 → 按状态分区的会话 → 底部「开始新任务」
    Column(
        Modifier
            .fillMaxSize()
            .background(containerColor)
            .padding(top = 4.dp)
    ) {
        HomeHeader(
            hostName = hostName,
            searchActive = sidebarSearchOpen || searchQuery.isNotBlank(),
            onOpenDevice = { actions.onOpenDevice() },
            onToggleSearch = { actions.onToggleSearch() },
            onOpenSettings = { actions.onOpenSettings() },
        )

        AnimatedVisibility(
            visible = sidebarSearchOpen,
            enter = fadeIn(tween(motionDuration(150))) + expandVertically(tween(motionDuration(180), easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(motionDuration(100))) + shrinkVertically(tween(motionDuration(150), easing = FastOutSlowInEasing)),
        ) {
            Box(Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                SidebarSearchField(
                    value = searchQuery,
                    onValueChange = actions.onSearchQueryChange,
                    onClear = { actions.onClearSearch() },
                    loading = searchState is SearchUiState.Loading,
                )
            }
        }

        val searchNeedle = searchQuery.trim()
        val visibleCandidates = remember(sessions, archivedIds, deletedIds, searchNeedle, searchResults) {
            filterSidebarSessions(
                sessions = sessions,
                archivedIds = archivedIds,
                deletedIds = deletedIds,
                searchNeedle = searchNeedle,
                searchResultIds = searchResults.map { it.sessionId },
                nowMillis = System.currentTimeMillis(),
            )
        }
        // 注册表就绪后以已注册工作区为准；无会话的新工作区也必须能被选中
        val knownWorkspaces = remember(visibleCandidates, deletedWorkspaces, workspaceRegistry, workspaceRegistryReady) {
            visibleUserWorkspaces(
                sessionCwds = visibleCandidates.map { it.cwd },
                deletedWorkspaces = deletedWorkspaces,
                registeredPaths = workspaceRegistry,
                requireRegistered = workspaceRegistryReady,
            )
        }
        val activeWorkspace = selectedWorkspace?.takeIf { it in knownWorkspaces }
        if (knownWorkspaces.isNotEmpty()) {
            WorkspaceChips(
                workspaces = knownWorkspaces,
                selected = activeWorkspace,
                onSelect = onSelectWorkspace,
                onAddWorkspace = { actions.onAddWorkspace() },
                onCreateSessionIn = { actions.onCreateSessionIn(it) },
                onDeleteWorkspace = { actions.onDeleteWorkspace(it) },
            )
        }
        val sections = remember(visibleCandidates, activeWorkspace, workspaceAccounts, deletedWorkspaces) {
            val scoped = if (activeWorkspace == null) {
                visibleCandidates
            } else {
                visibleCandidates.filter { workspaceGroupKey(it.sessionId, workspaceAccounts, deletedWorkspaces) == activeWorkspace }
            }
            homeSections(scoped, System.currentTimeMillis())
        }

        val sessionKind = sessionListKind(
            hasSessions = sessions.isNotEmpty(),
            initialLoad = sessionsInitialLoad,
            hasError = sessionsLoadError != null,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (searchNeedle.isNotEmpty() && searchShowsDegradedHint(searchState)) {
                item(key = "sidebar-search-degraded") {
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        SearchStatusBanner(
                            message = L.fullTextSearchUnavailable,
                            onRetry = { actions.onRetrySearch() },
                        )
                    }
                }
            }
            if (searchNeedle.isNotEmpty() && sections.isEmpty()) {
                item(key = "sidebar-search-empty") {
                    val searchError = searchState as? SearchUiState.Error
                    if (searchError != null) {
                        ChatHistoryError(
                            title = L.searchFailed,
                            message = searchError.message,
                            onRetry = { actions.onRetrySearch() },
                        )
                    } else {
                        Text(
                            if (searchState is SearchUiState.Loading) L.searching else L.noMatchingSessions,
                            color = Dsh.labelTertiary,
                            style = DshType.body,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
                        )
                    }
                }
            } else if (sessionKind == SessionListKind.Loading) {
                item(key = "sidebar-sessions-loading") {
                    Text(
                        L.loading,
                        color = Dsh.labelTertiary,
                        style = DshType.body,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
                    )
                }
            } else if (sessionKind == SessionListKind.Error) {
                item(key = "sidebar-sessions-error") {
                    ChatHistoryError(
                        title = L.loadSessionListFailed,
                        message = sessionsLoadError,
                        onRetry = { actions.onRetrySessions() },
                    )
                }
            } else {
                if (sessionShowsRefreshBanner(sessions.isNotEmpty(), sessionsLoadError != null)) {
                    item(key = "sidebar-sessions-refresh") {
                        LoadOlderRow(
                            loading = false,
                            failed = true,
                            failedMessage = sessionsLoadError,
                            onClick = { actions.onRetrySessions() },
                        )
                    }
                }
                if (sections.isEmpty()) {
                    item(key = "home-empty") {
                        DshEmptyState(
                            title = DshS.homeEmptyTitle,
                            message = DshS.homeEmptyHint,
                            compact = true,
                            modifier = Modifier.padding(top = 32.dp),
                        )
                    }
                }
                sections.forEach { (section, rows) ->
                    item(key = "home-section-${section.name}") {
                        HomeSectionHeader(section = section)
                    }
                    rows.forEach { s ->
                        item(key = "home-session-${s.sessionId}") {
                            Box(Modifier.animateItem()) {
                                SessionRowItem(
                                    session = s,
                                    isSelected = s.sessionId == currentSessionId,
                                    onClick = { actions.onSelectSession(s.sessionId) },
                                    onRename = { actions.onRenameSession(s) },
                                    onArchive = { actions.onArchiveSession(s) },
                                    onDelete = { actions.onDeleteSession(s) },
                                    onFork = { actions.onForkSession(s.sessionId) },
                                    goalSummary = goalSummaries[s.sessionId],
                                    containerColor = containerColor,
                                )
                            }
                        }
                    }
                }
                item(key = "home-bottom-space") { Spacer(Modifier.height(8.dp)) }
            }
        }

        HomeNewTaskBar(
            workspaceName = activeWorkspace?.substringAfterLast('/'),
            onClick = {
                val ws = activeWorkspace
                if (ws != null) actions.onCreateSessionIn(ws) else actions.onNewSession()
            },
        )
    }
}
