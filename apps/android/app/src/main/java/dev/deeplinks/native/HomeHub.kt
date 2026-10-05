package dev.deeplinks.native

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.remember
import dev.deeplinks.core.L
import dev.deeplinks.core.homeSearchContentMatches
import dev.deeplinks.core.homeSearchTitleMatches
import dev.deeplinks.native.ui.v4.DlIconButton
import dev.deeplinks.native.util.homeSearchGroups
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.homeAnswer
import dev.deeplinks.core.homeDiagnose
import dev.deeplinks.core.homeForkAsNew
import dev.deeplinks.core.homeShareSession
import dev.deeplinks.native.ui.v4.DlAction
import dev.deeplinks.native.ui.v4.DlBottomSheet
import dev.deeplinks.native.ui.v4.DlButton
import dev.deeplinks.native.ui.v4.DlButtonStyle
import dev.deeplinks.native.ui.v4.DlChip
import dev.deeplinks.native.ui.v4.DlInboxItem
import dev.deeplinks.native.ui.v4.DlListRow
import dev.deeplinks.native.ui.v4.DlRowTrailing
import dev.deeplinks.native.ui.v4.DlSectionHeader
import dev.deeplinks.native.util.homeTimeLabel
import dev.deeplinks.native.util.workspaceDisplayName
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import dev.deeplinks.native.ui.v4.DlPill
import dev.deeplinks.native.ui.v4.DlSize

/*
 * v4 首页收件箱（2.1–2.6）的积木。布局由 WorkspaceSidebar 组合；这里只管样子，状态全部由参数注入。
 * 首页以文件夹分组，底部固定搜索与新任务。
 */

/** 条目之间的细分隔线，左边与文字对齐。 */
@Composable
internal fun HomeDivider() {
    HorizontalDivider(Modifier.padding(start = DshSpace.s20), thickness = 1.dp, color = Dsh.outline)
}

/**
 * 2.1 收件箱条目：状态 · 工作区 + 时间；标题；进度 / 结果 / 问题预览；
 * [pending] 是当前会话里手机能处理的审批或提问：审批直接 拒绝 / 允许一次（离线时允许置灰），提问给「回答」进对话。
 */
@Composable
internal fun HomeInboxRow(
    session: MobileSession,
    pending: MobileMessage?,
    online: Boolean,
    goalSummary: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onReject: () -> Unit,
    onApprove: () -> Unit,
    compact: Boolean = false,
) {
    val s = DshS
    val texts = homeInboxTexts(session, pending, goalSummary, offline = !online)
    val actions = when (texts.pending) {
        HomePendingKind.Approval -> listOf(
            DlAction(s.reject, onReject),
            DlAction(s.allowOnce, onApprove, DlButtonStyle.Filled, enabled = online),
        )
        HomePendingKind.Question -> listOf(DlAction(s.homeAnswer, onClick))
        HomePendingKind.None -> emptyList()
    }
    DlInboxItem(
        modifier = if (compact) Modifier.padding(start = DshSpace.s32) else Modifier,
        compact = compact,
        workspace = if (compact) "" else texts.workspace,
        time = if (session.updatedAt > 0) homeTimeLabel(session.updatedAt) else "",
        title = displaySessionTitle(session.title),
        status = if (compact) {
            when {
                texts.pending != HomePendingKind.None || session.awaitingInput -> texts.status
                session.running -> s.homeRunning
                session.stoppedReason != null -> texts.status
                else -> null
            }
        } else texts.status,
        tone = texts.tone,
        preview = if (compact && texts.pending == HomePendingKind.None) null else texts.preview,
        command = texts.command,
        running = texts.running,
        actions = actions,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

/** 2.4 搜索结果条目：标题和摘要里的命中词用品牌色。 */
@Composable
internal fun HomeSearchRow(session: MobileSession, snippet: String?, needle: String, onClick: () -> Unit) {
    DlInboxItem(
        workspace = homeWorkspaceName(session).orEmpty(),
        time = if (session.updatedAt > 0) homeTimeLabel(session.updatedAt) else "",
        title = displaySessionTitle(session.title),
        preview = snippet?.let { "…$it…" },
        highlight = needle,
        onClick = onClick,
    )
}

/** 2.4 工作区筛选 chip：放在搜索结果上方，横向滚动。 */
@Composable
internal fun HomeWorkspaceChips(workspaces: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = DshSpace.s20, vertical = DshSpace.s8),
        horizontalArrangement = Arrangement.spacedBy(DshSpace.s8),
    ) {
        DlChip(DshS.homeAllWorkspaces, onClick = { onSelect(null) }, selected = selected == null)
        workspaces.forEach { cwd ->
            DlChip(workspaceDisplayName(cwd), onClick = { onSelect(cwd) }, selected = selected == cwd)
        }
    }
}

