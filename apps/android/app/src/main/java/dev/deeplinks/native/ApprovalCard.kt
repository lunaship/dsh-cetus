package dev.deeplinks.native

import androidx.compose.runtime.Composable
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.L
import dev.deeplinks.core.decisionInBar
import dev.deeplinks.core.decisionWaitApproval
import dev.deeplinks.native.ui.DshChipTone
import dev.deeplinks.native.ui.DshStatusChip

/**
 * 消息流里的审批（v4 4.3）：只留一行状态。要你处理的那条在底部决策栏里（[ApprovalDecisionBar]），
 * 这里写「等你批准 · 见下方」；已处理、交给电脑、状态未知各一行。
 * 结果仍走 Web 协议：`allowed-once` / `rejected`。
 */
@Composable
internal fun ApprovalCard(msg: MobileMessage) {
    when {
        isTerminalRequestStatus(msg.requestStatus) -> ApprovalSentBadge(msg.outcome, msg.requestStatus)
        msg.requestStatus == REQUEST_UNKNOWN -> DshStatusChip(text = L.approvalStatusUnknown, tone = DshChipTone.Remote)
        // 方案 D1-A 诚实降级：没被手机接管的审批只能看状态，点了必然 409
        msg.requestStatus == REQUEST_PENDING && !msg.takenOverByPhone ->
            DshStatusChip(text = L.homeApprovalOnDesktop, tone = DshChipTone.Remote)
        else -> DshStatusChip(
            text = listOfNotNull(L.decisionWaitApproval, msg.toolName, L.decisionInBar).joinToString(" · "),
            tone = DshChipTone.Approval,
        )
    }
}

@Composable
private fun ApprovalSentBadge(outcome: String?, status: String?) {
    val allowed = when {
        status == REQUEST_CANCELLED || status == REQUEST_EXPIRED -> false
        outcome == "rejected" || outcome == "cancelled" -> false
        else -> true
    }
    val label = when (status) {
        REQUEST_CANCELLED -> L.approvalCancelled
        REQUEST_EXPIRED -> L.approvalExpired
        else -> if (allowed) L.approvalAllowedSent else L.approvalRejectedSent
    }
    DshStatusChip(
        text = label,
        tone = DshChipTone.Remote,
        leading = if (allowed) CheckOutline16 else CloseOutline16,
        leadingTint = if (allowed) Dsh.successContent else Dsh.labelTertiary,
    )
}
