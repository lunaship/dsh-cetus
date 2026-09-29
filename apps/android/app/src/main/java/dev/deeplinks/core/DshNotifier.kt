package dev.deeplinks.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.deeplinks.R
import dev.deeplinks.native.util.WorkspacePrefs
import dev.deeplinks.native.WorkspaceActivity
import java.util.concurrent.ConcurrentHashMap

/**
 * DSH 会话事件系统通知：审批请求（会话在后台等你处理）与任务完成 / 已停止。
 * 点击回到对应主机的工作台并直接打开该会话；仅当 App 不在前台时发（前台已有审批卡与运行状态）。
 */
object DshNotifier {
    const val TASK_MONITOR_NOTIFICATION_ID = 70_001
    private val answeredApprovalUntil = ConcurrentHashMap<Int, Long>()
    // 方案 8：两个频道（审批要「现在处理」= 高优先级，完成是「有空看」= 默认）
    private const val CHANNEL_ID_APPROVAL = "dsh_approvals"
    private const val CHANNEL_ID_TASK = "dsh_tasks"
    private const val CHANNEL_ID_MONITOR = "dsh_active_monitor"
    /** 阶段 8 之前的单频道 id，只用于清理。 */
    private const val LEGACY_CHANNEL_ID = "dsh_events"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            channelOf(CHANNEL_ID_APPROVAL, L.notifChannelApprovals, L.notifChannelApprovalsDesc, NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            channelOf(CHANNEL_ID_TASK, L.notifChannelTasks, L.notifChannelTasksDesc, NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            channelOf(CHANNEL_ID_MONITOR, L.notifChannelMonitor, L.notifChannelMonitorDesc, NotificationManager.IMPORTANCE_LOW),
        )
        // 升级遗留：阶段 8 之前只有一个 dsh_events 频道，装过旧版的设备上它会一直留着，
        // 用户在系统通知设置里看到三个频道、其中一个永远不会响。这里一次性清掉。
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
    }

    private fun channelOf(id: String, name: String, desc: String, importance: Int): NotificationChannel =
        NotificationChannel(id, name, importance).apply { description = desc }

    /** Required by Android while a user-started conversation is monitored in the background. */
    fun taskMonitorNotification(context: Context, host: Host, sessionId: String, title: String): Notification =
        base(context, host, sessionId, CHANNEL_ID_MONITOR)
            .setContentTitle(L.notifMonitorTitle)
            .setContentText(L.notifMonitorBody.format(title))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    fun cancelTaskMonitor(context: Context, host: Host, sessionId: String) {
        NotificationManagerCompat.from(context).cancel(TASK_MONITOR_NOTIFICATION_ID)
    }

    /** 审批请求：需要审批「工具名」；带 approvalId 时附「允许一次 / 拒绝」两个动作（方案 8）。 */
    fun notifyApproval(
        context: Context,
        host: Host,
        sessionId: String,
        toolName: String,
        approvalId: String? = null,
    ) {
        // 阶段 8 第 3 条：设置页的两个开关（存本机）决定发不发。关卡放在这里而不是调用点，
        // 是为了「一个地方管住所有通知」——调用点分散在 WorkspaceActivity 多处，漏一处就是 bug。
        if (!WorkspacePrefs(context).notifyOnApproval) return
        val builder = base(context, host, sessionId, CHANNEL_ID_APPROVAL)
            .setContentTitle(L.notifNeedApproval)
            .setContentText(L.notifNeedApprovalBody.format(toolName))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        if (!approvalId.isNullOrBlank()) {
            // 「允许一次」要求先解锁（Android 12+）；「拒绝」是安全的默认方向，不必解锁
            builder.addAction(approvalAction(context, host, sessionId, approvalId, true, L.allowOnce, 11))
            builder.addAction(approvalAction(context, host, sessionId, approvalId, false, L.reject, 12))
        }
        postNotification(context, notificationId(host, sessionId, 1), builder.build())
    }

    private fun approvalAction(
        context: Context,
        host: Host,
        sessionId: String,
        approvalId: String,
        approve: Boolean,
        label: String,
        requestCode: Int,
    ): NotificationCompat.Action {
        val intent = Intent(context, ApprovalActionReceiver::class.java).apply {
            host.putInto(this)
            putExtra(ApprovalActionReceiver.EXTRA_SESSION_ID, sessionId)
            putExtra(ApprovalActionReceiver.EXTRA_APPROVAL_ID, approvalId)
            putExtra(ApprovalActionReceiver.EXTRA_APPROVE, approve)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode + notificationId(host, sessionId, 1),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Action.Builder(R.drawable.ic_stat_dsh, label, pending)
        if (approve) builder.setAuthenticationRequired(true)
        return builder.build()
    }

    /**
     * 完成通知两个动作带出去的意图（方案 8）；消费端在 WorkspaceScreen 的一个 LaunchedEffect 里
     * 分派：看改动掀开右侧改动面板，回复把焦点交给输入框并弹键盘。
     */
    const val INTENT_ACTION_CHANGES = "openChanges"
    const val INTENT_ACTION_REPLY = "reply"
    const val EXTRA_ACTION_REQUEST_ID = "notificationActionRequestId"

    /** 完成通知的深链动作：带 sessionId + 一个意图 extra，不带令牌。 */
    private fun deepLinkAction(
        context: Context,
        host: Host,
        sessionId: String,
        label: String,
        extraKey: String,
        requestCode: Int,
    ): NotificationCompat.Action {
        val intent = Intent(context, WorkspaceActivity::class.java).apply {
            host.putInto(this)
            putExtra("sessionId", sessionId)
            putExtra(extraKey, true)
            // MainActivity may already be showing this same action for this session. A fresh
            // request id makes tapping the same notification action again observable to Compose.
            putExtra(EXTRA_ACTION_REQUEST_ID, android.os.SystemClock.elapsedRealtimeNanos())
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            requestCode + notificationId(host, sessionId, 2),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_dsh, label, pending).build()
    }

    /** 审批已被处理：通知文字改成结果，几秒后自己消失（方案 8）。 */
    fun markApprovalAnswered(context: Context, host: Host, sessionId: String, approve: Boolean) {
        val id = notificationId(host, sessionId, 1)
        val expiresAt = android.os.SystemClock.elapsedRealtime() + 4_000
        answeredApprovalUntil[id] = expiresAt
        val notification = base(context, host, sessionId, CHANNEL_ID_APPROVAL)
            .setContentTitle(L.notifNeedApproval)
            .setContentText(if (approve) L.approvalAllowedSent else L.approvalNotAccepted)
            .setAutoCancel(true)
            .build()
        postNotification(context, id, notification)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            {
                if (answeredApprovalUntil.remove(id, expiresAt)) NotificationManagerCompat.from(context).cancel(id)
            },
            4_000,
        )
    }

