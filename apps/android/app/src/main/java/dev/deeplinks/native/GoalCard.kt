package dev.deeplinks.native

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextOverflow
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.core.goalClear
import dev.deeplinks.core.goalClearMessage
import dev.deeplinks.core.goalClearTitle
import dev.deeplinks.core.goalCollapse
import dev.deeplinks.core.goalEdit
import dev.deeplinks.core.goalEmpty
import dev.deeplinks.core.goalExpand
import dev.deeplinks.core.goalPause
import dev.deeplinks.core.goalResume
import dev.deeplinks.native.ui.DshIconAction
import kotlinx.coroutines.delay

/**
 * 会话页顶栏下方的目标卡。文本最多两行，可收起。
 * 没有目标时只给截图和空态使用；会话页在目标为空时不挂这张卡。
 */
@Composable
internal fun GoalCard(
    goal: SessionGoal?,
    modifier: Modifier = Modifier,
    collapsed: Boolean = false,
    highlightAlpha: Float = 0f,
    busy: Boolean = false,
    onToggle: () -> Unit = {},
    onPauseOrResume: () -> Unit = {},
    onEdit: () -> Unit = {},
    onClear: () -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpace.s12, vertical = DshSpace.s4)
            .clip(RoundedCornerShape(DshRadius.container))
            .background(Dsh.bgInput)
            .padding(horizontal = DshSpace.s12, vertical = DshSpace.s8),
        verticalArrangement = Arrangement.spacedBy(DshSpace.s4),
    ) {
        if (goal == null) {
            Text(L.goalEmpty, color = Dsh.labelSecondary, style = DshType.caption)
            return@Column
        }
        Box(Modifier.fillMaxWidth()) {
            if (highlightAlpha > 0f) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Dsh.accentIcon.copy(alpha = 0.16f * highlightAlpha.coerceIn(0f, 1f))),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(DshSpace.s4)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DshSpace.s6),
                ) {
                    Icon(
                        GoalOutline16,
                        contentDescription = null,
                        tint = Dsh.labelSecondary,
                        modifier = Modifier.size(DshIconSize.sm),
                    )
                    Text(
                        goalPhaseLabel(goal.phase),
                        color = Dsh.labelSecondary,
                        style = DshType.microMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DshIconAction(
                        if (collapsed) ChevronDownOutline16 else ChevronUpOutline16,
                        if (collapsed) L.goalExpand else L.goalCollapse,
                        onToggle,
                        iconSize = DshIconSize.sm,
                        visualSize = DshSpace.s32,
                    )
                }
                Text(
                    goal.objective,
                    color = Dsh.labelPrimary,
                    style = DshType.caption,
                    maxLines = if (collapsed) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!collapsed) {
                    GoalCardActions(
                        goal = goal,
                        busy = busy,
                        onPauseOrResume = onPauseOrResume,
                        onEdit = onEdit,
                        onClear = onClear,
                    )
                }
            }
        }
    }
}

@Composable
private fun GoalCardActions(
    goal: SessionGoal,
    busy: Boolean,
    onPauseOrResume: () -> Unit,
    onEdit: () -> Unit,
    onClear: () -> Unit,
) {
    if (busy) {
        CircularProgressIndicator(
            modifier = Modifier.size(DshIconSize.sm),
            color = Dsh.labelSecondary,
            strokeWidth = DshSpace.s2,
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (goal.phase != "complete") {
            DshIconAction(
                if (goal.active) PauseOutline16 else PlayOutline16,
                if (goal.active) L.goalPause else L.goalResume,
                onPauseOrResume,
                iconSize = DshIconSize.sm,
                visualSize = DshSpace.s32,
            )
        }
        DshIconAction(EditOutline16, L.goalEdit, onEdit, iconSize = DshIconSize.sm, visualSize = DshSpace.s32)
        DshIconAction(TrashOutline16, L.goalClear, onClear, iconSize = DshIconSize.sm, visualSize = DshSpace.s32)
    }
}

/** 顶栏下方的目标卡，按钮走现有 [SessionControlController]。revision 变化时高亮一下。 */
@Composable
internal fun SessionGoalCard(
    goal: SessionGoal,
    control: SessionControlController,
    modifier: Modifier = Modifier,
) {
    var collapsed by remember(goal.ref.id) { mutableStateOf(false) }
    var editing by remember(goal.ref.id) { mutableStateOf(false) }
    var confirmClear by remember(goal.ref.id) { mutableStateOf(false) }
    val busy = control.busy.value?.startsWith("goal:") == true
    GoalCard(
        goal = goal,
        modifier = modifier,
        collapsed = collapsed,
        highlightAlpha = goalRevisionHighlight(goal.ref.id, goal.ref.revision),
        busy = busy,
        onToggle = { collapsed = !collapsed },
        onPauseOrResume = { control.pauseOrResumeGoal() },
        onEdit = { editing = true },
        onClear = { confirmClear = true },
    )
    if (editing) {
        GoalEditDialog(goal, saving = busy, onDismiss = { editing = false }) { objective, rounds ->
            control.editGoal(objective, rounds) { ok -> if (ok) editing = false }
        }
    }
    if (confirmClear) {
        DshConfirmDialog(
            title = L.goalClearTitle,
            message = L.goalClearMessage,
            confirmLabel = L.goalClear,
            danger = true,
            saving = busy,
            onDismiss = { confirmClear = false },
            onConfirm = { control.clearGoal { ok -> if (ok) confirmClear = false } },
        )
    }
}

@Composable
private fun goalRevisionHighlight(goalId: String, revision: Int): Float {
    var previous by remember(goalId) { mutableStateOf(revision) }
    var highlighted by remember(goalId) { mutableStateOf(false) }
    val reduce = isReduceMotionEnabled() || LocalInspectionMode.current
    LaunchedEffect(revision) {
        if (previous == revision) return@LaunchedEffect
        previous = revision
        if (reduce) return@LaunchedEffect
        highlighted = true
        delay(DshDuration.slow.toLong())
        highlighted = false
    }
    val alpha by animateFloatAsState(
        targetValue = if (highlighted) 1f else 0f,
        animationSpec = tween(durationMillis = motionDuration(DshDuration.normal), easing = DshEasing.inOut),
        label = "goalRevision",
    )
    return alpha
}

/** 顶栏下方：有结构化目标就画目标卡，吸顶条不再重复这段文字。 */
@Composable
internal fun SessionChromeGoals(
    goalSummary: String?,
    messages: List<MobileMessage>,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
    control: SessionControlController,
) {
    val goal = control.goal.value
    if (goal != null) SessionGoalCard(goal, control, modifier)
    ChatStickySummary(
        goalOverride = goalSummary,
        messages = messages,
        isRunning = isRunning,
        modifier = modifier,
        goal = goal,
        control = control,
        suppressGoalText = goal != null,
    )
}
