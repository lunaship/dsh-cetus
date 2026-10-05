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

/** 2.1–2.3：大标题顶栏 + 文件夹分组 + 底部搜索 / 新任务。 */
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
) {
    val context = LocalContext.current
    val prefs = remember(context) { WorkspacePrefs(context) }
    var collapsedPaths by remember(hostIdentity) { mutableStateOf(prefs.homeCollapsedGroups(hostIdentity)) }
    val labels = draftWorkspaceChipLabels(groups.mapNotNull { it.path })
    val labelByPath = groups.mapNotNull { it.path }.zip(labels).toMap()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            HomeHeader(
                hostName = hostName,
                online = online,
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
                item(key = "home-workspaces-heading") {
                    DlSectionHeader(L.homeWorkspaceSection)
                }
                if (groups.isEmpty()) {
                    item(key = "home-empty") { HomeEmptyStarters(onPick = onPickStarter) }
                }
                groups.forEach { group ->
                    val expanded = group.key !in collapsedPaths
                    item(key = "folder-${group.key}") {
                        DlWorkspaceRow(
                            title = group.path?.let { labelByPath.getValue(it) } ?: L.ungrouped,
                            count = group.sessions.size,
                            expanded = expanded,
                            awaitingCount = group.awaitingCount,
                            runningCount = group.runningCount,
                            online = online,
                            onToggle = {
                                collapsedPaths = if (expanded) collapsedPaths + group.key else collapsedPaths - group.key
                                prefs.saveHomeCollapsedGroups(hostIdentity, collapsedPaths)
                            },
                            onCreate = { actions.onCreateSessionIn(group.path) },
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
                        group.sessions.forEach { session ->
                            item(key = "home-row-${session.sessionId}") {
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
                                    compact = true,
                                )
                            }
                        }
                    }
                }
                item(key = "home-add-workspace") {
                    DlListRow(title = L.addWorkspace, leading = PlusOutline16, onClick = actions.onAddWorkspace)
                }
            }
            HomeBottomBar(
                online = online,
                onSearch = onSearch,
                onCreate = actions.onNewSession,
            )
        }

    }
}