    /** 打开该会话（动作失败时的兜底，与点通知同一条路）。 */
    fun openSession(context: Context, host: Host, sessionId: String) {
        context.startActivity(
            Intent(context, WorkspaceActivity::class.java).apply {
                host.putInto(this)
                putExtra("sessionId", sessionId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
        )
    }

    /** 任务完成。 */
    fun notifyTaskDone(
        context: Context,
        host: Host,
        sessionId: String,
        title: String,
        /** 插件下发的「结果一句话」（阶段 2 的 lastResult）；没有就退回「会话名 已完成」。 */
        resultText: String? = null,
    ) {
        if (!WorkspacePrefs(context).notifyOnDone) return
        val summary = resultText?.trim()?.takeIf { it.isNotEmpty() }
        val sessionDone = L.notifTaskDoneBody.format(title)
        // 有结果一句话：标题写「会话「x」已完成」、正文写那句话（方案 8 稿 06 的形态）。
        // 没有（旧插件 / 还没产出结果）就沿用原来的「任务完成 / 会话「x」已完成」，
        // 不能两处都写同一句——标题与正文重复等于浪费一行。
        post(
            context = context,
            host = host,
            sessionId = sessionId,
            kind = 2,
            title = if (summary != null) sessionDone else L.notifTaskDone,
            text = sessionDone,
            bigText = summary,
            actions = listOf(
                dev.deeplinks.native.ChangesL.viewChanges to INTENT_ACTION_CHANGES,
                L.sendMessage to INTENT_ACTION_REPLY,
            ),
        )
    }

    /** 会话停止（非正常结束，如 interrupted/error/maxTokens）。 */
    fun notifyTaskFailed(context: Context, host: Host, sessionId: String, title: String, reason: String) {
        if (!WorkspacePrefs(context).notifyOnDone) return
        post(context, host, sessionId, 3, L.notifTaskStopped, L.notifTaskStoppedBody.format(title, reason))
    }

    /** 任务类通知的公共走法：默认频道 + 自动取消（审批那条自己带动作与频道）。 */
    private fun post(
        context: Context,
        host: Host,
        sessionId: String,
        kind: Int,
        title: String,
        text: String,
        bigText: String? = null,
        /** 额外动作（完成通知的「查看改动 / 回复」）；审批那条自己带动作与频道。 */
        actions: List<Pair<String, String>> = emptyList(),
    ) {
        var builder = base(context, host, sessionId)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
        // 结果一句话可能有好几行：折叠态只显示一行，展开用 BigTextStyle 看全
        if (bigText != null) builder = builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        actions.forEachIndexed { index, (label, extraKey) ->
            builder = builder.addAction(deepLinkAction(context, host, sessionId, label, extraKey, 21 + index))
        }
        postNotification(context, notificationId(host, sessionId, kind), builder.build())
    }

    fun cancelApproval(context: Context, host: Host, sessionId: String) {
        val id = notificationId(host, sessionId, 1)
        if (android.os.SystemClock.elapsedRealtime() >= (answeredApprovalUntil[id] ?: 0L)) {
            NotificationManagerCompat.from(context).cancel(id)
        }
    }

    /** 打开会话时清掉该会话的残留通知。 */
    fun cancelForSession(context: Context, host: Host, sessionId: String) {
        val nm = NotificationManagerCompat.from(context)
        for (kind in 1..3) nm.cancel(notificationId(host, sessionId, kind))
    }

    private fun postNotification(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // 用户可能在检查后立刻撤销权限；通知是可丢失的辅助能力。
        }
    }

    private fun base(
        context: Context,
        host: Host,
        sessionId: String,
        channelId: String = CHANNEL_ID_TASK,
    ): NotificationCompat.Builder {
        val intent = Intent(context, WorkspaceActivity::class.java).apply {
            host.putInto(this)
            putExtra("sessionId", sessionId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            notificationId(host, sessionId, 0),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    }

    private fun notificationId(host: Host, sessionId: String, kind: Int): Int =
        (host.slotKey.hashCode() * 31 + sessionId.hashCode() + kind * 10_007) and 0x7fffffff
}
