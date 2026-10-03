package dev.deeplinks.native

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.deeplinks.core.L
import dev.deeplinks.core.goalClearMessage
import dev.deeplinks.core.goalClearTitle
import dev.deeplinks.core.goalEmpty
import dev.deeplinks.core.goalPause
import dev.deeplinks.core.goalResume
import dev.deeplinks.core.slotClear
import dev.deeplinks.core.slotCurrent
import dev.deeplinks.core.slotEdit
import dev.deeplinks.core.slotPlan
import dev.deeplinks.native.ui.v4.DlAction
import dev.deeplinks.native.ui.v4.DlButton
import dev.deeplinks.native.ui.v4.DlButtonStyle
import dev.deeplinks.native.ui.v4.DlSpinner
import dev.deeplinks.native.ui.v4.DlStatusSlot

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

/** 状态槽收起时的第二行：有进行中的计划项就说「正在：……」，否则是目标原文。 */
internal fun goalSlotMeta(goal: SessionGoal?, summary: String?, plan: List<MobileTodoItem>): String? {
    val current = plan.firstOrNull { planItemKind(it.status) == PlanItemKind.Active }?.content?.trim()
    if (!current.isNullOrEmpty()) return L.slotCurrent.format(current)
    return goal?.objective?.takeIf { it.isNotBlank() } ?: summary?.takeIf { it.isNotBlank() }
}

/**
 * v4 4.1 / 4.5：目标和计划合在同一个状态槽里。平时一行，点开是目标原文 + 进度条 + 计划清单 + 文字按钮。
 * 只给截图和 [SessionGoalStatus] 用；按钮回调为空时不画按钮。
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
    val expandedTitle = goal?.objective ?: summary?.takeIf { it.isNotBlank() } ?: goalSlotTitle(null, null, plan)
    val expandedMeta = goal?.let { listOfNotNull(goalPhaseLabel(it.phase), goalRoundsLabel(it)).joinToString(" · ") }
    val canExpand = plan.isNotEmpty() || goal != null
    DlStatusSlot(
        title = if (expanded && canExpand) expandedTitle else goalSlotTitle(goal, summary, plan),
        meta = if (expanded && canExpand) expandedMeta else goalSlotMeta(goal, summary, plan),
        icon = if (hasGoal) GoalOutline16 else ChecklistOutline16,
        modifier = modifier,
        expanded = expanded,
        onExpandedChange = onExpandedChange.takeIf { canExpand },
        expandedContent = if (canExpand) {
            {
                if (plan.isNotEmpty()) PlanChecklist(plan)
                if (goal != null && goal.manageable) {
                    GoalSlotActions(goal, busy, onPauseOrResume, onEdit, onClear)
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun GoalSlotActions(
    goal: SessionGoal,
    busy: Boolean,
    onPauseOrResume: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onClear: (() -> Unit)?,
) {
    if (busy) {
        DlSpinner()
        return
    }
    val actions = listOfNotNull(
        onPauseOrResume?.let { DlAction(if (goal.active) L.goalPause else L.goalResume, it, DlButtonStyle.Text) },
        onEdit?.let { DlAction(L.slotEdit, it, DlButtonStyle.Text) },
        onClear?.let { DlAction(L.slotClear, it, DlButtonStyle.Danger) },
    )
    if (actions.isEmpty()) return
    // 文字按钮自带水平内边距，往左收一点让第一个字和清单对齐
    Row(horizontalArrangement = Arrangement.spacedBy(DshSpace.s4), verticalAlignment = Alignment.CenterVertically) {
        actions.forEach { DlButton(it, compact = true) }
    }
}

/** 对话页状态槽里的目标：按钮走 [SessionControlController]，编辑 / 清除确认沿用原对话框。 */
@Composable
internal fun SessionGoalStatus(
    goal: SessionGoal?,
    summary: String?,
    plan: List<MobileTodoItem>,
    control: SessionControlController,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(goal?.ref?.id) { mutableStateOf(false) }
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
        onExpandedChange = { expanded = it },
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
