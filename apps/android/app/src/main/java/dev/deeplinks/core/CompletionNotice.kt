package dev.deeplinks.core

/** 普通任务短于这个分钟数时，完成不发通知。 */
const val DEFAULT_LONG_TASK_MINUTES = 3

val LONG_TASK_MINUTE_CHOICES: List<Int> = listOf(1, 3, 5, 10, 30)

fun normalizeLongTaskMinutes(value: Int): Int =
    if (value in LONG_TASK_MINUTE_CHOICES) value else DEFAULT_LONG_TASK_MINUTES

enum class CompletionNoticeKind { None, Done, Failed, Stopped }

/**
 * 完成通知该不该发、正文里带不带回复首行。
 * 定时任务结束就发；普通任务（含子代理）只有观测到的运行时长达到阈值才发。
 * 回复首行只进展开正文，调用方必须把锁屏版限制在状态和耗时。
 */
data class CompletionNotice(
    val notify: Boolean,
    val schedule: Boolean,
    val kind: CompletionNoticeKind,
    val durationMs: Long?,
    val privateFirstLine: String?,
)

fun replyFirstLine(text: String?): String? {
    val line = text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
    return line.take(80)
}

fun decideCompletionNotice(
    origin: String,
    state: String,
    observedDurationMs: Long?,
    longTaskMinutes: Int,
    notifyOnDone: Boolean,
    showReplyFirstLine: Boolean,
    replyFirstLine: String?,
): CompletionNotice {
    val kind = when (state) {
        "completed" -> CompletionNoticeKind.Done
        "failed" -> CompletionNoticeKind.Failed
        "stopped" -> CompletionNoticeKind.Stopped
        else -> CompletionNoticeKind.None
    }
    val schedule = origin == "schedule"
    val thresholdMs = normalizeLongTaskMinutes(longTaskMinutes).toLong() * 60_000L
    val longEnough = observedDurationMs != null && observedDurationMs >= thresholdMs
    val notify = notifyOnDone && kind != CompletionNoticeKind.None && (schedule || longEnough)
    val duration = observedDurationMs?.coerceAtLeast(0)
    val first = if (notify && showReplyFirstLine) replyFirstLine(replyFirstLine) else null
    return CompletionNotice(
        notify = notify,
        schedule = schedule,
        kind = if (notify) kind else CompletionNoticeKind.None,
        durationMs = if (notify) duration else null,
        privateFirstLine = first,
    )
}

data class CompletionNoticeLabels(
    val scheduleDone: String,
    val scheduleFailed: String,
    val scheduleStopped: String,
    val taskDone: String,
    val taskFailed: String,
    val taskStopped: String,
    val statusDone: String,
    val statusFailed: String,
    val statusStopped: String,
    val bodyWithDuration: String,
    val publicWithDuration: String,
    val publicStatus: String,
    val duration: (Long) -> String,
)

data class CompletionNoticeText(
    val title: String,
    val body: String,
    val expanded: String?,
    val publicText: String,
)

/** 锁屏文案 [CompletionNoticeText.publicText] 只有状态和耗时，不包含回复首行。 */
fun completionNoticeText(
    sessionTitle: String,
    notice: CompletionNotice,
    labels: CompletionNoticeLabels,
): CompletionNoticeText {
    val status = when (notice.kind) {
        CompletionNoticeKind.Done -> labels.statusDone
        CompletionNoticeKind.Failed -> labels.statusFailed
        CompletionNoticeKind.Stopped -> labels.statusStopped
        CompletionNoticeKind.None -> labels.statusDone
    }
    val duration = notice.durationMs?.let(labels.duration)
    val body = if (duration != null) labels.bodyWithDuration.format(status, duration) else status
    val pattern = when {
        notice.schedule && notice.kind == CompletionNoticeKind.Failed -> labels.scheduleFailed
        notice.schedule && notice.kind == CompletionNoticeKind.Stopped -> labels.scheduleStopped
        notice.schedule -> labels.scheduleDone
        notice.kind == CompletionNoticeKind.Failed -> labels.taskFailed
        notice.kind == CompletionNoticeKind.Stopped -> labels.taskStopped
        else -> labels.taskDone
    }
    val title = pattern.format(sessionTitle.ifBlank { "cetus" })
    val publicText = if (duration != null) {
        labels.publicWithDuration.format(status, duration)
    } else {
        labels.publicStatus.format(status)
    }
    return CompletionNoticeText(
        title = title,
        body = body,
        expanded = notice.privateFirstLine,
        publicText = publicText,
    )
}

/**
 * 手机第一次看到会话处于活跃状态的时刻。
 * `session.list` 没有开始时间，耗时只能按这段观测窗口计算。
 */
class ActiveSinceTracker {
    private val starts = mutableMapOf<String, Long>()

    fun observe(sessionId: String, state: String, nowMs: Long) {
        if (sessionId.isBlank()) return
        if (isHostSessionActive(state)) starts.putIfAbsent(sessionId, nowMs)
    }

    fun finish(sessionId: String, nowMs: Long): Long? {
        val start = starts.remove(sessionId) ?: return null
        return (nowMs - start).coerceAtLeast(0)
    }

    fun clear() {
        starts.clear()
    }
}
