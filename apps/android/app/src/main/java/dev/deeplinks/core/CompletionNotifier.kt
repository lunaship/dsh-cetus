package dev.deeplinks.core

import android.content.Context
import androidx.core.app.NotificationCompat
import dev.deeplinks.R
import dev.deeplinks.native.util.WorkspacePrefs
import dev.deeplinks.native.util.compactDuration

/**
 * 定时任务结束，或普通任务跑得够久时的完成通知。
 * 正文只有状态和耗时；回复首行只出现在展开正文，锁屏版不含它。
 */
fun notifySessionCompletion(
    context: Context,
    host: Host,
    sessionId: String,
    sessionTitle: String,
    notice: CompletionNotice,
) {
    if (!notice.notify || !WorkspacePrefs(context).notifyOnDone) return
    val text = completionNoticeText(sessionTitle, notice, completionLabels())
    var builder = DshNotifier.base(context, host, sessionId)
        .setContentTitle(text.title)
        .setContentText(text.body)
        .setAutoCancel(true)
    if (text.expanded != null) {
        builder = builder.setStyle(NotificationCompat.BigTextStyle().bigText(text.expanded))
    }
    val publicNotification = NotificationCompat.Builder(context, "dsh_tasks")
        .setSmallIcon(R.drawable.ic_stat_dsh)
        .setContentTitle("DeepLinks")
        .setContentText(text.publicText)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .build()
    builder.setPublicVersion(publicNotification)
    DshNotifier.postNotification(context, DshNotifier.notificationId(host, sessionId, 2), builder.build())
}

private fun completionLabels(): CompletionNoticeLabels = CompletionNoticeLabels(
    scheduleDone = L.scheduleDoneTitle,
    scheduleFailed = L.scheduleFailedTitle,
    scheduleStopped = L.scheduleStoppedTitle,
    taskDone = L.taskDoneTitle,
    taskFailed = L.taskFailedTitle,
    taskStopped = L.taskStoppedTitle,
    statusDone = L.completionStatusDone,
    statusFailed = L.completionStatusFailed,
    statusStopped = L.completionStatusStopped,
    bodyWithDuration = L.completionBody,
    publicWithDuration = L.completionPublic,
    publicStatus = L.completionPublicStatus,
    duration = ::compactDuration,
)
