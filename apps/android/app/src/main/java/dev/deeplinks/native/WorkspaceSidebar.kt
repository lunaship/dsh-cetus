package dev.deeplinks.native

import dev.deeplinks.core.DshS
import dev.deeplinks.native.ui.DshEmptyState
import dev.deeplinks.native.util.homeTimeLabel
import dev.deeplinks.native.util.HomeSection
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
import androidx.compose.ui.draw.alpha
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
import dev.deeplinks.native.util.sessionsInWorkspace
import dev.deeplinks.native.util.HostConnectivity
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
    online: Boolean,
    viaRemote: Boolean,
    offlineSinceLabel: String?,
    selectedWorkspace: String?,
    onSelectWorkspace: (String?) -> Unit,
    onOpenArchived: () -> Unit,
    onPickStarter: (String) -> Unit,
    /** 当前会话里由手机接管的 pending 审批（方案 D1-A）：首页只对它有拒绝/批准。 */
    activeApproval: MobileMessage?,
    onAnswerApproval: (String, String, (Boolean) -> Unit) -> Unit,
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
            .padding(top = DshSpace.s4)
    ) {
        HomeHeader(
            hostName = hostName,
            online = online,
            viaRemote = viaRemote,
            offlineSinceLabel = offlineSinceLabel,
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
            Box(Modifier.padding(top = DshSpace.s4, bottom = DshSpace.s4)) {
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
        val scoped = remember(visibleCandidates, activeWorkspace, workspaceAccounts, deletedWorkspaces) {
            if (activeWorkspace == null) {
                visibleCandidates
            } else {
                sessionsInWorkspace(visibleCandidates, activeWorkspace, workspaceAccounts, deletedWorkspaces)
            }
        }
        // 概况行：等你处理 / 在跑。筛选到某个工作区时结果为空也保留（W2），否则回不到全部工作区。
        if (online && (scoped.isNotEmpty() || activeWorkspace != null)) {
            HomeSummaryRow(
                awaitingCount = scoped.count { it.awaitingInput },
                runningCount = scoped.count { it.running && !it.awaitingInput },
                workspaces = knownWorkspaces,
                selected = activeWorkspace,
                onSelect = onSelectWorkspace,
                onAddWorkspace = { actions.onAddWorkspace() },
                onDeleteWorkspace = { actions.onDeleteWorkspace(it) },
                onOpenArchived = onOpenArchived,
            )
        } else if (!online) {
            HomeOfflineCard(
                hostName = hostName,
                sinceLabel = offlineSinceLabel,
                // R4：点「重试」先让探测循环立刻重探，否则顶栏会继续显示离线。
                onRetry = { HostConnectivity.requestProbe(); actions.onRetrySessions() },
                onOpenConnectionMode = { actions.onOpenDevice() },
            )
        }
        val sections = remember(scoped) { homeSections(scoped) }

        val sessionKind = sessionListKind(
            hasSessions = sessions.isNotEmpty(),
            initialLoad = sessionsInitialLoad,
            hasError = sessionsLoadError != null,
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                // 离线时列表整体压到 72%：看得见但明确是缓存状态（稿 08）
                .alpha(if (online) 1f else 0.72f),
            verticalArrangement = Arrangement.spacedBy(DshSpace.s2)
        ) {
            if (searchNeedle.isNotEmpty() && searchShowsDegradedHint(searchState)) {
                item(key = "sidebar-search-degraded") {
                    Box(Modifier.padding(horizontal = DshSpace.s12, vertical = DshSpace.s4)) {
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
                            modifier = Modifier.padding(horizontal = DshSpace.s20, vertical = DshSpace.s20)
                        )
                    }
                }
            } else if (sessionKind == SessionListKind.Loading) {
                item(key = "sidebar-sessions-loading") {
                    Text(
                        L.loading,
                        color = Dsh.labelTertiary,
                        style = DshType.body,
                        modifier = Modifier.padding(horizontal = DshSpace.s20, vertical = DshSpace.s20)
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
                if (activeWorkspace != null && scoped.isEmpty()) {
                    item(key = "home-workspace-empty") {
                        HomeWorkspaceEmpty({ actions.onCreateSessionIn(activeWorkspace) }, { onSelectWorkspace(null) })
                    }
                } else if (sections.isEmpty()) {
                    item(key = "home-empty") { HomeEmptyStarters(onPick = onPickStarter) }
                }
                sections.forEach { (section, rows) ->
                    item(key = "home-section-${section.name}") {
                        HomeSectionHeader(section = section)
                    }
                    rows.forEach { s ->
                        item(key = "home-session-${s.sessionId}") {
                            Box(Modifier.animateItem()) {
                                if (section == HomeSection.AWAITING &&
                                    activeApproval != null &&
                                    s.sessionId == currentSessionId
                                ) {
                                    HomeApprovalCard(
                                        title = displaySessionTitle(s.title),
                                        workspaceLabel = s.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() },
                                        timeLabel = if (s.updatedAt > 0) homeTimeLabel(s.updatedAt) else null,
                                        toolName = activeApproval.toolName,
                                        chipText = DshS.homeChipWaitingApproval,
                                        onReject = {
                                            activeApproval.approvalId?.let { onAnswerApproval(it, "rejected") {} }
                                        },
                                        onApprove = {
                                            activeApproval.approvalId?.let { onAnswerApproval(it, "allowed-once") {} }
                                        },
                                    )
                                    return@Box
                                }
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
                                    offline = !online,
                                )
                            }
                        }
                    }
                }
                item(key = "home-bottom-space") { Spacer(Modifier.height(DshSpace.s8)) }
            }
        }

        HomeNewTaskFab(
            enabled = online,
            onClick = {
                val ws = activeWorkspace
                if (ws != null) actions.onCreateSessionIn(ws) else actions.onNewSession()
            },
        )
    }
}
