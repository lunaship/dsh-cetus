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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.native.ui.DshEmptyState
import dev.deeplinks.native.ui.DshGlassCapsule
import dev.deeplinks.native.ui.DshGlassCapsuleIcon
import dev.deeplinks.native.ui.DshGlassCapsuleLabel
import dev.deeplinks.native.ui.DshGlassCircle
import dev.deeplinks.native.ui.DshCardRows
import dev.deeplinks.native.ui.DshIconAction
import dev.deeplinks.native.ui.DshPageChromeDensity
import dev.deeplinks.native.ui.DshPillButton
import dev.deeplinks.native.ui.DshPillTone
import dev.deeplinks.native.ui.DshSectionLabel
import dev.deeplinks.native.ui.HostStatusDot
import dev.deeplinks.native.util.HomeSection
import dev.deeplinks.native.util.workspaceDisplayName
import dev.deeplinks.native.DshSpace
import dev.deeplinks.native.DshIconSize
import dev.deeplinks.native.DshTouch

/*
 * 首页（抽屉 / 平板侧栏）的积木（2026-10-02 Lody 风格简化 · 方案 4.1）：
 * 顶栏是两块悬浮玻璃胶囊，分区内容落在白色分组卡（DshCardRows）里，
 * 底部操作行是「搜索胶囊 + 圆形 +」。灰底只出现在画布，白色卡承载内容，
 * 玻璃只在控件上。布局由 WorkspaceSidebar 组合；这里只管样子，状态全部由参数注入。
 *
 * 强调色（L4）：圆形 + 的图标取 accentIcon；审批「允许一次」仍是描边按钮。
 */

// E4：在线点改用共享组件 HostStatusDot（在线 successContent / 离线 labelTertiary），
// 与设置页、设备页同一个组件同一组 token。

/**
 * 顶栏（L2/L9）：左侧「DeepLinks + 连接状态点」展示胶囊，右侧「工作区筛选 + 设置」操作胶囊。
 * 没有全宽导航条（L9），内容直接滚到胶囊下面；工作区筛选菜单锚在筛选图标上。
 */
@Composable
internal fun HomeHeader(
    online: Boolean,
    offlineSinceLabel: String?,
    workspaces: List<String>,
    selectedWorkspace: String?,
    onSelectWorkspace: (String?) -> Unit,
    onAddWorkspace: () -> Unit,
    onDeleteWorkspace: (String) -> Unit,
    onOpenArchived: () -> Unit,
    onOpenSettings: () -> Unit,
    backdrop: LayerBackdrop?,
) {
    val s = DshS
    val statusDesc = if (online) s.statusOnline
        else offlineSinceLabel?.let { s.homeOfflineHeader.format(it) } ?: s.statusOffline
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DshPageChromeDensity.Compact.minHeight)
            .padding(start = DrawerEdgePadding, end = DrawerEdgePadding, top = DshSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 品牌身份胶囊（L2）：不可点（无既有行为），语义合并朗读「DeepLinks, 在线」
        DshGlassCapsule(
            onClick = null,
            backdrop = backdrop,
            modifier = Modifier.semantics(mergeDescendants = true) {
                heading()
                contentDescription = "DeepLinks, $statusDesc"
            },
        ) {
            DshGlassCapsuleLabel("DeepLinks")
            Spacer(Modifier.width(DshSpace.s6))
            HostStatusDot(online)
        }
        Spacer(Modifier.weight(1f))
        // 「工作区 + 设置」合并胶囊（4.1）：左半直接写出当前工作区（全部 / 某个工作区）+ 下拉箭头，
        // 点开是原工作区菜单（H1）。2026-10-02 真机反馈：只放一个灰色漏斗时看不出能选工作区。
        Box {
            DshGlassCapsule(
                onClick = { menuOpen = true },
                backdrop = backdrop,
                contentDescription = s.homeFilterWorkspaces,
            ) {
                DshGlassCapsuleLabel(homeWorkspaceChipLabel(selectedWorkspace, s.homeAllWorkspaces))
                Spacer(Modifier.width(DshSpace.s4))
                Icon(
                    ChevronDownOutline16,
                    contentDescription = null,
                    tint = Dsh.labelSecondary,
                    modifier = Modifier.size(DshIconSize.sm),
                )
                Spacer(Modifier.width(DshSpace.s4))
                DshGlassCapsuleIcon(
                    icon = SettingsOutline16,
                    contentDescription = s.settingsTitle,
                    onClick = onOpenSettings,
                )
            }
            DshMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                offset = DpOffset(0.dp, 4.dp),
                items = buildList {
                    add(DshMenuItem(ChecklistOutline16, s.homeAllWorkspaces, selected = selectedWorkspace == null) {
                        menuOpen = false
                        onSelectWorkspace(null)
                    })
                    workspaces.forEach { cwd ->
                        add(DshMenuItem(FolderOpenOutline16, workspaceDisplayName(cwd), selected = selectedWorkspace == cwd) {
                            menuOpen = false
                            onSelectWorkspace(if (selectedWorkspace == cwd) null else cwd)
                        })
                    }
                    add(DshMenuItem(PlusOutline16, s.addWorkspace, dividerBefore = true) {
                        menuOpen = false
                        onAddWorkspace()
                    })
                    add(DshMenuItem(ArchiveBoxOutline16, s.homeArchivedSessions) {
                        menuOpen = false
                        onOpenArchived()
                    })
                    if (selectedWorkspace != null) {
                        add(
                            DshMenuItem(
                                icon = TrashOutline16,
                                label = s.homeDeleteWorkspaceNamed.format(workspaceDisplayName(selectedWorkspace)),
                                danger = true,
                                dividerBefore = true,
                                onClick = {
                                    menuOpen = false
                                    onDeleteWorkspace(selectedWorkspace)
                                },
                            ),
                        )
                    }
                },
            )
        }
    }
}

