package dev.deeplinks.core

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.deeplinks.native.SessionBackgroundMonitorService
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
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val approvalId = intent.getStringExtra(EXTRA_APPROVAL_ID) ?: return
        val approve = intent.getBooleanExtra(EXTRA_APPROVE, false)
        val host = HostStore.load(context).resolveFromIntent(intent) ?: return
        // 兜底：锁屏状态下不直接批准，改走 App 内审批卡。
        if (approve) {
            val km = context.getSystemService(KeyguardManager::class.java)
            if (km != null && km.isDeviceLocked) {
                DshNotifier.notifyApprovalNeedsUnlock(context, host, sessionId)
                return
            }
        }
        // 开关已被关闭：订阅已停，插件不再认这台手机能处理该审批；收回通知，交给电脑网页
        if (!WorkspacePrefs(context).backgroundTakeover) {
            DshNotifier.cancelApproval(context, host, sessionId)
            return
        }
        SessionBackgroundMonitorService.answerApproval(context, host, sessionId, approvalId, approve)
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
