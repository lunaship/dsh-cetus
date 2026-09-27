package dev.deeplinks.native

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.tabularNums
import dev.deeplinks.native.util.HomeSection

/*
 * 首页（抽屉 / 平板侧栏）的积木：顶栏、工作区筛选条、分区标题、底部「开始新任务」。
 * 布局由 WorkspaceSidebar 组合；这里只管样子，状态全部由参数注入。
 */

/** 顶栏：左边是当前电脑（点开设备面板），右边搜索与设置。 */
@Composable
internal fun HomeHeader(
    hostName: String,
    searchActive: Boolean,
    onOpenDevice: () -> Unit,
    onToggleSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val s = DshS
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DrawerEdgePadding + 6.dp, end = DrawerEdgePadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(DshRadius.full))
                .clickable(role = Role.Button, onClickLabel = s.deviceAndPairing, onClick = onOpenDevice),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(DshRadius.full))
                    .background(Dsh.bgGroupedCard)
                    .padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LaptopOutline16, contentDescription = null, tint = Dsh.labelSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    hostName.ifBlank { s.deviceAndPairing },
                    color = Dsh.labelPrimary,
                    style = DshType.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    ChevronDownOutline14,
                    contentDescription = null,
                    tint = Dsh.labelTertiary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        SidebarIconAction(
            icon = SearchOutline16,
            contentDescription = s.searchSessions,
            onClick = onToggleSearch,
            active = searchActive,
        )
        SidebarIconAction(
            icon = SettingsOutline16,
            contentDescription = s.settingsTitle,
            onClick = onOpenSettings,
        )
    }
}

/**
 * 工作区筛选条：「全部」+ 每个工作区一个胶囊，横向滚动；末尾「+」添加工作区。
 * 长按工作区胶囊：在这里新建会话 / 移除工作区（原文件夹行的菜单）。
 */
@Composable
internal fun WorkspaceChips(
    workspaces: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onAddWorkspace: () -> Unit,
    onCreateSessionIn: (String) -> Unit,
    onDeleteWorkspace: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = DrawerEdgePadding + 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeChip(label = DshS.homeAllWorkspaces, selected = selected == null, onClick = { onSelect(null) })
        workspaces.forEach { cwd ->
            var menuOpen by remember(cwd) { mutableStateOf(false) }
            Box {
                HomeChip(
                    label = cwd.substringAfterLast('/'),
                    selected = selected == cwd,
                    onClick = { onSelect(if (selected == cwd) null else cwd) },
                    onLongClick = { menuOpen = true },
                )
                DshMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    offset = DpOffset(0.dp, 4.dp),
                    items = listOf(
                        DshMenuItem(PlusOutline16, DshS.createSession) {
                            menuOpen = false
                            onCreateSessionIn(cwd)
                        },
                        DshMenuItem(TrashOutline16, DshS.deleteWorkspace, danger = true) {
                            menuOpen = false
                            onDeleteWorkspace(cwd)
                        },
                    ),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = DshS.addWorkspace, onClick = onAddWorkspace),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Dsh.bgGroupedCard),
                contentAlignment = Alignment.Center,
            ) {
                Icon(PlusOutline16, contentDescription = DshS.addWorkspace, tint = Dsh.labelSecondary, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeChip(label: String, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(DshRadius.full))
            .combinedClickable(role = Role.Tab, onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            // 选中态是反色胶囊：深色底配画布色字（深浅色模式都成立，不用 onBrand）
            color = if (selected) Dsh.bgBase else Dsh.labelSecondary,
            style = DshType.titleSmall,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(DshRadius.full))
                .background(if (selected) Dsh.labelPrimary else Dsh.bgGroupedCard)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

/** 分区标题：状态区用语义色圆点；历史区用时钟图标，和任务行的 leading 槽位对齐。 */
@Composable
internal fun HomeSectionHeader(section: HomeSection, count: Int) {
    val s = DshS
    val waitingContent = if (Dsh.isDark) Dsh.warn else Dsh.warnLabel
    val (title, accent) = when (section) {
        HomeSection.AWAITING -> s.homeAwaiting to waitingContent
        HomeSection.RUNNING -> s.homeRunning to Dsh.brand400
        HomeSection.TODAY -> s.homeToday to null
        HomeSection.YESTERDAY -> s.homeYesterday to null
        HomeSection.EARLIER -> s.homeEarlier to null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DrawerEdgePadding + DrawerInnerPadding, end = DrawerEdgePadding + DrawerInnerPadding, top = 14.dp, bottom = 4.dp)
            .semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (accent != null) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Spacer(Modifier.width(8.dp))
        } else {
            Icon(
                ClockOutline16,
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            title,
            color = accent ?: Dsh.labelSecondary,
            style = DshType.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (accent != null && count > 0) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(DshRadius.full))
                    .background(Dsh.bgGroupedCard)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("$count", color = Dsh.labelTertiary, style = DshType.captionRelaxed.tabularNums())
            }
        }
    }
}

/** 底部「开始新任务」：与聊天页输入框同一形状；点按进入新会话（选中工作区时建在该工作区）。 */
@Composable
internal fun HomeNewTaskBar(workspaceName: String?, onClick: () -> Unit) {
    val s = DshS
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DrawerEdgePadding + 6.dp, vertical = 8.dp)
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(DshRadius.composer))
            .background(Dsh.bgGroupedCard)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 18.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            PlusOutline16,
            contentDescription = null,
            tint = Dsh.labelTertiary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            s.homeNewTask,
            color = Dsh.labelTertiary,
            style = DshType.body,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (workspaceName != null) {
            Row(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clip(RoundedCornerShape(DshRadius.full))
                    .background(Dsh.bgCard)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(FolderOpenOutline16, contentDescription = null, tint = Dsh.labelSecondary, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
                Text(workspaceName, color = Dsh.labelSecondary, style = DshType.captionRelaxed, maxLines = 1)
                Spacer(Modifier.width(4.dp))
                Icon(
                    ChevronDownOutline14,
                    contentDescription = null,
                    tint = Dsh.labelTertiary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Dsh.brand400),
            contentAlignment = Alignment.Center,
        ) {
            Icon(PlusOutline16, contentDescription = s.newSession, tint = Dsh.onBrand, modifier = Modifier.size(16.dp))
        }
    }
}
