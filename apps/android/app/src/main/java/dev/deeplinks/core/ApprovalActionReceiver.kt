package dev.deeplinks.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.deeplinks.native.MobileApiClient
import kotlin.concurrent.thread

/**
 * 审批通知上「允许一次 / 拒绝」两个动作的处理者（2026-09-28 重设计 · 方案 8）。
 *
 * - 在 AndroidManifest 里注册为 `exported="false"`：只有本 App 发出的 PendingIntent 能触发它。
 * - Intent 只带 sessionId / approvalId 与主机三要素（后者用于校验），**令牌绝不进 PendingIntent**
 *   —— 进程内由 [HostStore.current] 取。
 * - 提交是阻塞调用，所以走工作线程；用 goAsync() 让系统知道这段工作还没结束。
 * - 失败（断线 / 已被电脑端处理）不留假状态：清掉通知并把 App 打开到该会话，让用户在界面里处理。
 */
class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val approvalId = intent.getStringExtra(EXTRA_APPROVAL_ID) ?: return
        val approve = intent.getBooleanExtra(EXTRA_APPROVE, false)
        val host = HostStore.current(context) ?: return
        val pending = goAsync()
        thread {
            val ok = runCatching {
                MobileApiClient(host).answerApproval(
                    sessionId,
                    approvalId,
                    if (approve) OUTCOME_ALLOW else OUTCOME_REJECT,
                )
            }.getOrDefault(false)
            if (ok) {
                DshNotifier.markApprovalAnswered(context, host, sessionId, approve)
            } else {
                DshNotifier.cancelApproval(context, host, sessionId)
                DshNotifier.openSession(context, host, sessionId)
            }
            pending.finish()
        }
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
