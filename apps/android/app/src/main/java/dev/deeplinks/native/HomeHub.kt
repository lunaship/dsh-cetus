package dev.deeplinks.native

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.native.ui.DshFloatingPill
import dev.deeplinks.native.ui.DshStatusChip
import dev.deeplinks.native.ui.DshGroupCard
import dev.deeplinks.native.ui.DshChipTone
import dev.deeplinks.native.ui.DshIconAction
import dev.deeplinks.native.ui.DshPillButton
import dev.deeplinks.native.ui.DshPillTone
import dev.deeplinks.native.ui.DshSectionLabel
import dev.deeplinks.native.util.HomeSection

/*
 * 首页（抽屉 / 平板侧栏）的积木（2026-09-28 重设计）：顶栏、概况行+工作区筛选、
 * 分区标题、离线卡、空态起手式。布局由 WorkspaceSidebar 组合；这里只管样子，状态全部由参数注入。
 *
 * 强调色只给需要你动手的动作；开关、选中态、分区标题都是中性灰。
 * 悬浮「新任务」是全 App 唯一带阴影的普通按钮（DshFloatingPill）。
 */

/** 首页顶栏里那枚 40dp 的电脑图标块（白底、圆角 container，不加描边）。 */
@Composable
private fun HostBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(DshRadius.container))
            .background(Dsh.bgCard),
        contentAlignment = Alignment.Center,
    ) {
        Icon(LaptopOutline16, contentDescription = null, tint = Dsh.labelPrimary, modifier = Modifier.size(18.dp))
    }
}

/** 在线点：在线实心 success；离线空心灰（稿 08 的「空心灰点」）。 */
@Composable
private fun StatusDot(online: Boolean) {
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(if (online) Dsh.success else Dsh.labelDimmed),
    )
}

/**
 * 顶栏：电脑图标块 + 电脑名（粗）⌄，下一行状态（在线 · 云端 · 31ms / 离线 · 10 分钟前在线）；
 * 右侧搜索与设置两个 44dp 圆钮。
 *
 * 离线时不给延迟数字（拿不到），改写成「离线 · N 分钟前在线」。
 */
