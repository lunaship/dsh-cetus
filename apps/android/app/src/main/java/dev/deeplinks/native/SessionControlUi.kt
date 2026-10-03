package dev.deeplinks.native

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.core.goalActiveLabel
import dev.deeplinks.core.goalBlockedLabel
import dev.deeplinks.core.goalClearMessage
import dev.deeplinks.core.goalClearTitle
import dev.deeplinks.core.goalClearAction
import dev.deeplinks.core.goalCompleteLabel
import dev.deeplinks.core.goalEdit
import dev.deeplinks.core.goalMaxRounds
import dev.deeplinks.core.goalMaxRoundsInvalid
import dev.deeplinks.core.goalObjective
import dev.deeplinks.core.goalPausedLabel
import dev.deeplinks.core.goalRounds
import dev.deeplinks.core.goalRoundsOf
import dev.deeplinks.core.queueContext
import dev.deeplinks.core.queueEdit
import dev.deeplinks.core.queueImages
import dev.deeplinks.core.queueQueued
import dev.deeplinks.core.queueRemove
import dev.deeplinks.core.queueSteer
import dev.deeplinks.core.queueSteering
import dev.deeplinks.core.queueTitle
import dev.deeplinks.core.scheduleDelete
import dev.deeplinks.core.scheduleDeleteMessage
import dev.deeplinks.core.scheduleEdit
import dev.deeplinks.core.scheduleEnded
import dev.deeplinks.core.scheduleLastDelivered
import dev.deeplinks.core.scheduleNext
import dev.deeplinks.core.schedulePrompt
import dev.deeplinks.core.scheduleTimingNote
import dev.deeplinks.core.scheduleTitle
import dev.deeplinks.core.scheduledTasks
import dev.deeplinks.core.schedulesAll
import dev.deeplinks.core.schedulesEmpty
import dev.deeplinks.core.schedulesEmptyHint
import dev.deeplinks.core.schedulesFootnote
import dev.deeplinks.core.schedulesThisSession
import dev.deeplinks.core.slotClear
import dev.deeplinks.native.ui.DshIconAction
import dev.deeplinks.native.ui.v4.DlBottomSheet
import dev.deeplinks.native.ui.v4.DlListRow
import dev.deeplinks.native.ui.v4.DlRowTrailing
import dev.deeplinks.native.ui.v4.DlSegmented
import dev.deeplinks.native.ui.v4.DlSpinner
import dev.deeplinks.native.ui.v4.DlTextField
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ===================== 排队消息 =====================

/**
 * 输入卡上方的待发送条：排队中（下一轮发）/ 引导中（本轮下一步插入）。
 * 每条可「立即引导」「改后重发」（移出队列并把文字放回输入框）或移出队列；
 * 附加上下文由系统产生，只展示不操作。
 */
@Composable
internal fun QueuedPromptsStrip(
    control: SessionControlController,
    onRestoreToComposer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!control.supported.value) return
    val items = control.queue.value
    if (items.isEmpty()) return
    val busy = control.busy.value
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpace.s12, vertical = DshSpace.s4)
            .clip(RoundedCornerShape(DshRadius.container))
            .background(Dsh.bgInput)
            .padding(horizontal = DshSpace.s12, vertical = DshSpace.s8),
    ) {
        Text(L.queueTitle.format(items.size), color = Dsh.labelTertiary, style = DshType.microMedium)
        items.take(MAX_VISIBLE_QUEUE).forEach { item ->
            QueuedPromptRow(
                item = item,
                busy = busy == "queue:${item.id}",
                enabled = busy == null,
                onSteer = { control.steerQueued(item) },
                onEdit = { control.editQueued(item, onRestoreToComposer) },
                onRemove = { control.removeQueued(item) },
            )
        }
        control.error.value?.let { Text(it, color = Dsh.error, style = DshType.captionRelaxed) }
    }
}

private const val MAX_VISIBLE_QUEUE = 4

internal fun queuePlacementLabel(placement: String): String = when (placement) {
    "steering" -> L.queueSteering
    "context" -> L.queueContext
    else -> L.queueQueued
}

@Composable
private fun QueuedPromptRow(
    item: QueuedPrompt,
    busy: Boolean,
    enabled: Boolean,
    onSteer: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = DshTouch.min),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpace.s8),
    ) {
        Icon(QueueOutline16, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(DshIconSize.sm))
        Text(queuePlacementLabel(item.placement), color = Dsh.labelSecondary, style = DshType.microMedium)
        val preview = item.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            .ifEmpty { if (item.images > 0) L.queueImages.format(item.images) else "…" }
        Text(
            preview,
            color = Dsh.labelPrimary,
            style = DshType.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(DshIconSize.sm), color = Dsh.labelSecondary, strokeWidth = DshSpace.s4)
        } else if (item.editable) {
            if (item.placement == "queued") {
                DshIconAction(RightUpOutline16, L.queueSteer, onSteer, iconSize = DshIconSize.sm, visualSize = DshSpace.s32)
            }
            DshIconAction(EditOutline16, L.queueEdit, { if (enabled) onEdit() }, iconSize = DshIconSize.sm, visualSize = DshSpace.s32)
            DshIconAction(TrashOutline16, L.queueRemove, { if (enabled) onRemove() }, iconSize = DshIconSize.sm, visualSize = DshSpace.s32)
        }
    }
}

