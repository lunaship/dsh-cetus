package dev.deeplinks.core

import dev.deeplinks.native.util.compactDuration

/** 常驻通知上的会话状态。 */
internal enum class TaskMonitorPhase {
    Running,
    AwaitingApproval,
    AwaitingInput,
    Completed,
}

/** 拼通知前的输入。todos 为空表示没有清单，进度条用不确定样式。 */
internal data class TaskProgressSnapshot(
    val title: String,
    val phase: TaskMonitorPhase,
    val todosDone: Int? = null,
    val todosTotal: Int? = null,
    val step: Long? = null,
    val elapsedMs: Long? = null,
)

internal data class TaskProgressLabels(
    val running: String,
    val awaitingApproval: String,
    val awaitingInput: String,
    val completed: String,
    val step: String,
    val elapsed: String,
    val publicText: String,
)

/** 通知上实际显示的标题、正文、进度和锁屏公开版。 */
internal data class TaskProgressContent(
    val title: String,
    val text: String,
    val progress: Int,
    val progressMax: Int,
    val indeterminate: Boolean,
    val publicText: String,
)

internal data class TaskProgressState(
    val title: String,
    val awaitingApproval: Boolean = false,
    val awaitingAnswer: Boolean = false,
    val completed: Boolean = false,
    val todosDone: Int? = null,
    val todosTotal: Int? = null,
    val step: Long? = null,
    val startedAtMs: Long? = null,
) {
    fun snapshot(nowMs: Long): TaskProgressSnapshot = TaskProgressSnapshot(
        title = title.ifBlank { "DeepLinks" },
        phase = when {
            completed -> TaskMonitorPhase.Completed
            awaitingApproval -> TaskMonitorPhase.AwaitingApproval
            awaitingAnswer -> TaskMonitorPhase.AwaitingInput
            else -> TaskMonitorPhase.Running
        },
        todosDone = todosDone,
        todosTotal = todosTotal,
        step = step,
        elapsedMs = startedAtMs?.let { (nowMs - it).coerceAtLeast(0) },
    )
}

internal sealed class TaskProgressEvent {
    data object ApprovalAsked : TaskProgressEvent()
    data object ApprovalDecided : TaskProgressEvent()
    data object QuestionAsked : TaskProgressEvent()
    data object QuestionResolved : TaskProgressEvent()
    data class TurnStarted(val atMs: Long) : TaskProgressEvent()
    data object TurnEnded : TaskProgressEvent()
    data class ToolStep(val step: Long?) : TaskProgressEvent()
    data class Todos(val done: Int, val total: Int) : TaskProgressEvent()
    data class Ignored(val type: String) : TaskProgressEvent()
}

internal const val TASK_PROGRESS_MIN_INTERVAL_MS = 2_000L

private val COMPLETED_TODO = setOf("done", "completed", "complete")

/** 流式片段不改常驻通知。其余事件可以改，但仍受 2 秒节流。 */
internal fun taskProgressEventChangesNotification(event: TaskProgressEvent): Boolean = event !is TaskProgressEvent.Ignored

internal fun shouldPostTaskProgress(lastPostedAtElapsed: Long, nowElapsed: Long, force: Boolean): Boolean {
    if (force || lastPostedAtElapsed <= 0L) return true
    return nowElapsed - lastPostedAtElapsed >= TASK_PROGRESS_MIN_INTERVAL_MS
}

internal fun todoProgressCounts(statuses: List<String>): Pair<Int, Int> {
    val done = statuses.count { it.lowercase() in COMPLETED_TODO }
    return done to statuses.size
}

internal fun reduceTaskProgress(state: TaskProgressState, event: TaskProgressEvent): TaskProgressState = when (event) {
    TaskProgressEvent.ApprovalAsked -> state.copy(awaitingApproval = true, completed = false)
    TaskProgressEvent.ApprovalDecided -> state.copy(awaitingApproval = false)
    TaskProgressEvent.QuestionAsked -> state.copy(awaitingAnswer = true, completed = false)
    TaskProgressEvent.QuestionResolved -> state.copy(awaitingAnswer = false)
    is TaskProgressEvent.TurnStarted -> state.copy(
        completed = false,
        awaitingApproval = false,
        startedAtMs = event.atMs.takeIf { it > 0L } ?: state.startedAtMs,
    )
    TaskProgressEvent.TurnEnded -> state.copy(completed = true, awaitingApproval = false, awaitingAnswer = false)
    is TaskProgressEvent.ToolStep -> if (event.step != null && event.step >= 0) state.copy(step = event.step) else state
    is TaskProgressEvent.Todos -> if (event.total <= 0) {
        state.copy(todosDone = null, todosTotal = null)
    } else {
        state.copy(todosDone = event.done.coerceIn(0, event.total), todosTotal = event.total)
    }
    is TaskProgressEvent.Ignored -> state
}

/**
 * 标题是会话名。正文是状态，有清单、步号和耗时时用间隔号接在后面。
 * 没有 todos 时进度不确定；todos 全部完成时进度满格。
 */
internal fun taskProgressContent(snapshot: TaskProgressSnapshot, labels: TaskProgressLabels): TaskProgressContent {
    val status = when (snapshot.phase) {
        TaskMonitorPhase.Running -> labels.running
        TaskMonitorPhase.AwaitingApproval -> labels.awaitingApproval
        TaskMonitorPhase.AwaitingInput -> labels.awaitingInput
        TaskMonitorPhase.Completed -> labels.completed
    }
    val total = snapshot.todosTotal
    val indeterminate = total == null || total <= 0
    val progressMax = if (indeterminate) 0 else total
    val progress = if (indeterminate) 0 else (snapshot.todosDone ?: 0).coerceIn(0, total)
    val parts = mutableListOf(status)
    if (!indeterminate) parts += "$progress/$progressMax"
    val step = snapshot.step
    if (step != null && step > 0) parts += labels.step.format(step)
    val elapsed = snapshot.elapsedMs
    if (elapsed != null && elapsed >= 0) parts += labels.elapsed.format(compactDuration(elapsed))
    return TaskProgressContent(
        title = snapshot.title.ifBlank { "DeepLinks" },
        text = parts.joinToString(" · "),
        progress = progress,
        progressMax = progressMax,
        indeterminate = indeterminate,
        publicText = labels.publicText,
    )
}
