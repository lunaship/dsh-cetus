package dev.deeplinks.native

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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.L
import dev.deeplinks.native.util.SessionListKind
import dev.deeplinks.native.util.WorkspaceAccount
import dev.deeplinks.native.util.sessionListKind
import dev.deeplinks.native.util.sessionShowsRefreshBanner
import dev.deeplinks.native.util.visibleUserWorkspaces
import dev.deeplinks.native.util.sessionsInWorkspace
import dev.deeplinks.native.util.HostConnectivity
import dev.deeplinks.native.util.workspaceDisplayName
import dev.deeplinks.core.DshType
import dev.deeplinks.native.ui.DshCardRows
import dev.deeplinks.native.ui.rememberDshTopFadeHeight
import dev.deeplinks.native.ui.DshEdgeFadeDefaults
import dev.deeplinks.native.ui.DshEdgeFades

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

    val searchNeedle = searchQuery.trim()
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
    val sections = remember(scoped) { homeSections(scoped) }

    val sessionKind = sessionListKind(
        hasSessions = sessions.isNotEmpty(),
        initialLoad = sessionsInitialLoad,
        hasError = sessionsLoadError != null,
    )
    // 第 5 步叠层（2026-10-02 L9 改造）：列表铺满，顶部 / 底部只有悬浮控件与边缘渐隐，
    // 不再画全宽玻璃条；顶部 / 底部高度实测回填给内容区做 contentPadding
    val homeListState = rememberLazyListState()
    val homeScrolled by remember { derivedStateOf { homeListState.canScrollBackward } }
    val homeNotAtBottom by remember { derivedStateOf { homeListState.canScrollForward } }
    val chrome = rememberOverlayChromeState()
    val topFadeHeight = rememberDshTopFadeHeight() + DshEdgeFadeDefaults.overhang
    val bottomFadeHeight = chrome.bottomDp() + DshSpace.s24
    Box(Modifier.fillMaxSize().background(containerColor)) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = homeListState,
                modifier = Modifier
                    .fillMaxSize().overlayBackdropSource(chrome)
                    .alpha(if (online) 1f else 0.72f),
                verticalArrangement = Arrangement.spacedBy(DshSpace.s2),
                contentPadding = PaddingValues(top = chrome.topDp(), bottom = chrome.bottomDp() + DshSpace.s16),
            ) {
                homeStatusItems(
                    searchNeedle = searchNeedle,
                    searchState = searchState,
                    sectionsEmpty = sections.isEmpty(),
                    sessionKind = sessionKind,
                    sessionsLoadError = sessionsLoadError,
                    hasSessions = sessions.isNotEmpty(),
                    onRetrySearch = { actions.onRetrySearch() },
                    onRetrySessions = { actions.onRetrySessions() },
                )
                // 已筛选工作区提示行（A02）：可 × 清除；未筛选不显示
                    if (activeWorkspace != null) {
                        item(key = "home-workspace-filter-chip") {
                            HomeWorkspaceFilterChip(
                                label = workspaceDisplayName(activeWorkspace),
                                onClear = { onSelectWorkspace(null) },
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
                        item(key = "home-card-${section.name}") {
                            Box(Modifier.animateItem()) {
                                Box(Modifier.padding(horizontal = DrawerEdgePadding)) {
                                    DshCardRows {
                                        rows.forEach { s ->
                                            if (section == HomeSection.AWAITING &&
                                                activeApproval != null &&
                                                s.sessionId == currentSessionId
                                            ) {
                                                // 稿 07：手机接管的审批展开成卡内的一行内容，不再单独成卡
                                                HomeApprovalCard(
                                                    title = displaySessionTitle(s.title),
                                                    workspaceLabel = s.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() },
                                                    timeLabel = if (s.updatedAt > 0) homeTimeLabel(s.updatedAt) else null,
                                                    toolName = activeApproval.toolName,
                                                    onReject = {
                                                        activeApproval.approvalId?.let { onAnswerApproval(it, "rejected") {} }
                                                    },
                                                    onApprove = {
                                                        activeApproval.approvalId?.let { onAnswerApproval(it, "allowed-once") {} }
                                                    },
                                                )
                                            } else {
                                                SessionRowItem(
                                                    session = s,
                                                    isSelected = s.sessionId == currentSessionId,
                                                    onClick = { actions.onSelectSession(s.sessionId) },
                                                    onRename = { actions.onRenameSession(s) },
                                                    onArchive = { actions.onArchiveSession(s) },
                                                    onDelete = { actions.onDeleteSession(s) },
                                                    onFork = { actions.onForkSession(s.sessionId) },
                                                    goalSummary = goalSummaries[s.sessionId],
                                                    offline = !online,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
            }

            // 顶部 / 底部渐隐（4.5.3；v3 渐进模糊）：内容滚到边缘下方才出现；不进采样源
            DshEdgeFades(topFadeHeight, bottomFadeHeight, homeScrolled, homeNotAtBottom, containerColor, chrome.backdrop)

            // 顶部悬浮区（无玻璃条）：顶栏胶囊 + 崩溃横幅 + 搜索框 + 离线卡
            HomeTopChrome(
                chrome = chrome,
                online = online,
                offlineSinceLabel = offlineSinceLabel,
                hostName = hostName,
                searchQuery = searchQuery,
                searchLoading = searchState is SearchUiState.Loading,
                sidebarSearchOpen = sidebarSearchOpen,
                knownWorkspaces = knownWorkspaces,
                activeWorkspace = activeWorkspace,
                onSelectWorkspace = onSelectWorkspace,
                onAddWorkspace = { actions.onAddWorkspace() },
                onDeleteWorkspace = { actions.onDeleteWorkspace(it) },
                onOpenArchived = onOpenArchived,
                onOpenSettings = { actions.onOpenSettings() },
                onSearchQueryChange = actions.onSearchQueryChange,
                onClearSearch = { actions.onClearSearch() },
                onRetrySessions = { HostConnectivity.requestProbe(); actions.onRetrySessions() },
                onOpenDevice = { actions.onOpenDevice() },
            )

            // 底部操作行（4.1）：搜索胶囊 + 圆形 +；高度实测回填
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .zIndex(1f)
                    .overlayBottomChrome(chrome)
                    .navigationBarsPadding(),
            ) {
                HomeBottomBar(
                    online = online,
                    onOpenSearch = { actions.onToggleSearch() },
                    onNewTask = {
                        val ws = activeWorkspace
                        if (ws != null) actions.onCreateSessionIn(ws) else actions.onNewSession()
                    },
                    backdrop = chrome.backdrop,
                )
            }
        }
    }
}

/** 顶部悬浮区（L9 无玻璃条）：顶栏胶囊 + 崩溃横幅 + 搜索框 + 离线卡；高度实测回填。 */
@Composable
private fun HomeTopChrome(
    chrome: OverlayChromeState,
    online: Boolean,
    offlineSinceLabel: String?,
    hostName: String,
    searchQuery: String,
    searchLoading: Boolean,
    sidebarSearchOpen: Boolean,
    knownWorkspaces: List<String>,
    activeWorkspace: String?,
    onSelectWorkspace: (String?) -> Unit,
    onAddWorkspace: () -> Unit,
    onDeleteWorkspace: (String) -> Unit,
    onOpenArchived: () -> Unit,
    onOpenSettings: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onRetrySessions: () -> Unit,
    onOpenDevice: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .zIndex(1f)
            .onSizeChanged { chrome.topPx = it.height },
    ) {
        Column(Modifier.padding(top = DshSpace.s4)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                HomeHeader(
                    online = online,
                    offlineSinceLabel = offlineSinceLabel,
                    workspaces = knownWorkspaces,
                    selectedWorkspace = activeWorkspace,
                    onSelectWorkspace = onSelectWorkspace,
                    onAddWorkspace = onAddWorkspace,
                    onDeleteWorkspace = onDeleteWorkspace,
                    onOpenArchived = onOpenArchived,
                    onOpenSettings = onOpenSettings,
                    backdrop = chrome.backdrop,
                )
                HomeCrashBanner()
            }
            AnimatedVisibility(
                visible = sidebarSearchOpen,
                enter = fadeIn(tween(motionDuration(150))) + expandVertically(tween(motionDuration(180), easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(motionDuration(100))) + shrinkVertically(tween(motionDuration(150), easing = FastOutSlowInEasing)),
            ) {
                Box(Modifier.padding(top = DshSpace.s4, bottom = DshSpace.s4)) {
                    SidebarSearchField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        onClear = onClearSearch,
                        loading = searchLoading,
                    )
                }
            }
            if (!online) {
                HomeOfflineCard(
                    hostName = hostName,
                    sinceLabel = offlineSinceLabel,
                    onRetry = onRetrySessions,
                    onOpenConnectionMode = onOpenDevice,
                )
            }
        }
    }
}

/** 列表状态行：搜索降级提示、搜索空态 / 加载 / 错误、刷新横幅。 */
private fun androidx.compose.foundation.lazy.LazyListScope.homeStatusItems(
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