// ===================== 目标 =====================

internal fun goalPhaseLabel(phase: String): String = when (phase) {
    "paused" -> L.goalPausedLabel
    "blocked" -> L.goalBlockedLabel
    "complete" -> L.goalCompleteLabel
    else -> L.goalActiveLabel
}

internal fun goalRoundsLabel(goal: SessionGoal): String? = when {
    goal.roundsStarted <= 0 -> null
    goal.maxGoalRounds != null -> L.goalRoundsOf.format(goal.roundsStarted, goal.maxGoalRounds)
    else -> L.goalRounds.format(goal.roundsStarted)
}

/** ⋯ 菜单「目标」入口：有可管理目标时打开编辑对话框。 */
@Composable
internal fun GoalEditHost(open: Boolean, control: SessionControlController, onDismiss: () -> Unit) {
    val goal = control.goal.value
    if (!open || goal == null) return
    val busy = control.busy.value?.startsWith("goal:") == true
    GoalEditDialog(
        goal,
        saving = busy,
        onDismiss = onDismiss,
        onClear = { control.clearGoal { ok -> if (ok) onDismiss() } },
    ) { objective, rounds ->
        control.editGoal(objective, rounds) { ok -> if (ok) onDismiss() }
    }
}

/** 解析「最多轮数」输入：空 = 不改；非法返回 -1。 */
internal fun parseGoalRounds(raw: String): Int? {
    val t = raw.trim()
    if (t.isEmpty()) return null
    val n = t.toIntOrNull() ?: return -1
    return if (n in 1..1000) n else -1
}

/**
 * 5.13 编辑目标：目标内容 + 最多轮数；「清除目标」红字放在按钮行最左边，点了先二次确认。
 * [onClear] 为 null 时不显示清除。
 */
@Composable
internal fun GoalEditDialog(
    goal: SessionGoal,
    saving: Boolean,
    onDismiss: () -> Unit,
    onClear: (() -> Unit)? = null,
    onSave: (String, Int?) -> Unit,
) {
    var objective by remember(goal.ref) { mutableStateOf(goal.objective) }
    var rounds by remember(goal.ref) { mutableStateOf(goal.maxGoalRounds?.toString().orEmpty()) }
    var confirmClear by remember(goal.ref) { mutableStateOf(false) }
    val parsedRounds = parseGoalRounds(rounds)
    val roundsInvalid = parsedRounds == -1
    DshDialogFrame(onDismiss = onDismiss, dismissible = !saving) { requestDismiss ->
        DshDialogTitle(L.goalEdit)
        Spacer(Modifier.height(DshSpace.s16))
        DlTextField(
            value = objective,
            onValueChange = { objective = it },
            label = L.goalObjective,
            enabled = !saving,
            singleLine = false,
            minLines = 2,
        )
        Spacer(Modifier.height(DshSpace.s12))
        DlTextField(
            value = rounds,
            onValueChange = { rounds = it.filter(Char::isDigit).take(4) },
            label = L.goalMaxRounds,
            enabled = !saving,
            isError = roundsInvalid,
        )
        if (roundsInvalid) DshDialogError(L.goalMaxRoundsInvalid)
        DshDialogButtons(
            dismissLabel = L.cancel,
            onDismiss = requestDismiss,
            confirmLabel = if (saving) L.saving else L.save,
            onConfirm = { onSave(objective.trim(), parsedRounds) },
            enabled = !saving,
            confirmEnabled = objective.isNotBlank() && !roundsInvalid &&
                (objective.trim() != goal.objective || parsedRounds != goal.maxGoalRounds),
            leadingLabel = if (onClear != null) L.goalClearAction else null,
            onLeading = { confirmClear = true },
        )
    }
    if (confirmClear && onClear != null) {
        DshConfirmDialog(
            title = L.goalClearTitle,
            message = L.goalClearMessage,
            confirmLabel = L.slotClear,
            danger = true,
            saving = saving,
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                onClear()
            },
        )
    }
}

// ===================== 定时任务 =====================

private val scheduleTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d HH:mm")

