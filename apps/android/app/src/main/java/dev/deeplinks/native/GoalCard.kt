package dev.deeplinks.native

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.deeplinks.native.ui.v4.DlPill
import dev.deeplinks.native.ui.v4.DlSize
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.core.goalClearMessage
import dev.deeplinks.core.goalClearTitle
import dev.deeplinks.core.goalEmpty
import dev.deeplinks.core.goalPause
import dev.deeplinks.core.goalResume
import dev.deeplinks.core.slotClear
import dev.deeplinks.core.slotEdit
import dev.deeplinks.core.slotPlan
import dev.deeplinks.native.ui.v4.DlIconButton
import dev.deeplinks.native.ui.v4.DlSpinner

/** 状态槽收起时的标题：「目标 · 第 3 / 8 轮 · 计划 4/7」。 */
internal fun goalSlotTitle(goal: SessionGoal?, summary: String?, plan: List<MobileTodoItem>): String {
    val parts = mutableListOf<String>()
    if (goal != null || !summary.isNullOrBlank()) parts += L.goalRole
    goal?.let { goalRoundsLabel(it) }?.let { parts += it }
    if (plan.isNotEmpty()) {
        val done = plan.count { planItemKind(it.status) == PlanItemKind.Done }
        parts += L.slotPlan.format(done, plan.size)
    }
    return parts.joinToString(" · ").ifEmpty { L.goalEmpty }
}

/** 收起时的一行：有进行中的计划项就是那一步，否则是目标原文 / 推断目标 / 「目标 · 计划 n/m」。 */
internal fun goalDockLine(goal: SessionGoal?, summary: String?, plan: List<MobileTodoItem>): String {
    val current = plan.firstOrNull { planItemKind(it.status) == PlanItemKind.Active }?.content?.trim()
    if (!current.isNullOrEmpty()) return current
    return goal?.objective?.takeIf { it.isNotBlank() }
        ?: summary?.takeIf { it.isNotBlank() }
        ?: goalSlotTitle(null, null, plan)
}

/**
 * v4 4.1 / 4.5：目标和计划停靠在输入框上沿（和网页端一样），不再占对话顶部。
 * 平时一行：进度圈 + 「1/5」+ 当前步骤 + ⌃；点开向上展开，最多半屏、内部滚动，
 * 放目标原文、轮次、计划清单；暂停 / 编辑 / 清除收进 ⋯ 菜单。回调为空时不画菜单。
 */
@Composable
internal fun GoalStatusSlot(
    goal: SessionGoal?,
    plan: List<MobileTodoItem>,
    modifier: Modifier = Modifier,
    summary: String? = null,
    expanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    busy: Boolean = false,
    onPauseOrResume: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null,
) {
    val hasGoal = goal != null || !summary.isNullOrBlank()
    val canExpand = plan.isNotEmpty() || goal != null
    val showExpanded = expanded && canExpand
    val done = plan.count { planItemKind(it.status) == PlanItemKind.Done }
    val maxPanel = (LocalConfiguration.current.screenHeightDp / 2).dp
    Column(
        modifier = modifier
            .padding(horizontal = DshSpace.s12, vertical = DshSpace.s4)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DshSpace.s8),
    ) {
        AnimatedVisibility(visible = showExpanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(DshRadius.container))
                    .background(Dsh.surface1)
                    .heightIn(max = maxPanel)
                    .verticalScroll(rememberScrollState())
                    .padding(start = DshSpace.s12, end = DshSpace.s4, top = DshSpace.s8, bottom = DshSpace.s12),
                verticalArrangement = Arrangement.spacedBy(DshSpace.s8),
            ) {
                GoalDockHeader(goal, summary, plan, busy, onPauseOrResume, onEdit, onClear)
                if (plan.isNotEmpty()) PlanChecklist(plan, Modifier.padding(end = DshSpace.s8))
            }
        }
        // 收起时是一颗和建议胶囊同高的小胶囊（Kurage「3 agents」那种），不再占一整行卡片
        Row(
            modifier = Modifier
                .clip(DlPill)
                .background(Dsh.surface1)
                .then(
                    if (canExpand) {
                        Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = if (showExpanded) DshS.collapse else DshS.expand,
                        ) { onExpandedChange(!expanded) }
                    } else {
                        Modifier
                    },
                )
                .heightIn(min = DlSize.buttonCompact)
                .padding(horizontal = DshSpace.s12),
            horizontalArrangement = Arrangement.spacedBy(DshSpace.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (plan.isNotEmpty()) {
                GoalProgressRing(done.toFloat() / plan.size)
                Text("$done/${plan.size}", style = DshType.caption, color = Dsh.labelSecondary, maxLines = 1)
            } else {
                Icon(
                    if (hasGoal) GoalOutline16 else ChecklistOutline16,
                    contentDescription = null,
                    tint = Dsh.labelSecondary,
                    modifier = Modifier.size(DshIconSize.sm),
                )
            }
            Text(
                goalDockLine(goal, summary, plan),
                style = DshType.supporting,
                color = Dsh.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            goal?.takeIf { it.phase == "paused" || it.phase == "blocked" }?.let {
                Text(goalPhaseLabel(it.phase), style = DshType.caption, color = Dsh.labelSecondary, maxLines = 1)
            }
            if (canExpand) {
                Icon(
                    if (showExpanded) ChevronDownOutline16 else ChevronUpOutline16,
                    contentDescription = null,
                    tint = Dsh.labelTertiary,
                    modifier = Modifier.size(DshIconSize.xs),
                )
            }
        }
    }
}

