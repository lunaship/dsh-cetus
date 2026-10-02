package dev.deeplinks.native

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.native.util.HostConnectivity
import dev.deeplinks.native.util.SessionListKind
import dev.deeplinks.native.util.WorkspaceAccount
import dev.deeplinks.native.util.homeSections
import dev.deeplinks.native.util.sessionListKind
import dev.deeplinks.native.util.sessionShowsRefreshBanner
import dev.deeplinks.native.util.sessionsInWorkspace
import dev.deeplinks.native.util.visibleUserWorkspaces
import dev.deeplinks.native.util.workspaceDisplayName

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
    val onShareSession: (String) -> Unit = {},
)

/**
 * v4 首页收件箱（2.1–2.6）。手机全屏首页与平板常驻侧栏共用。
 * 顶栏和列表平铺在画布上；搜索打开时整页换成 2.4 搜索页；长按条目出 2.6，点电脑状态行出 2.5。
 */
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
    /** 当前会话里手机能处理的审批或提问（[pendingDecision]）：首页只对它给内联按钮。 */
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
    // 搜索关着时首页不按查询过滤（查询留在搜索页里）
    val searchNeedle = if (sidebarSearchOpen) searchQuery.trim() else ""
    val visibleCandidates = remember(sessions, archivedIds, deletedIds, searchNeedle, searchResults) {
        filterSidebarSessions(
            sessions = sessions, archivedIds = archivedIds, deletedIds = deletedIds,
            searchNeedle = searchNeedle, searchResultIds = searchResults.map { it.sessionId },
            nowMillis = System.currentTimeMillis(),
        )
    }
    val knownWorkspaces = remember(visibleCandidates, deletedWorkspaces, workspaceRegistry, workspaceRegistryReady) {
        visibleUserWorkspaces(
            sessionCwds = visibleCandidates.map { it.cwd }, deletedWorkspaces = deletedWorkspaces,
            registeredPaths = workspaceRegistry, requireRegistered = workspaceRegistryReady,
        )
    }
    val activeWorkspace = selectedWorkspace?.takeIf { it in knownWorkspaces }
    val scoped = remember(visibleCandidates, activeWorkspace, workspaceAccounts, deletedWorkspaces) {
        if (activeWorkspace == null) visibleCandidates
        else sessionsInWorkspace(visibleCandidates, activeWorkspace, workspaceAccounts, deletedWorkspaces)
    }
    val sessionKind = sessionListKind(
        hasSessions = sessions.isNotEmpty(),
        initialLoad = sessionsInitialLoad,
        hasError = sessionsLoadError != null,
    )
    var computerSheetOpen by remember { mutableStateOf(false) }
    var sheetSession by remember { mutableStateOf<MobileSession?>(null) }
    val closeSearch = { actions.onClearSearch(); actions.onToggleSearch() }
    BackHandler(enabled = sidebarSearchOpen, onBack = closeSearch)
    val statusItems: LazyListScope.(Boolean) -> Unit = { empty ->
        homeStatusItems(
            searchNeedle = searchNeedle, searchState = searchState, sectionsEmpty = empty,
            sessionKind = sessionKind, sessionsLoadError = sessionsLoadError, hasSessions = sessions.isNotEmpty(),
            onRetrySearch = { actions.onRetrySearch() }, onRetrySessions = { actions.onRetrySessions() },
        )
    }
    Box(Modifier.fillMaxSize().background(containerColor)) {
        if (sidebarSearchOpen) {
            HomeSearchPage(
                query = searchQuery,
                needle = searchNeedle,
                loading = searchState is SearchUiState.Loading,
                scoped = scoped,
                searchResults = searchResults,
                workspaces = knownWorkspaces,
                activeWorkspace = activeWorkspace,
                onSelectWorkspace = onSelectWorkspace,
                onBack = closeSearch,
                actions = actions,
                statusItems = statusItems,
            )
        } else {
            HomeInboxPage(
                scoped = scoped,
                allSessions = sessions,
                currentSessionId = currentSessionId,
                activeWorkspace = activeWorkspace,
                hostName = hostName,
                online = online,
                offlineSinceLabel = offlineSinceLabel,
                pending = activeApproval,
                goalSummaries = goalSummaries,
                onAnswerApproval = onAnswerApproval,
                onSelectWorkspace = onSelectWorkspace,
                onPickStarter = onPickStarter,
                onOpenComputer = { computerSheetOpen = true },
                onLongPress = { sheetSession = it },
                actions = actions,
                statusItems = statusItems,
            )
        }
    }
    if (computerSheetOpen) {
        HomeComputerSheet(
            hostName = hostName,
            online = online,
            viaRemote = viaRemote,
            offlineSinceLabel = offlineSinceLabel,
            workspaces = homeWorkspaceOptions(visibleCandidates, knownWorkspaces, workspaceAccounts, deletedWorkspaces),
            selected = activeWorkspace,
            onDismiss = { computerSheetOpen = false },
            onOpenDevice = { actions.onOpenDevice() },
            onSelectWorkspace = onSelectWorkspace,
            onAddWorkspace = { actions.onAddWorkspace() },
            onOpenArchived = onOpenArchived,
            onDeleteWorkspace = { actions.onDeleteWorkspace(it) },
        )
    }
    sheetSession?.let { target ->
        HomeSessionSheet(
            session = target,
            onDismiss = { sheetSession = null },
            onRename = { actions.onRenameSession(target) },
            onFork = { actions.onForkSession(target.sessionId) },
            onShare = { actions.onShareSession(target.sessionId) },
            onArchive = { actions.onArchiveSession(target) },
            onDelete = { actions.onDeleteSession(target) },
        )
    }
}

