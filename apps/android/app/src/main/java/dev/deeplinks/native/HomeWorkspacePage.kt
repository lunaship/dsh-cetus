package dev.deeplinks.native

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.deeplinks.core.L
import dev.deeplinks.native.util.HostConnectivity
import dev.deeplinks.native.util.HomeWorkspaceGroup
import dev.deeplinks.native.util.draftWorkspaceChipLabels
import dev.deeplinks.native.util.WorkspacePrefs
import androidx.compose.ui.platform.LocalContext
import dev.deeplinks.native.ui.v4.DlWorkspaceRow
import dev.deeplinks.native.ui.v4.DlSectionHeader
import dev.deeplinks.native.ui.v4.DlListRow
import dev.deeplinks.core.homeWorkspaceSection
import dev.deeplinks.core.homeShowAllCount
import dev.deeplinks.core.homeShowFewer
import dev.deeplinks.native.util.sessionMillis

/** 每个文件夹默认显示的会话条数；超出的折进「显示全部 N 个」。 */
internal const val HOME_FOLDER_PREVIEW_ROWS = 3

/**
 * 2.1–2.3：居中电脑名顶栏 + 「等你处理」置顶 + 文件夹分组 + 底部搜索 / 新任务。
 * 等你处理的会话只在置顶区出现（跨工作区，所以保留工作区名）；文件夹里不再重复，收起后组头仍带计数。
 */
@Composable
internal fun HomeWorkspacePage(
    groups: List<HomeWorkspaceGroup>,
    hostIdentity: String,
    allSessions: List<MobileSession>,
    currentSessionId: String?,
    hostName: String,
    online: Boolean,
    offlineSinceLabel: String?,
    pending: MobileMessage?,
    goalSummaries: Map<String, String>,
    onAnswerApproval: (String, String, (Boolean) -> Unit) -> Unit,
    onPickStarter: (String) -> Unit,
    onOpenComputer: () -> Unit,
    onLongPress: (MobileSession) -> Unit,
    actions: WorkspaceSidebarActions,
    onSearch: () -> Unit,
    statusItems: LazyListScope.(Boolean) -> Unit,
    viaRemote: Boolean = false,
) {
    val context = LocalContext.current
    val prefs = remember(context) { WorkspacePrefs(context) }
    var collapsedPaths by remember(hostIdentity) { mutableStateOf(prefs.homeCollapsedGroups(hostIdentity)) }
    val labels = draftWorkspaceChipLabels(groups.mapNotNull { it.path })
    val labelByPath = groups.mapNotNull { it.path }.zip(labels).toMap()
    var showAll by rememberSaveable(hostIdentity) { mutableStateOf(emptyList<String>()) }
    var sheetPath by remember { mutableStateOf<String?>(null) }
    val awaiting = groups.flatMap { it.sessions }.filter { it.awaitingInput }
        .distinctBy { it.sessionId }.sortedByDescending { sessionMillis(it.updatedAt) }
    val inboxRow: @Composable (MobileSession) -> Unit = { session ->
        val rowPending = pending?.takeIf { session.sessionId == currentSessionId }
        HomeInboxRow(
            session = session.copy(subagentCount = runningSubagentCount(allSessions, session.sessionId).takeIf { it > 0 }),
            pending = rowPending,
            online = online,
            goalSummary = goalSummaries[session.sessionId],
            onClick = { actions.onSelectSession(session.sessionId) },
            onLongClick = { onLongPress(session) },
            onReject = { rowPending?.approvalId?.let { onAnswerApproval(it, "rejected") {} } },
            onApprove = { rowPending?.approvalId?.let { onAnswerApproval(it, "allowed-once") {} } },
        )
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            HomeTopBar(
                hostName = hostName,
                online = online,
                viaRemote = viaRemote,
                offlineSinceLabel = offlineSinceLabel,
                onOpenComputer = onOpenComputer,
                onOpenSettings = { actions.onOpenSettings() },
            )
            HomeCrashBanner()
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = DshSpace.s16),
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
                item(key = "home-balance") { HomeBalanceNotice(onOpenSettings = { actions.onOpenSettings() }) }
                statusItems(groups.isEmpty())
                if (awaiting.isNotEmpty()) {
                    item(key = "home-awaiting-heading") { DlSectionHeader(L.homeAwaiting, trailing = awaiting.size.toString()) }
                    awaiting.forEach { session ->
                        item(key = "home-awaiting-${session.sessionId}") { inboxRow(session) }
                    }
                }
                item(key = "home-workspaces-heading") {
                    HomeWorkspacesTitle(L.homeWorkspaceSection)
                }
                if (groups.isEmpty()) {
                    item(key = "home-empty") { HomeEmptyStarters(onPick = onPickStarter) }
                }
                groups.forEach { group ->
                    val expanded = group.key !in collapsedPaths
                    item(key = "folder-${group.key}") {
                        DlWorkspaceRow(
                            title = group.path?.let { labelByPath.getValue(it) } ?: L.ungrouped,
                            expanded = expanded,
                            online = online,
                            onToggle = {
                                collapsedPaths = if (expanded) collapsedPaths + group.key else collapsedPaths - group.key
                                prefs.saveHomeCollapsedGroups(hostIdentity, collapsedPaths)
                            },
                            onCreate = { actions.onCreateSessionIn(group.path) },
                            onLongClick = group.path?.let { path -> { sheetPath = path } },
                        )
                    }
                    if (expanded) {
                        if (group.sessions.isEmpty()) {
                            item(key = "empty-${group.key}") {
                                DlListRow(
                                    title = L.homeWorkspaceEmptyTitle,
                                    subtitle = L.homeWorkspaceEmptyHint,
                                    modifier = Modifier.padding(start = DshSpace.s32),
                                    onClick = if (online) ({ actions.onCreateSessionIn(group.path) }) else null,
                                )
                            }
                        }
                        val rest = group.sessions.filterNot { it.awaitingInput }
                        val all = group.key in showAll
                        val shown = if (all) rest else rest.take(HOME_FOLDER_PREVIEW_ROWS)
                        shown.forEach { session ->
                            item(key = "home-row-${session.sessionId}") {
                                HomeFolderSessionRow(
                                    session = session,
                                    online = online,
                                    goalSummary = goalSummaries[session.sessionId],
                                    onClick = { actions.onSelectSession(session.sessionId) },
                                    onLongClick = { onLongPress(session) },
                                )
                            }
                        }
                        if (rest.size > HOME_FOLDER_PREVIEW_ROWS) {
                            item(key = "more-${group.key}") {
                                HomeFolderMoreRow(
                                    label = if (all) L.homeShowFewer else L.homeShowAllCount.format(rest.size),
                                    onClick = { showAll = if (all) showAll - group.key else showAll + group.key },
                                )
                            }
                        }
                    }
                }
            }
            HomeBottomBar(
                online = online,
                onSearch = onSearch,
                onCreate = actions.onNewSession,
            )
        }
    }
    sheetPath?.let { path ->
        HomeWorkspaceSheet(
            label = labelByPath[path] ?: path,
            online = online,
            onDismiss = { sheetPath = null },
            onNewTask = { actions.onCreateSessionIn(path) },
            onDelete = { actions.onDeleteWorkspace(path) },
        )
    }
}