@Composable
private fun GoalProgressRing(progress: Float) {
    Box(Modifier.size(DshIconSize.sm), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(DshIconSize.sm),
            color = Dsh.brand400,
            trackColor = Dsh.borderSubtle,
            strokeWidth = 2.dp,
        )
    }
}

@Composable
private fun GoalDockHeader(
    goal: SessionGoal?,
    summary: String?,
    plan: List<MobileTodoItem>,
    busy: Boolean,
    onPauseOrResume: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onClear: (() -> Unit)?,
) {
    val title = goal?.objective ?: summary?.takeIf { it.isNotBlank() } ?: goalSlotTitle(null, null, plan)
    val meta = goal?.let { listOfNotNull(goalPhaseLabel(it.phase), goalRoundsLabel(it)).joinToString(" · ") }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(DshSpace.s4)) {
        Column(Modifier.weight(1f).padding(top = DshSpace.s4)) {
            Text(title, style = DshType.supporting, fontWeight = FontWeight.SemiBold, color = Dsh.labelPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (!meta.isNullOrBlank()) {
                Text(meta, style = DshType.caption, color = Dsh.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (goal != null && goal.manageable) GoalDockMenu(goal, busy, onPauseOrResume, onEdit, onClear)
    }
}

@Composable
private fun GoalDockMenu(
    goal: SessionGoal,
    busy: Boolean,
    onPauseOrResume: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onClear: (() -> Unit)?,
) {
    if (busy) {
        Box(Modifier.size(DshTouch.min), contentAlignment = Alignment.Center) { DlSpinner() }
        return
    }
    var open by remember { mutableStateOf(false) }
    val items = listOfNotNull(
        onPauseOrResume?.let {
            DshMenuItem(if (goal.active) PauseOutline16 else PlayOutline16, if (goal.active) L.goalPause else L.goalResume) { open = false; it() }
        },
        onEdit?.let { DshMenuItem(EditOutline16, L.slotEdit) { open = false; it() } },
        onClear?.let { DshMenuItem(TrashOutline16, L.slotClear, danger = true) { open = false; it() } },
    )
    if (items.isEmpty()) return
    Box {
        DlIconButton(EllipsisOutline16, L.moreActions, onClick = { open = true }, tint = Dsh.labelSecondary)
        DshMenu(expanded = open, onDismiss = { open = false }, items = items)
    }
}

/**
 * 对话页输入框上沿的目标：按钮走 [SessionControlController]，编辑 / 清除确认沿用原对话框。
 * 展开状态由调用方持有，好在对话区盖一层浅色遮罩、点遮罩收起。
 */
@Composable
internal fun SessionGoalStatus(
    goal: SessionGoal?,
    summary: String?,
    plan: List<MobileTodoItem>,
    control: SessionControlController,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember(goal?.ref?.id) { mutableStateOf(false) }
    var confirmClear by remember(goal?.ref?.id) { mutableStateOf(false) }
    val busy = control.busy.value?.startsWith("goal:") == true
    val managed = goal?.takeIf { it.manageable }
    GoalStatusSlot(
        goal = goal,
        plan = plan,
        summary = summary,
        modifier = modifier,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        busy = busy,
        onPauseOrResume = managed?.let { { control.pauseOrResumeGoal() } },
        onEdit = managed?.let { { editing = true } },
        onClear = managed?.let { { confirmClear = true } },
    )
    if (editing && goal != null) {
        GoalEditDialog(
            goal,
            saving = busy,
            onDismiss = { editing = false },
            onClear = { control.clearGoal { ok -> if (ok) editing = false } },
        ) { objective, rounds ->
            control.editGoal(objective, rounds) { ok -> if (ok) editing = false }
        }
    }
    if (confirmClear) {
        DshConfirmDialog(
            title = L.goalClearTitle,
            message = L.goalClearMessage,
            confirmLabel = L.slotClear,
            danger = true,
            saving = busy,
            onDismiss = { confirmClear = false },
            onConfirm = { control.clearGoal { ok -> if (ok) confirmClear = false } },
        )
    }
}

/** 对话页输入框上沿的目标停靠条；[goal] 为空时不占位。 */
@Composable
internal fun ChatGoalDock(
    goal: SessionStatus.Goal?,
    control: SessionControlController,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (goal == null) return
    SessionGoalStatus(goal.goal, goal.summary, goal.plan, control, expanded, onExpandedChange, modifier)
}

/** 目标面板展开时盖在对话区上的浅色遮罩，点一下收起（4.5）。 */
@Composable
internal fun GoalDockScrim(visible: Boolean, onDismiss: () -> Unit) {
    BackHandler(enabled = visible, onBack = onDismiss)
    if (!visible) return
    Box(
        Modifier
            .fillMaxSize()
            .background(Dsh.bgBase.copy(alpha = 0.6f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = DshS.collapse,
                onClick = onDismiss,
            ),
    )
}
