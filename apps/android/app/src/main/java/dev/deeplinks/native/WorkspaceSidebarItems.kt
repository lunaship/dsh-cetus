package dev.deeplinks.native

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.core.tabularNums
import dev.deeplinks.core.ThemeManager
import dev.deeplinks.core.dshRipple
import dev.deeplinks.native.util.relativeTime

/**
 * 抽屉（侧栏）原生行组件 —— 密度与颜色规格（2026-09-22 收紧版）：
 *
 * - item 高 48dp（Android 触控下限）、图标 18dp、行内水平 12dp、抽屉边距 6dp；
 *   会话选中态是中性浅灰（bgSubtle），品牌蓝不进列表。
 *   相比上一版 56/24/14/8 全面收紧，
 *   一屏多出约 3 行，头部主机行 64→48、搜索框 52→40。
 * - 容器底 [Dsh.bgDrawer] 与 bgSidePanel 同档（与内容只差一档）；行底必须不透明
 *   （选中 [Dsh.bgSubtle] 垫在不透明行底上），右滑归档层才不会透出。
 *
 * 只做「尺码 + 颜色」的原生化，交互一律保留：右滑归档、长按菜单、选中回弹、
 * 点击涟漪、搜索防抖、分组折叠。行为逻辑仍在 WorkspaceSidebar / WorkspaceActivity。
 */
// 抽屉行规格（internal：WorkspaceSidebar 的分组行也按同一套边距对齐）
internal val DrawerItemHeight = 48.dp
internal val DrawerIconSize = 16.dp
internal val DrawerEdgePadding = 6.dp
/** 行内水平内边距（原 14dp）。 */
internal val DrawerInnerPadding = 12.dp
/** 首页所有文字的左边线：标题、筛选文字、分区标题、会话标题都落在这里。 */
internal val DrawerTextStart = DrawerEdgePadding + DrawerInnerPadding
/** 图标与文字的间距（原 12dp）。 */
internal val DrawerLeadingGap = 10.dp
/** 抽屉所有行共用同一圆角（选中/按压/滑动垫底同形），不混两种弧度。 */
internal val DrawerRowShape = RoundedCornerShape(DshRadius.container)