/** 2.6 长按会话：重命名 / 分叉为新会话 / 分享对话 / 归档 / 删除（红色，最后）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeSessionSheet(
    session: MobileSession,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onFork: () -> Unit,
    onShare: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    DlBottomSheet(onDismissRequest = onDismiss) {
        HomeSessionSheetContent(
            session = session,
            onRename = { onDismiss(); onRename() },
            onFork = { onDismiss(); onFork() },
            onShare = { onDismiss(); onShare() },
            onArchive = { onDismiss(); onArchive() },
            onDelete = { onDismiss(); onDelete() },
        )
    }
}

@Composable
internal fun HomeSessionSheetContent(
    session: MobileSession,
    onRename: () -> Unit,
    onFork: () -> Unit,
    onShare: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val s = DshS
    Column(Modifier.fillMaxWidth()) {
        Text(
            listOfNotNull(displaySessionTitle(session.title), homeWorkspaceName(session)).joinToString(" · "),
            style = DshType.supporting,
            color = Dsh.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = DshSpace.s24, vertical = DshSpace.s8),
        )
        DlListRow(title = s.rename, leading = EditOutline16, onClick = onRename)
        DlListRow(title = s.homeForkAsNew, leading = BranchOutline16, onClick = onFork)
        DlListRow(title = s.homeShareSession, leading = ShareOutline16, onClick = onShare)
        DlListRow(title = s.archiveSession, leading = ArchiveBoxOutline16, onClick = onArchive)
        DlListRow(title = s.deleteSession, leading = TrashOutline16, danger = true, onClick = onDelete)
    }
}

/** 2.3 离线横幅：中性底色，只有图标是红色；列表保留最后看到的状态。 */
@Composable
internal fun HomeOfflineBanner(
    hostName: String,
    sinceLabel: String?,
    onRetry: () -> Unit,
    onDiagnose: () -> Unit,
) {
    val s = DshS
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpace.s12, vertical = DshSpace.s8)
            .background(Dsh.surface1, RoundedCornerShape(DshRadius.block))
            .padding(start = DshSpace.s16, end = DshSpace.s16, top = DshSpace.s16, bottom = DshSpace.s8),
        horizontalArrangement = Arrangement.spacedBy(DshSpace.s12),
    ) {
        Icon(CloudOffOutline16, contentDescription = null, tint = Dsh.err, modifier = Modifier.size(DshIconSize.md))
        Column(Modifier.weight(1f)) {
            Text(s.homeOfflineUnreachable.format(hostName.ifBlank { s.deviceAndPairing }), style = DshType.bodyStrong, color = Dsh.labelPrimary)
            if (sinceLabel != null) {
                Text(s.homeOfflineHint.format(sinceLabel), style = DshType.supporting, color = Dsh.labelSecondary)
            }
            Row(Modifier.padding(top = DshSpace.s4), horizontalArrangement = Arrangement.spacedBy(DshSpace.s8)) {
                DlButton(DlAction(s.retry, onRetry, DlButtonStyle.Text), compact = true)
                DlButton(DlAction(s.homeDiagnose, onDiagnose, DlButtonStyle.Text), compact = true)
            }
        }
    }
}

/** 2.2 空态：图标 + 一句话，下面「从一件事开始」三条普通列表行。 */
@Composable
internal fun HomeEmptyStarters(onPick: (String) -> Unit) {
    val s = DshS
    Column(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = DshSpace.s24, end = DshSpace.s24, top = DshSpace.s32 + DshSpace.s32, bottom = DshSpace.s32 + DshSpace.s32),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DshSpace.s8),
        ) {
            Icon(CheckOutline16, contentDescription = null, tint = Dsh.ok, modifier = Modifier.size(DshSpace.s32))
            Text(s.homeEmptyTitle, style = DshType.titleLarge, color = Dsh.labelPrimary, textAlign = TextAlign.Center)
            Text(s.homeEmptyHint, style = DshType.supporting, color = Dsh.labelSecondary, textAlign = TextAlign.Center)
        }
        DlSectionHeader(s.homeStartFrom)
        val starters = listOf(s.homeStarterOrganize, s.homeStarterTest, s.homeStarterDiff)
        starters.forEachIndexed { index, starter ->
            if (index > 0) HomeDivider()
            DlListRow(title = starter, trailing = DlRowTrailing.Chevron, onClick = { onPick(starter) })
        }
    }
}

