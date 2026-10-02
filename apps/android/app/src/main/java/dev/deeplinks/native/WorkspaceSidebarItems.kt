package dev.deeplinks.native

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import dev.deeplinks.native.util.homeTimeLabel
import dev.deeplinks.native.DshIconSize
import dev.deeplinks.native.DshTouch
import dev.deeplinks.native.DshRowHeight
import dev.deeplinks.native.DshSpace
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.core.subagentRunning
import dev.deeplinks.core.tabularNums
import dev.deeplinks.core.ThemeManager
import dev.deeplinks.core.dshRipple

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
internal val DrawerItemHeight = DshRowHeight.default
internal val DrawerIconSize = DshIconSize.sm
internal val DrawerEdgePadding = DshSpace.s6
/** 行内水平内边距（原 14dp）。 */
internal val DrawerInnerPadding = DshSpace.s12
/** 首页所有文字的左边线：标题、筛选文字、分区标题、会话标题都落在这里。 */
internal val DrawerTextStart = DrawerEdgePadding + DrawerInnerPadding
/** 图标与文字的间距（原 12dp）。 */
internal val DrawerLeadingGap = DshSpace.s10
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
    /** 电脑离线：进行中行改成「最后看到：…」元信息，不再假装还在实时跑。 */
    offline: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val itemInteraction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val semanticHaptic = rememberDshHaptic()
    val s = DshS
    val archiveLabel = L.archiveSession
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
            // P1（2026-09-30）：底色改浅灰，且只有实际左滑超过 24dp 才显形。平时完全透明，
            // 手指点击时的一点点横向位移不会再从圆角后面透出深色（真机反馈的「黑色阴影」）。
            val revealThreshold = with(LocalDensity.current) { 24.dp.toPx() }
            val swipeOffset = runCatching { -dismissState.requireOffset() }.getOrDefault(0f)
            val revealing = swipeOffset > revealThreshold
            val revealBg by animateColorAsState(
                // 浅灰底 + 墨色图标文字（不用强调色，强调色只给批准 / 发送）。
                targetValue = if (revealing) Dsh.bgSubtle else Color.Transparent,
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
                        tint = Dsh.labelPrimary,
                        modifier = Modifier.size(DshIconSize.md),
                    )
                    Spacer(Modifier.width(DshSpace.s8))
                    Text(
                        archiveLabel,
                        color = Dsh.labelPrimary,
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
                    .heightIn(min = DshRowHeight.expanded)
                    .clip(rowShape)
                    // 分组卡承载白色底（2026-10-02 简化），行本体透明；选中叠 bgSubtle，
                    // 不垫底也能盖住下层的滑动归档层（滑动层平时完全透明）
                    .background(
                        when {
                            isSelected -> Dsh.bgSubtle
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
                    val relTime = if (session.updatedAt > 0) homeTimeLabel(session.updatedAt) else ""
                    val texts = homeRowTexts(session = session, goalSummary = goalSummary, offline = offline)
                    // 元信息行（3.2 第一层）：状态点（仅执行中 / 等你批准）+ 工作区 · 状态 · 步数；时间右对齐
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val dotColor = when {
                            session.awaitingInput -> Dsh.warn
                            session.running && !offline -> Dsh.labelPrimary
                            else -> null
                        }
                        if (dotColor != null) {
                            HomeStatusDot(color = dotColor, pulsing = session.running && !offline)
                            Spacer(Modifier.width(DshSpace.s6))
                        }
                        if (texts.meta.isNotBlank()) {
                            Text(
                                texts.meta,
                                color = Dsh.labelTertiary,
                                style = DshType.captionRelaxed,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        if (relTime.isNotBlank()) {
                            Spacer(Modifier.width(DshSpace.s12))
                            Text(
                                relTime,
                                color = Dsh.labelTertiary,
                                style = DshType.captionRelaxed.tabularNums(),
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.height(DshSpace.s2))
                    // 标题行（第二层）：bodyLarge Normal，最多 2 行
                    Text(
                        displaySessionTitle(session.title),
                        color = Dsh.labelPrimary,
                        style = DshType.listTitle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 结果一句话（有才显示）：supporting 14/20，1 行
                    texts.result?.let { result ->
                        Spacer(Modifier.height(DshSpace.s2))
                        Text(
                            result,
                            color = Dsh.labelSecondary,
                            style = DshType.supporting,
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

/**
 * 状态点（3.2）：6dp 圆点。执行中 = labelPrimary 脉冲（系统关动画时静止），
 * 等你批准 = warn 点（pulsing = false）。完成、已中断不显示点。
 */
@Composable
private fun HomeStatusDot(color: Color, pulsing: Boolean) {
    val alpha = if (pulsing && !isReduceMotionEnabled()) {
        val transition = rememberInfiniteTransition(label = "homeStatusDot")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "homeStatusDotAlpha",
        ).value
    } else {
        1f
    }
    Box(
        Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}

/**
 * 首页行两层文字（3.2，2026-10-02）：
 * - [HomeRowTexts.meta]：元信息行 = 工作区 · 状态（仅执行中 / 等你批准 / 已中断）· 步数；
 *   时间不在这里（UI 右对齐注入）。离线时进行中行加「最后看到：」前缀（稿 08）。
 * - [HomeRowTexts.result]：结果一句话（lastResult.text，L7：文件数只认改动卡，不写进行里）；
 *   已中断的原因在元信息行，不再与结果拼接。
 */
internal data class HomeRowTexts(val meta: String, val result: String?)

internal fun homeRowTexts(session: MobileSession, goalSummary: String?, offline: Boolean = false): HomeRowTexts {
    val s = L
    val workspace = session.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    val parts = mutableListOf<String>()
    workspace?.let { parts += it }
    var result: String? = null
    when {
        session.awaitingInput -> parts += s.homeChipWaitingApproval
        session.running -> {
            val activity = session.activity
            val body = when {
                activity?.isTool == true && !activity.label.isNullOrBlank() -> {
                    val step = activity.step?.let { " · ${s.homeStepLabel.format(it)}" } ?: ""
                    "${s.homeRunningInline.format(activity.label)}$step"
                }
                activity?.kind == "thinking" -> s.homeThinking
                activity?.kind == "writing" -> s.homeWriting
                !goalSummary.isNullOrBlank() -> goalSummary
                else -> s.runningStatus
            }
            // 离线时这一行是缓存下来的最后状态，加前缀说清楚（稿 08）
            parts += if (offline) "${s.homeLastSeenPrefix}$body" else body
        }
        else -> {
            // 已结束：元信息里用文字表达「怎么停的」（3.2 不再用图标）；结果一句话放第二层
            session.stoppedReason?.let { parts += stoppedReasonLabel(it) }
            result = session.lastResult?.text?.takeIf { it.isNotBlank() }
        }
    }
    session.subagentCount?.takeIf { it > 0 }?.let { parts += s.subagentRunning.format(it) }
    return HomeRowTexts(meta = parts.joinToString(" · "), result = result)
}

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
            .heightIn(min = DshTouch.min)
            .clip(RoundedCornerShape(DshRadius.container))
            .background(Dsh.bgInput)
            .padding(horizontal = DrawerInnerPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            SearchOutline16,
            contentDescription = null,
            tint = Dsh.labelTertiary,
            modifier = Modifier.size(DshIconSize.sm),
        )
        Spacer(Modifier.width(DshSpace.s8))
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
            Spacer(Modifier.width(DshSpace.s6))
            SidebarIconAction(
                icon = CloseOutline16,
                contentDescription = L.clearSearch,
                onClick = onClear,
                // 撑满搜索框高度当热区（不达触控下限的 32/40 已收敛到 48）
                size = DshTouch.min,
                iconSize = DshIconSize.sm,
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
    size: Dp = DshTouch.min,
    iconSize: Dp = DshIconSize.sm,
    active: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                when {
                    active -> Dsh.bgNavSelected
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
            // 激活态靠 bgNavSelected 底色表达，不用强调色（V3）。
            tint = if (active) Dsh.labelPrimary else Dsh.labelSecondary,
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
            .padding(vertical = DshSpace.s8),
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
        Spacer(Modifier.height(DshSpace.s4))
    }
}
