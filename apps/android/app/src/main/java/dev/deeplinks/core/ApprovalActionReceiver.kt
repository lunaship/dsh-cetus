package dev.deeplinks.core

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.deeplinks.native.SessionBackgroundMonitorService
import dev.deeplinks.core.resolveFromIntentStrict
import dev.deeplinks.native.util.WorkspacePrefs

/**
 * 审批通知上「允许一次 / 拒绝」两个动作的处理者（2026-09-28 重设计 · 方案 8）。
 *
 * - 在 AndroidManifest 里注册为 `exported="false"`：只有本 App 发出的 PendingIntent 能触发它。
 * - Intent 只带 sessionId / approvalId 与主机三要素（后者用于校验），**令牌绝不进 PendingIntent**
 *   —— 进程内由 [HostStore.load] 按 Intent 指定的主机取。
 * - 通知点击只移交给前台监控服务，网络请求由服务持有到完成。
 * - 失败（断线 / 已被电脑端处理）不留假状态：清掉通知并把 App 打开到该会话，让用户在界面里处理。
 */
class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread({
            try {
                handle(context, intent)
            } finally {
                pending.finish()
            }
        }, "dsh-approval-action").apply { isDaemon = true }.start()
    }

    private fun handle(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val approvalId = intent.getStringExtra(EXTRA_APPROVAL_ID) ?: return
        val approve = intent.getBooleanExtra(EXTRA_APPROVE, false)
        val host = HostStore.load(context).resolveFromIntentStrict(intent)
        if (host == null) {
            cancelUnresolved(context, intent, sessionId)
            return
        }
        val prefs = WorkspacePrefs(context)
        // 兜底顺序：先看接管是否还开着，再看通知栏直批开关，最后才是锁屏。
        // 接管关闭时审批已交回电脑网页，旧通知上的「允许」不许再生效。
        val km = context.getSystemService(KeyguardManager::class.java)
        val locked = km != null && km.isDeviceLocked
        when (
            approvalReceiverDecision(
                approve = approve,
                backgroundTakeover = prefs.backgroundTakeover,
                quickApprove = prefs.allowApproveFromNotification,
                locked = locked,
            )
        ) {
            ApprovalReceiverDecision.Cancel -> DshNotifier.cancelApproval(context, host, sessionId)
            ApprovalReceiverDecision.ConfirmInApp -> DshNotifier.notifyApprovalConfirmInApp(context, host, sessionId)
            ApprovalReceiverDecision.NeedsUnlock -> DshNotifier.notifyApprovalNeedsUnlock(context, host, sessionId)
            ApprovalReceiverDecision.Answer ->
                SessionBackgroundMonitorService.answerApproval(context, host, sessionId, approvalId, approve)
        }
    }

    /** 通知指向的主机已经不在本机时，按 Intent 里的名字和地址算出同一条通知 id 并取消。 */
    private fun cancelUnresolved(context: Context, intent: Intent, sessionId: String) {
        val name = intent.getStringExtra(EXTRA_HOST_NAME)?.takeIf { it.isNotBlank() } ?: return
        val url = intent.getStringExtra(EXTRA_HOST_BASE_URL)?.takeIf { it.isNotBlank() } ?: return
        DshNotifier.cancelApproval(context, Host(name, url, ""), sessionId)
    }

    companion object {
        const val EXTRA_SESSION_ID = "approvalSessionId"
        const val EXTRA_APPROVAL_ID = "approvalId"
        const val EXTRA_APPROVE = "approvalApprove"

        /** 与 ApprovalCard / RequestState 同一套取值，别处改这里也要跟着改。 */
        const val OUTCOME_ALLOW = "allowed-once"
        const val OUTCOME_REJECT = "rejected"
    }
}

/** 通知栏审批动作的决策结果。 */
internal enum class ApprovalReceiverDecision { Cancel, ConfirmInApp, NeedsUnlock, Answer }

/**
 * 通知栏点了「允许 / 拒绝」之后怎么走，纯函数抽出来供单测（项目未引入 Robolectric）。
 *
 * 顺序即优先级：
 * 1. 接管已关闭：审批已交回电脑网页，旧通知直接收回；
 * 2. 通知栏直批已被关闭（例如关开关前弹出的旧通知）：不批准，提示去 App 内确认；
 * 3. 锁屏：不批准，提示解锁后在 App 内确认；
 * 4. 其余（拒绝、或允许且条件都满足）交给后台监控服务答复。
 */
internal fun approvalReceiverDecision(
    approve: Boolean,
    backgroundTakeover: Boolean,
    quickApprove: Boolean,
    locked: Boolean,
): ApprovalReceiverDecision = when {
    !backgroundTakeover -> ApprovalReceiverDecision.Cancel
    approve && !quickApprove -> ApprovalReceiverDecision.ConfirmInApp
    approve && locked -> ApprovalReceiverDecision.NeedsUnlock
    else -> ApprovalReceiverDecision.Answer
}