/** 首页工作区胶囊上的文字：未筛选写「全部工作区」，否则写工作区名（过长截断，给设置钮留位）。 */
internal fun homeWorkspaceChipLabel(selected: String?, allLabel: String): String {
    val name = selected?.let(::workspaceDisplayName)?.trim().orEmpty()
    if (name.isEmpty()) return allLabel
    return if (name.length > HOME_WORKSPACE_CHIP_MAX_CHARS) name.take(HOME_WORKSPACE_CHIP_MAX_CHARS - 1) + "…" else name
}

private const val HOME_WORKSPACE_CHIP_MAX_CHARS = 12

/**
 * 首页审批（稿 01/07 · 2026-10-02 改为「等你处理」卡内的一行展开内容，不再单独成卡）：
 * 元信息（工具名 · 工作区 · 时间）→ 标题 → 缺参说明 → 按钮行（拒绝 tonal / 允许一次描边）。
 *
 * 只在「这条审批由手机接管」时出现（App 正订阅该会话、requests 快照里有 pending）；
 * 其余等你处理的会话一律走会话行的「等你批准」元信息。
 *
 * 数据边界：DSH 的审批请求不带工具参数（dsh-user-approval 明确不重复 presented tool call
 * 的 arguments），紧贴标题写明「手机不含完整参数」；判断不了时引导回电脑处理。
 */