/** 2.5 的工作区单选：全部 + 各工作区，副标题是任务数。 */
private fun homeWorkspaceOptions(
    visible: List<MobileSession>,
    workspaces: List<String>,
    accounts: List<WorkspaceAccount>,
    deleted: Set<String>,
): List<HomeWorkspaceOption> =
    listOf(HomeWorkspaceOption(null, L.homeAllWorkspaces, visible.size)) +
        workspaces.map { cwd ->
            HomeWorkspaceOption(cwd, workspaceDisplayName(cwd), sessionsInWorkspace(visible, cwd, accounts, deleted).size)
        }

/** 2.1–2.3：大标题顶栏 + 平铺分组 + 右下新任务。 */
@Composable
private fun HomeInboxPage(
    scoped: List<MobileSession>,
    allSessions: List<MobileSession>,
    currentSessionId: String?,
    activeWorkspace: String?,
    hostName: String,
    online: Boolean,
    offlineSinceLabel: String?,
    pending: MobileMessage?,
    goalSummaries: Map<String, String>,
    onAnswerApproval: (String, String, (Boolean) -> Unit) -> Unit,
    onSelectWorkspace: (String?) -> Unit,
    onPickStarter: (String) -> Unit,
    onOpenComputer: () -> Unit,
    onLongPress: (MobileSession) -> Unit,
    actions: WorkspaceSidebarActions,
    statusItems: LazyListScope.(Boolean) -> Unit,
) {
    val sections = remember(scoped) { homeSections(scoped) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            HomeHeader(
                hostName = hostName,
                online = online,
                onOpenComputer = onOpenComputer,
                onOpenSearch = { actions.onToggleSearch() },
                onOpenSettings = { actions.onOpenSettings() },
            )
            HomeCrashBanner()
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = DshSpace.s32 + DshSpace.s32 + DshSpace.s32),
            ) {
                if (!online) {
                    item(key = "home-offline") {
                        HomeOfflineBanner(
                            hostName = hostName,
                            sinceLabel = offlineSinceLabel,
                            onRetry = { HostConnectivity.requestProbe(); actions.onRetrySessions() },
                            onDiagnose = { actions.onOpenDevice() },
                        )
                    }
                }
                item(key = "home-balance") { HomeBalanceBanner(onOpenSettings = { actions.onOpenSettings() }) }
                statusItems(sections.isEmpty())
                if (activeWorkspace != null) {
                    item(key = "home-workspace-filter-chip") {
                        HomeWorkspaceFilterChip(label = workspaceDisplayName(activeWorkspace), onClear = { onSelectWorkspace(null) })
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
                    item(key = "home-section-${section.name}") { HomeSectionHeader(section, rows.size) }
                    rows.forEachIndexed { index, s ->
                        item(key = "home-row-${s.sessionId}") {
                            Column(Modifier.animateItem()) {
                                if (index > 0) HomeDivider()
                                val rowPending = pending?.takeIf { s.sessionId == currentSessionId }
                                HomeInboxRow(
                                    session = s.copy(subagentCount = runningSubagentCount(allSessions, s.sessionId).takeIf { it > 0 }),
                                    pending = rowPending,
                                    online = online,
                                    goalSummary = goalSummaries[s.sessionId],
                                    onClick = { actions.onSelectSession(s.sessionId) },
                                    onLongClick = { onLongPress(s) },
                                    onReject = { rowPending?.approvalId?.let { onAnswerApproval(it, "rejected") {} } },
                                    onApprove = { rowPending?.approvalId?.let { onAnswerApproval(it, "allowed-once") {} } },
                                )
                            }
                        }
                    }
                }
            }
        }
        HomeNewTaskFab(
            enabled = online,
            onClick = { if (activeWorkspace != null) actions.onCreateSessionIn(activeWorkspace) else actions.onNewSession() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(DshSpace.s16),
        )
    }
}

/** 列表状态行：搜索降级提示、搜索空态 / 加载 / 错误、刷新横幅。 */
private fun LazyListScope.homeStatusItems(
    searchNeedle: String,
    searchState: SearchUiState,
    sectionsEmpty: Boolean,
    sessionKind: SessionListKind,
    sessionsLoadError: String?,
    hasSessions: Boolean,
    onRetrySearch: () -> Unit,
    onRetrySessions: () -> Unit,
) {
    if (searchNeedle.isNotEmpty() && searchShowsDegradedHint(searchState)) {
        item(key = "sidebar-search-degraded") {
            Box(Modifier.padding(horizontal = DshSpace.s12, vertical = DshSpace.s4)) {
                SearchStatusBanner(
                    message = L.fullTextSearchUnavailable,
                    onRetry = onRetrySearch,
                )
            }
        }
    }
    if (searchNeedle.isNotEmpty() && sectionsEmpty) {
        item(key = "sidebar-search-empty") {
            val searchError = searchState as? SearchUiState.Error
            if (searchError != null) {
                ChatHistoryError(
                    title = L.searchFailed,
                    message = searchError.message,
                    onRetry = onRetrySearch,
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
                onRetry = onRetrySessions,
            )
        }
    } else if (sessionShowsRefreshBanner(hasSessions, sessionsLoadError != null)) {
        item(key = "sidebar-sessions-refresh") {
            LoadOlderRow(
                loading = false,
                failed = true,
                failedMessage = sessionsLoadError,
                onClick = onRetrySessions,
            )
        }
    }
}