/**
 * 任务首页会话行（M3 双行列表条目规格）。
 * 归档滑动、长按菜单与原实现一致；尺寸/双行副标题/状态指示原生化。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SessionRowItem(
    session: MobileSession,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onFork: () -> Unit,
    onArchive: () -> Unit = {},
    onDelete: () -> Unit = {},
    indent: Dp = 0.dp,
    goalSummary: String? = null,
    containerColor: Color = Color.Unspecified,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val itemInteraction = remember { MutableInteractionSource() }
    val itemPressed by itemInteraction.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val semanticHaptic = rememberDshHaptic()
    val s = DshS
    val archiveLabel = L.archiveSession
    val rowRestColor = if (containerColor == Color.Unspecified) Dsh.bgDrawer else containerColor
    // 会话行统一用抽屉行圆角（小圆角矩形），不改成会抢注意力的长胶囊。
    val rowShape = DrawerRowShape

    // 原生滑动手势：从右向左滑出「归档」。松手即执行并回弹，行在服务端确认后消失；
    // 撤销走根级全局 Snackbar（restoredSessionIds 本地恢复通路，客户端无 unarchive API）。与长按菜单归档同一动作。
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                semanticHaptic(DshHaptic.Tick)
                onArchive()
            }
            false // 永远回弹，不吞掉行；可见性由服务端归档状态驱动
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            // 只在真的滑开时显形：平时不铺底、不写字，否则会从会话行的弱蓝（半透明）
            // 与圆角后面透出来，看起来像「归档会话」和标题重叠、行用阴影。
            val revealing = dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart
            val revealBg by animateColorAsState(
                targetValue = if (revealing) Dsh.brand500 else Color.Transparent,
                animationSpec = tween(motionDuration(DshDuration.normal)),
                label = "sessionArchiveRevealBg",
            )
            val revealAlpha by animateFloatAsState(
                targetValue = if (revealing) 1f else 0f,
                animationSpec = tween(motionDuration(DshDuration.normal)),
                label = "sessionArchiveRevealAlpha",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = DrawerEdgePadding + indent, end = DrawerEdgePadding)
                    .clip(rowShape)
                    .background(revealBg),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(
                    modifier = Modifier
                        .padding(end = 18.dp)
                        .graphicsLayer { alpha = revealAlpha },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        ArchiveOutline20,
                        contentDescription = null,
                        tint = Dsh.onBrand,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        archiveLabel,
                        color = Dsh.onBrand,
                        style = DshType.body,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = DrawerEdgePadding + indent, end = DrawerEdgePadding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(rowShape)
                    // 先铺不透明行底，再叠选中/按压色。选中是浅灰，
                    // 不垫底就会透出下层的滑动归档层。
                    .background(rowRestColor)
                    .background(
                        when {
                            isSelected -> Dsh.bgSubtle
                            itemPressed -> Dsh.bgPressed
                            else -> Color.Transparent
                        },
                    )
                    .semantics {
                        role = Role.Button
                        selected = isSelected
                        if (session.awaitingInput) {
                            stateDescription = s.awaitingInputStatus
                        } else if (session.running) {
                            stateDescription = s.runningStatus
                        }
                    }
                    .combinedClickable(
                        interactionSource = itemInteraction,
                        indication = dshRipple(),
                        onClick = onClick,
                        onLongClick = {
                            haptic.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                            )
                            menuOpen = true
                        },
                    )
                    .padding(horizontal = DrawerInnerPadding, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    val relTime = if (!session.running && !session.awaitingInput && session.updatedAt > 0) {
                        relativeTime(session.updatedAt)
                    } else {
                        ""
                    }
                    // 时间放在标题行尾，副标题只写项目和状态
                    val meta = formatSessionSubtitle(
                        session = session,
                        goalSummary = goalSummary,
                        runningLabel = s.runningStatus,
                        awaitingLabel = s.awaitingInputStatus,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            displaySessionTitle(session.title),
                            color = Dsh.labelPrimary,
                            style = DshType.titleSmall,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (relTime.isNotBlank()) {
                            Spacer(Modifier.width(12.dp))
                            Text(
                                relTime,
                                color = Dsh.labelTertiary,
                                style = DshType.captionRelaxed.tabularNums(),
                                maxLines = 1,
                            )
                        }
                    }
                    if (meta.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            meta,
                            color = Dsh.labelTertiary,
                            style = DshType.captionRelaxed,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // 锚在行尾：菜单靠右弹出，避免贴侧栏左边
            Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                DshMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    offset = androidx.compose.ui.unit.DpOffset(0.dp, 4.dp),
                    items = listOf(
                        DshMenuItem(EditOutline16, L.rename) {
                            menuOpen = false
                            onRename()
                        },
                        DshMenuItem(BranchOutline16, L.forkSession) {
                            menuOpen = false
                            onFork()
                        },
                        DshMenuItem(ArchiveOutline20, L.archiveSession) {
                            menuOpen = false
                            onArchive()
                        },
                        DshMenuItem(TrashOutline16, L.deleteSession, danger = true) {
                            menuOpen = false
                            onDelete()
                        },
                    ),
                )
            }
        }
    }
}

/** 抽屉搜索：40dp 输入框（bgInput + 发丝描边，与工具调用查找条同语义）。 */
@Composable
internal fun SidebarSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    loading: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DrawerEdgePadding)
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(DshRadius.container))
            .background(Dsh.bgInput)
            .padding(horizontal = DrawerInnerPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            SearchOutline16,
            contentDescription = null,
            tint = Dsh.labelTertiary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = DshType.body.copy(color = Dsh.labelPrimary),
            cursorBrush = SolidColor(Dsh.brand400),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            L.searchSessionsPlaceholder,
                            color = Dsh.labelTertiary,
                            style = DshType.body,
                        )
                    }
                    inner()
                }
            },
        )
        if (loading) {
            val spin = rememberMotionSpin(750, label = "sidebarSearchSpin")
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .rotate(spin ?: 0f)
                    .clip(CircleShape)
                    .background(Dsh.labelTertiary),
            )
        }
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            SidebarIconAction(
                icon = CloseOutline16,
                contentDescription = L.clearSearch,
                onClick = onClear,
                // 撑满搜索框高度当热区（32dp 不达触控下限）
                size = 40.dp,
                iconSize = 14.dp,
            )
        }
    }
}


/** 工作区组头：M3 抽屉条目形态。行内不再嵌「+」，新建会话进长按菜单。 */
@OptIn(ExperimentalFoundationApi::class)


/** 抽屉内的小图标按钮（overflow / 清除 / 主题），热区与图标分开。 */
@Composable
internal fun SidebarIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: Dp = 48.dp,
    iconSize: Dp = 16.dp,
    active: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                when {
                    active -> Dsh.bgNavSelected
                    pressed -> Dsh.bgPressed
                    else -> Color.Transparent
                },
            )
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            }
            .clickable(interactionSource = interaction, indication = dshRipple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (active) Dsh.brand500 else Dsh.labelSecondary,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** 折叠态：56dp 图标条（新会话 / 搜索 / 设置 / 设备 / 主题）。 */
@Composable
internal fun WorkspaceSidebarCollapsed(actions: WorkspaceSidebarActions) {
    val isDarkTheme = Dsh.isDark
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SidebarIconAction(
            icon = PlusOutline16,
            contentDescription = L.newSession,
            onClick = { actions.onNewSession() },
        )
        SidebarIconAction(
            icon = SearchOutline16,
            contentDescription = L.searchSessions,
            onClick = { actions.onToggleSearch() },
        )
        Spacer(Modifier.weight(1f))
        SidebarIconAction(
            icon = SettingsOutline16,
            contentDescription = L.settingsTitle,
            onClick = { actions.onOpenSettings() },
        )
        SidebarIconAction(
            icon = DevicesOutline16,
            contentDescription = L.deviceAndPairing,
            onClick = { actions.onOpenDevice() },
        )
        SidebarIconAction(
            icon = if (isDarkTheme) LightOutline16 else DarkOutline16,
            contentDescription = if (isDarkTheme) L.switchToLight else L.switchToDark,
            onClick = { ThemeManager.toggleTheme(context, isDarkTheme) },
        )
        Spacer(Modifier.height(4.dp))
    }
}