internal fun formatScheduleTime(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String? {
    if (iso.isNullOrBlank()) return null
    return runCatching { scheduleTimeFormat.withZone(zone).format(Instant.parse(iso)) }.getOrDefault(iso)
}

internal fun scheduleSubtitle(task: ScheduledTask): String = listOfNotNull(
    scheduleRuleLabel(task),
    if (task.active) formatScheduleTime(task.nextRunAt)?.let { L.scheduleNext.format(it) } else L.scheduleEnded,
    formatScheduleTime(task.lastDeliveredAt)?.let { L.scheduleLastDelivered.format(it) },
).joinToString(" · ")

/**
 * 5.12 定时任务：本会话 / 全部会话两档 + 列表。点一条可改标题与提示词或删除。
 * DSH 不开放远程新建，也没有暂停接口，所以没有开关；状态写在副标题里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduledTasksSheet(open: Boolean, control: SessionControlController, hasSession: Boolean, onDismiss: () -> Unit) {
    if (!open) return
    var all by remember { mutableStateOf(!hasSession) }
    var editing by remember { mutableStateOf<ScheduledTask?>(null) }
    LaunchedEffect(all) { control.loadSchedules(all) }
    DlBottomSheet(onDismissRequest = onDismiss, title = L.scheduledTasks) {
        ScheduledTasksContent(
            tasks = control.schedules.value,
            loading = control.schedulesLoading.value,
            loadError = control.schedulesError.value,
            showScope = hasSession,
            all = all,
            enabled = control.busy.value == null,
            onScopeChange = { all = it },
            onRetry = { control.loadSchedules(all) },
            onOpen = { editing = it },
        )
    }
    editing?.let { task ->
        ScheduleEditDialog(
            task = task,
            saving = control.busy.value == "schedule:${task.id}",
            error = control.error.value,
            onDismiss = { editing = null },
            onSave = { title, prompt -> control.updateSchedule(task, title, prompt, all) { ok -> if (ok) editing = null } },
            onDelete = { control.deleteSchedule(task, all) { ok -> if (ok) editing = null } },
        )
    }
}

/** 弹层正文，截图直接画这一块。 */
@Composable
internal fun ScheduledTasksContent(
    tasks: List<ScheduledTask>,
    loading: Boolean,
    loadError: String?,
    showScope: Boolean,
    all: Boolean,
    enabled: Boolean,
    onScopeChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onOpen: (ScheduledTask) -> Unit,
) {
    if (showScope) {
        DlSegmented(
            options = listOf(L.schedulesThisSession, L.schedulesAll),
            selectedIndex = if (all) 1 else 0,
            onSelect = { onScopeChange(it == 1) },
            modifier = Modifier.fillMaxWidth().padding(start = DshSpace.s24, end = DshSpace.s24, bottom = DshSpace.s8),
        )
    }
    when {
        loading && tasks.isEmpty() -> Box(Modifier.fillMaxWidth().padding(DshSpace.s24), contentAlignment = Alignment.Center) {
            DlSpinner()
        }
        loadError != null && tasks.isEmpty() -> DlListRow(
            title = loadError,
            danger = true,
            trailing = DlRowTrailing.TextAction(L.retry, onRetry),
        )
        tasks.isEmpty() -> DlListRow(title = L.schedulesEmpty, subtitle = L.schedulesEmptyHint, leading = ClockOutline16, enabled = false)
        else -> Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            tasks.forEach { task ->
                DlListRow(
                    title = task.title.ifBlank { task.prompt.lineSequence().firstOrNull().orEmpty().take(40) },
                    subtitle = scheduleSubtitle(task),
                    leading = ClockOutline16,
                    trailing = DlRowTrailing.Chevron,
                    enabled = enabled,
                    onClick = { onOpen(task) },
                )
            }
        }
    }
    Text(
        L.schedulesFootnote,
        style = DshType.supporting,
        color = Dsh.labelSecondary,
        modifier = Modifier.padding(horizontal = DshSpace.s24, vertical = DshSpace.s8),
    )
}

@Composable
private fun ScheduleEditDialog(
    task: ScheduledTask,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onDelete: () -> Unit,
) {
    var title by remember(task.id) { mutableStateOf(task.title) }
    var prompt by remember(task.id) { mutableStateOf(task.prompt) }
    var confirmDelete by remember(task.id) { mutableStateOf(false) }
    DshDialogFrame(onDismiss = onDismiss, dismissible = !saving) { requestDismiss ->
        DshDialogTitle(L.scheduleEdit)
        DshDialogMessage(scheduleRuleLabel(task) + "\n" + L.scheduleTimingNote)
        Spacer(Modifier.height(DshSpace.s16))
        DlTextField(value = title, onValueChange = { title = it }, label = L.scheduleTitle, enabled = !saving)
        Spacer(Modifier.height(DshSpace.s12))
        DlTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = L.schedulePrompt,
            enabled = !saving,
            singleLine = false,
            minLines = 2,
        )
        DshDialogError(error)
        DshDialogButtons(
            dismissLabel = L.cancel,
            onDismiss = requestDismiss,
            confirmLabel = if (saving) L.saving else L.save,
            onConfirm = { onSave(title, prompt) },
            enabled = !saving,
            confirmEnabled = prompt.isNotBlank() && (title != task.title || prompt != task.prompt),
            leadingLabel = L.delete,
            onLeading = { confirmDelete = true },
        )
    }
    if (confirmDelete) {
        DshConfirmDialog(
            title = L.scheduleDelete,
            message = L.scheduleDeleteMessage,
            confirmLabel = L.delete,
            danger = true,
            saving = saving,
            icon = TrashOutline16,
            onDismiss = { confirmDelete = false },
            onConfirm = { confirmDelete = false; onDelete() },
        )
    }
}