@Composable
internal fun HomeHeader(
    hostName: String,
    online: Boolean,
    viaRemote: Boolean,
    latencyMs: Long?,
    offlineSinceLabel: String?,
    searchActive: Boolean,
    onOpenDevice: () -> Unit,
    onToggleSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val s = DshS
    val deviceLabel = hostName.ifBlank { s.deviceAndPairing }
    val status = if (online) {
        listOfNotNull(s.statusOnline, if (viaRemote) s.viaRemote else s.viaLan, latencyMs?.let { "${it}ms" })
            .joinToString(" · ")
    } else {
        offlineSinceLabel?.let { s.homeOfflineHeader.format(it) } ?: s.statusOffline
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DrawerEdgePadding, end = DrawerEdgePadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(DshRadius.container))
                    .clickable(role = Role.Button, onClickLabel = s.deviceAndPairing, onClick = onOpenDevice)
                    .semantics { heading() }
                    .padding(horizontal = DrawerInnerPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HostBadge()
                Spacer(Modifier.width(DshSpace.s12))
                Column(Modifier.weight(1f, fill = false)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            deviceLabel,
                            color = Dsh.labelPrimary,
                            style = DshType.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(DshSpace.s4))
                        Icon(
                            ChevronDownOutline14,
                            contentDescription = null,
                            tint = Dsh.labelTertiary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(online)
                        Spacer(Modifier.width(DshSpace.s6))
                        Text(
                            status,
                            color = Dsh.labelSecondary,
                            style = DshType.captionRelaxed,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        DshIconAction(
            icon = SearchOutline16,
            contentDescription = s.searchSessions,
            onClick = onToggleSearch,
            active = searchActive,
            size = 44.dp,
            iconSize = 18.dp,
        )
        DshIconAction(
            icon = SettingsOutline16,
            contentDescription = s.settingsTitle,
            onClick = onOpenSettings,
            size = 44.dp,
            iconSize = 18.dp,
        )
    }
}

/**
 * 首页审批卡（稿 01/07 · 方案 3.4 + D1-A）。
 *
 * 只在「这条审批由手机接管」时出现（App 正订阅该会话、requests 快照里有 pending）；
 * 其余等你处理的会话一律走 [HomeAwaitingRow] 的「在电脑上处理」样式。批准按钮是页面上
 * 唯一的实心强调色——它确实是「需要你动手」的动作。
 *
 * 命令文本：DSH 的审批请求不带工具参数（dsh-user-approval 明确不重复 presented tool call 的
 * arguments），所以这里按用户确认的「诚实降级」只写工具名。
 */
@Composable
internal fun HomeApprovalCard(
    title: String,
    workspaceLabel: String?,
    timeLabel: String?,
    toolName: String?,
    chipText: String,
    onReject: () -> Unit,
    onApprove: () -> Unit,
) {
    val s = DshS
    DshGroupCard(modifier = Modifier.padding(horizontal = DrawerEdgePadding + DshSpace.s6)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DshStatusChip(text = chipText, tone = DshChipTone.Approval)
            if (!workspaceLabel.isNullOrBlank()) {
                Spacer(Modifier.width(DshSpace.s8))
                Text(workspaceLabel, color = Dsh.labelSecondary, style = DshType.captionRelaxed, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            if (!timeLabel.isNullOrBlank()) {
                Text(timeLabel, color = Dsh.labelSecondary, style = DshType.captionRelaxed, maxLines = 1)
            }
        }
        Spacer(Modifier.size(DshSpace.s6))
        Text(
            title,
            color = Dsh.labelPrimary,
            style = DshType.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(DshSpace.s6))
        Text(s.homeWantsCommand, color = Dsh.labelSecondary, style = DshType.supporting)
        Spacer(Modifier.size(DshSpace.s6))
        Text(
            toolName ?: s.toolFallbackName,
            color = Dsh.labelPrimary,
            style = DshType.captionRelaxed,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DshRadius.container))
                .background(Dsh.bgCode)
                .padding(horizontal = DshSpace.s12, vertical = DshSpace.s8),
        )
        Spacer(Modifier.size(DshSpace.s12))
        Row {
            DshPillButton(
                label = s.reject,
                onClick = onReject,
                tone = DshPillTone.Tonal,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(DshSpace.s8))
            DshPillButton(
                label = s.allowOnce,
                onClick = onApprove,
                tone = DshPillTone.Accent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 概况行：「N 件等你处理 · M 个在跑」+ 右侧工作区筛选。
 *
 * 筛选作用于整页，所以挂在概况行而不是某个分组上；菜单列出全部工作区，
 * 每项尾部的「更多」保留原来长按胶囊的「在这里新建」「移除工作区」，最后一项进归档列表。
 */
@Composable
internal fun HomeSummaryRow(
    awaitingCount: Int,
    runningCount: Int,
    workspaces: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onAddWorkspace: () -> Unit,
    onCreateSessionIn: (String) -> Unit,
    onDeleteWorkspace: (String) -> Unit,
    onOpenArchived: () -> Unit,
) {
    val s = DshS
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DrawerTextStart, end = DrawerEdgePadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                s.homeSummaryAwaiting.format(awaitingCount),
                color = Dsh.labelPrimary,
                style = DshType.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            if (runningCount > 0) {
                Text(
                    " ${s.homeSummaryRunning.format(runningCount)}",
                    color = Dsh.labelSecondary,
                    style = DshType.headlineMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
        Box {
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = { menuOpen = true })
                    .padding(horizontal = DshSpace.s4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(DshRadius.full))
                        .background(Dsh.bgCard)
                        .padding(start = DshSpace.s12, end = DshSpace.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        s.homeAllWorkspaces,
                        color = Dsh.labelPrimary,
                        style = DshType.titleSmall,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(DshSpace.s4))
                    Icon(
                        ChevronDownOutline14,
                        contentDescription = null,
                        tint = Dsh.labelTertiary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            DshMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                offset = DpOffset(0.dp, 4.dp),
                items = buildList {
                    add(
                        DshMenuItem(
                            icon = ChecklistOutline14,
                            label = s.homeAllWorkspaces,
                            onClick = {
                                menuOpen = false
                                onSelect(null)
                            },
                        ),
                    )
                    workspaces.forEach { cwd ->
                        val name = cwd.substringAfterLast('/')
                        add(
                            DshMenuItem(
                                icon = FolderOpenOutline16,
                                label = if (selected == cwd) "$name ✓" else name,
                                onClick = {
                                    menuOpen = false
                                    onSelect(if (selected == cwd) null else cwd)
                                },
                                trailingContent = {
                                    WorkspaceMoreAction(
                                        onAddWorkspace = { menuOpen = false; onAddWorkspace() },
                                        onCreateSessionIn = { menuOpen = false; onCreateSessionIn(cwd) },
                                        onDeleteWorkspace = { menuOpen = false; onDeleteWorkspace(cwd) },
                                    )
                                },
                            ),
                        )
                    }
                    add(
                        DshMenuItem(
                            icon = ArchiveBoxOutline16,
                            label = s.homeArchivedSessions,
                            onClick = {
                                menuOpen = false
                                onOpenArchived()
                            },
                        ),
                    )
                },
            )
        }
    }
}

/** 筛选菜单每项的「更多」：原来长按工作区胶囊的两个动作 + 添加工作区。 */
@Composable
private fun WorkspaceMoreAction(
    onAddWorkspace: () -> Unit,
    onCreateSessionIn: () -> Unit,
    onDeleteWorkspace: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        DshIconAction(
            icon = EllipsisOutline16,
            contentDescription = DshS.moreActions,
            onClick = { open = true },
            size = 32.dp,
            iconSize = 16.dp,
        )
        DshMenu(
            expanded = open,
            onDismiss = { open = false },
            offset = DpOffset(0.dp, 4.dp),
            items = listOf(
                DshMenuItem(PlusOutline16, DshS.createSession) { open = false; onCreateSessionIn() },
                DshMenuItem(FolderOpenOutline16, DshS.addWorkspace) { open = false; onAddWorkspace() },
                DshMenuItem(TrashOutline16, DshS.deleteWorkspace, danger = true) { open = false; onDeleteWorkspace() },
            ),
        )
    }
}

/** 分区标题（等你处理 / 进行中 / 最近）：只有灰字，左边和会话标题对齐。 */
@Composable
internal fun HomeSectionHeader(section: HomeSection) {
    val s = DshS
    val title = when (section) {
        HomeSection.AWAITING -> s.homeAwaiting
        HomeSection.RUNNING -> s.homeRunning
        HomeSection.RECENT -> s.homeRecent
    }
    DshSectionLabel(
        text = title,
        modifier = Modifier.padding(
            start = DrawerTextStart,
            end = DrawerTextStart,
            top = DshSpace.s16,
            bottom = DshSpace.s4,
        ),
    )
}

/**
 * 离线卡（稿 08）：连不上电脑时顶掉概况行，说明下面看到的是缓存状态。
 * 「重试」重连，「连接方式」进设备页。
 */
@Composable
internal fun HomeOfflineCard(
    hostName: String,
    sinceLabel: String?,
    onRetry: () -> Unit,
    onOpenConnectionMode: () -> Unit,
) {
    val s = DshS
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DrawerEdgePadding + DshSpace.s6)
            .clip(RoundedCornerShape(DshRadius.composer))
            .background(Dsh.bgCard)
            .padding(DshSpace.s16),
    ) {
        Text(
            s.homeOfflineUnreachable.format(hostName.ifBlank { s.deviceAndPairing }),
            color = Dsh.labelPrimary,
            style = DshType.title,
            fontWeight = FontWeight.SemiBold,
        )
        if (sinceLabel != null) {
            Spacer(Modifier.size(DshSpace.s4))
            Text(
                s.homeOfflineHint.format(sinceLabel),
                color = Dsh.labelSecondary,
                style = DshType.supporting,
            )
        }
        Spacer(Modifier.size(DshSpace.s12))
        Row {
            DshPillButton(
                label = s.retry,
                onClick = onRetry,
                tone = DshPillTone.Accent,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(DshSpace.s8))
            DshPillButton(
                label = s.connectionMode,
                onClick = onOpenConnectionMode,
                tone = DshPillTone.Tonal,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 空态起手式：三行，点一下带着这句话去开新任务（稿 09）。 */
@Composable
internal fun HomeEmptyStarters(onPick: (String) -> Unit) {
    val s = DshS
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DrawerEdgePadding + DshSpace.s6),
    ) {
        Text(
            s.homeEmptyTitle,
            color = Dsh.labelPrimary,
            style = DshType.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = DrawerInnerPadding),
        )
        Spacer(Modifier.size(DshSpace.s4))
        Text(
            s.homeEmptyHint,
            color = Dsh.labelSecondary,
            style = DshType.supporting,
            modifier = Modifier.padding(horizontal = DrawerInnerPadding),
        )
        Spacer(Modifier.size(DshSpace.s16))
        DshSectionLabel(s.homeStartFrom)
        Spacer(Modifier.size(DshSpace.s8))
        listOf(
            s.homeStarterOrganize,
            s.homeStarterTest,
            s.homeStarterDiff,
        ).forEach { starter ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(DshRadius.container))
                    .clickable(role = Role.Button) { onPick(starter) }
                    .padding(horizontal = DrawerInnerPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DshSpace.s12),
            ) {
                Icon(SparkleOutline16, contentDescription = null, tint = Dsh.labelSecondary, modifier = Modifier.size(16.dp))
                Text(
                    starter,
                    color = Dsh.labelPrimary,
                    style = DshType.body,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 底部悬浮主按钮：居中「+ 新任务」（离线时置灰不可点）。 */
@Composable
internal fun HomeNewTaskFab(
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = DshSpace.s12),
        contentAlignment = Alignment.Center,
    ) {
        DshFloatingPill(
            label = DshS.homeNewTask,
            onClick = onClick,
            enabled = enabled,
            icon = PlusOutline16,
        )
    }
}