/** 2.1 底部：左边「搜索会话」胶囊（入口，点开 2.4），右边同色圆钮新建。没有分隔线，浮在列表上。 */
@Composable
internal fun HomeBottomBar(online: Boolean, onSearch: () -> Unit, onCreate: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Dsh.bgBase).padding(horizontal = DshSpace.s16, vertical = DshSpace.s8),
        horizontalArrangement = Arrangement.spacedBy(DshSpace.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f)
                .height(DlSize.button)
                .clip(DlPill)
                .background(Dsh.surface1)
                .clickable(role = Role.Button, onClick = onSearch)
                .padding(horizontal = DshSpace.s16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(SearchOutline16, contentDescription = null, tint = Dsh.labelSecondary, modifier = Modifier.size(DshIconSize.md))
            Spacer(Modifier.width(DshSpace.s12))
            Text(DshS.searchSessions, style = DshType.body, color = Dsh.labelSecondary, maxLines = 1)
        }
        Box(
            Modifier.size(DlSize.button)
                .clip(DlPill)
                .background(Dsh.surface1)
                .clickable(enabled = online, role = Role.Button, onClickLabel = DshS.homeNewTask, onClick = onCreate),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                EditOutline16,
                contentDescription = DshS.homeNewTask,
                tint = if (online) Dsh.labelPrimary else Dsh.tertiaryText,
                modifier = Modifier.size(DshIconSize.md),
            )
        }
    }
}

/** 2.4 搜索页：返回 + 搜索框，工作区 chip，结果按 标题匹配 / 内容匹配 分组。 */
@Composable
internal fun HomeSearchPage(
    query: String,
    needle: String,
    loading: Boolean,
    scoped: List<MobileSession>,
    searchResults: List<MobileSearchResult>,
    workspaces: List<String>,
    activeWorkspace: String?,
    onSelectWorkspace: (String?) -> Unit,
    onBack: () -> Unit,
    actions: WorkspaceSidebarActions,
    statusItems: LazyListScope.(Boolean) -> Unit,
) {
    val groups = remember(scoped, needle, searchResults) {
        homeSearchGroups(scoped, needle, searchResults.associate { it.sessionId to it.snippet })
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = DshSpace.s4, end = DshSpace.s16, top = DshSpace.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DlIconButton(ArrowLeftOutline16, DshS.back, onBack)
            Spacer(Modifier.width(DshSpace.s4))
            SidebarSearchField(
                value = query,
                onValueChange = actions.onSearchQueryChange,
                onClear = { actions.onClearSearch() },
                loading = loading,
                modifier = Modifier.weight(1f),
            )
        }
        HomeWorkspaceChips(workspaces = workspaces, selected = activeWorkspace, onSelect = onSelectWorkspace)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = DshSpace.s32)) {
            statusItems(groups.titleMatches.isEmpty() && groups.contentMatches.isEmpty())
            if (groups.titleMatches.isNotEmpty()) {
                item(key = "search-title-header") {
                    DlSectionHeader(if (needle.isEmpty()) L.homeRecent else L.homeSearchTitleMatches)
                }
                groups.titleMatches.forEachIndexed { index, s ->
                    item(key = "search-title-${s.sessionId}") {
                        Column {
                            if (index > 0) HomeDivider()
                            HomeSearchRow(s, snippet = null, needle = needle, onClick = { actions.onSelectSession(s.sessionId) })
                        }
                    }
                }
            }
            if (groups.contentMatches.isNotEmpty()) {
                item(key = "search-content-header") { DlSectionHeader(L.homeSearchContentMatches) }
                groups.contentMatches.forEachIndexed { index, (s, snippet) ->
                    item(key = "search-content-${s.sessionId}") {
                        Column {
                            if (index > 0) HomeDivider()
                            HomeSearchRow(s, snippet = snippet, needle = needle, onClick = { actions.onSelectSession(s.sessionId) })
                        }
                    }
                }
            }
        }
    }
}