@Composable
internal fun HomeApprovalCard(
    title: String,
    workspaceLabel: String?,
    timeLabel: String?,
    toolName: String?,
    onReject: () -> Unit,
    onApprove: () -> Unit,
) {
    val s = DshS
    Column(Modifier.padding(horizontal = DrawerInnerPadding, vertical = DshSpace.s12)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 等你批准：6dp 琥珀点（3.2），与会话行同一语义
            Box(
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(DshRadius.full))
                    .background(Dsh.warn),
            )
            Spacer(Modifier.width(DshSpace.s6))
            Text(DshS.homeChipWaitingApproval, color = Dsh.warnLabel, style = DshType.captionRelaxed, maxLines = 1)
            val meta = listOfNotNull(
                toolName?.takeIf { it.isNotBlank() },
                workspaceLabel?.takeIf { it.isNotBlank() },
                timeLabel?.takeIf { it.isNotBlank() },
            )
            if (meta.isNotEmpty()) {
                Spacer(Modifier.width(DshSpace.s8))
                Text(
                    meta.joinToString(" · "),
                    color = Dsh.labelTertiary,
                    style = DshType.captionRelaxed,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(DshSpace.s4))
        Text(
            title,
            color = Dsh.labelPrimary,
            style = DshType.listTitle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(DshSpace.s6))
        Text(
            s.homeWantsCommand + " " + s.approvalArgsMissing,
            color = Dsh.labelSecondary,
            style = DshType.supporting,
        )
        Spacer(Modifier.height(DshSpace.s12))
        Row {
            DshPillButton(
                label = s.reject,
                onClick = onReject,
                tone = DshPillTone.Tonal,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(DshSpace.s8))
            // C1：手机收不到工具参数，批准等于盲批。改用 Outline（透明底 + 描边 +
            // labelPrimary），与「拒绝」同权重，不靠品牌蓝引导用户点它。
            DshPillButton(
                label = s.allowOnce,
                onClick = onApprove,
                tone = DshPillTone.Outline,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 已筛选工作区提示行（4.1 / A02）：列表顶部一行 titleSmall 灰字「dsh-links ×」，
 * 点 × 清除筛选。未筛选时不显示。
 */
@Composable
internal fun HomeWorkspaceFilterChip(label: String, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DrawerTextStart, end = DrawerEdgePadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = Dsh.labelTertiary,
            style = DshType.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        DshIconAction(
            icon = CloseOutline16,
            contentDescription = DshS.homeClearFilter,
            onClick = onClear,
            size = DshTouch.min,
            iconSize = DshIconSize.sm,
        )
    }
}

/** 分区标题（等你处理 / 进行中 / 最近）：只有灰字，左边和分组卡内文字对齐。 */
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
 * 离线卡（稿 08）：连不上电脑时顶在列表最前，说明下面看到的是缓存状态。
 * 「重试」重连，「连接方式」进设备页。白卡形态与分组卡一致。
 */
@Composable
internal fun HomeOfflineCard(
    hostName: String,
    sinceLabel: String?,
    onRetry: () -> Unit,
    onOpenConnectionMode: () -> Unit,
) {
    val s = DshS
    DshCardRows {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DrawerInnerPadding, vertical = DshSpace.s12),
        ) {
            Text(
                s.homeOfflineUnreachable.format(hostName.ifBlank { s.deviceAndPairing }),
                color = Dsh.labelPrimary,
                style = DshType.listTitle,
            )
            if (sinceLabel != null) {
                Spacer(Modifier.height(DshSpace.s4))
                Text(
                    s.homeOfflineHint.format(sinceLabel),
                    color = Dsh.labelSecondary,
                    style = DshType.supporting,
                )
            }
            Spacer(Modifier.height(DshSpace.s12))
            Row {
                DshPillButton(
                    label = s.retry,
                    onClick = onRetry,
                    // 离线重试不是「批准 / 发送」：不用强调色（V3）。
                    tone = DshPillTone.Tonal,
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
}

/** 空态起手式（稿 09）：三行，点一下带着这句话去开新任务；落在同一张白卡里。 */
@Composable
internal fun HomeEmptyStarters(onPick: (String) -> Unit) {
    val s = DshS
    DshCardRows {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DrawerInnerPadding, vertical = DshSpace.s12),
        ) {
            Text(
                s.homeEmptyTitle,
                color = Dsh.labelPrimary,
                style = DshType.titleLarge,
            )
            Spacer(Modifier.height(DshSpace.s4))
            Text(
                s.homeEmptyHint,
                color = Dsh.labelSecondary,
                style = DshType.supporting,
            )
            Spacer(Modifier.height(DshSpace.s16))
            DshSectionLabel(s.homeStartFrom)
            Spacer(Modifier.height(DshSpace.s8))
            listOf(
                s.homeStarterOrganize,
                s.homeStarterTest,
                s.homeStarterDiff,
            ).forEach { starter ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = DshRowHeight.default)
                        .clip(RoundedCornerShape(DshRadius.control))
                        .clickable(role = Role.Button) { onPick(starter) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DshSpace.s12),
                ) {
                    Icon(SparkleOutline16, contentDescription = null, tint = Dsh.labelSecondary, modifier = Modifier.size(DshIconSize.sm))
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
}

/** 筛选到某个工作区但那里还没有任务（W2）：给「在这里新建」和「查看全部」两条路。 */
@Composable
internal fun HomeWorkspaceEmpty(onCreate: () -> Unit, onShowAll: () -> Unit) {
    val s = DshS
    DshCardRows {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DrawerInnerPadding, vertical = DshSpace.s20),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DshEmptyState(title = s.homeWorkspaceEmptyTitle, message = s.homeWorkspaceEmptyHint)
            Spacer(Modifier.height(DshSpace.s12))
            Row(horizontalArrangement = Arrangement.spacedBy(DshSpace.s8)) {
                DshPillButton(label = s.createSession, onClick = onCreate, tone = DshPillTone.Accent)
                DshPillButton(label = s.homeShowAll, onClick = onShowAll, tone = DshPillTone.Tonal)
            }
        }
    }
}

/**
 * 底部操作行（4.1，L9）：左侧搜索胶囊（进现有搜索态），右侧圆形 +（accentIcon，离线置灰）。
 * 内容避让由 WorkspaceSidebar 用 overlayBottomChrome 实测高度回填。
 */
@Composable
internal fun HomeBottomBar(
    online: Boolean,
    onOpenSearch: () -> Unit,
    onNewTask: () -> Unit,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
) {
    val s = DshS
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DrawerEdgePadding, vertical = DshSpace.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DshGlassCapsule(
            onClick = onOpenSearch,
            backdrop = backdrop,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                SearchOutline16,
                contentDescription = null,
                tint = Dsh.labelSecondary,
                modifier = Modifier.size(DshIconSize.md),
            )
            Spacer(Modifier.width(DshSpace.s8))
            DshGlassCapsuleLabel(s.searchSessions, color = Dsh.labelSecondary)
        }
        Spacer(Modifier.width(DshSpace.s8))
        DshGlassCircle(
            icon = PlusOutline16,
            contentDescription = s.homeNewTask,
            onClick = onNewTask,
            enabled = online,
            iconTint = Dsh.accentIcon,
            backdrop = backdrop,
        )
    }
}
