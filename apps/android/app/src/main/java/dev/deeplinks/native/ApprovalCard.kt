package dev.deeplinks.native

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import dev.deeplinks.core.DshType

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deeplinks.core.dshRipple
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.L
import dev.deeplinks.native.ui.DshChipTone
import dev.deeplinks.native.ui.DshPillButton
import dev.deeplinks.native.ui.DshPillTone
import dev.deeplinks.native.ui.DshStatusChip

private enum class ApprovalChoice { AllowOnce, Reject }

/**
 * 工具审批卡（对齐参考 ApprovalCard + DSH Web）：
 * 单题单选 → 手动提交；仅在服务端接受后收成绿色确认徽章。
 * 结果仍走 Web 协议：`allowed-once` / `rejected`。
 */
@Composable
internal fun ApprovalCard(
    msg: MobileMessage,
    onAnswer: (approvalId: String, outcome: String, onDone: (Boolean) -> Unit) -> Unit,
) {
    var open by remember(msg.id) { mutableStateOf(true) }
    var selected by remember(msg.id) { mutableStateOf<ApprovalChoice?>(null) }
    val haptic = rememberDshHaptic()
    var sent by remember(msg.id, msg.requestStatus, msg.outcome) {
        mutableStateOf(isTerminalRequestStatus(msg.requestStatus))
    }
    var submitting by remember(msg.id) { mutableStateOf(false) }
    var submitError by remember(msg.id) { mutableStateOf<String?>(null) }
    var sentChoice by remember(msg.id, msg.outcome) {
        mutableStateOf(
            when (msg.outcome) {
                "rejected", "cancelled" -> ApprovalChoice.Reject
                "allowed-once" -> ApprovalChoice.AllowOnce
                else -> null
            },
        )
    }

    fun submit(choice: ApprovalChoice) {
        if (sent || submitting || msg.requestStatus == REQUEST_UNKNOWN) return
        val id = msg.approvalId
        if (id.isNullOrBlank()) return
        haptic(if (choice == ApprovalChoice.AllowOnce) DshHaptic.Confirm else DshHaptic.Reject)
        submitting = true
        submitError = null
        onAnswer(id, if (choice == ApprovalChoice.AllowOnce) "allowed-once" else "rejected") { ok ->
            submitting = false
            if (ok) {
                sent = true
                sentChoice = choice
            } else {
                selected = null
                submitError = L.approvalNotAccepted
            }
        }
    }

    if (!open && !sent) {
        val interaction = remember { MutableInteractionSource() }
        Text(
            L.openApproval,
            color = Dsh.labelPrimary,
            style = DshType.title,
            fontWeight = FontWeight(500),
            modifier = Modifier
                .clip(RoundedCornerShape(DshRadius.control))
                .background(Dsh.bgSubtle)
                .clickable(interactionSource = interaction, indication = dshRipple()) { open = true }
                .semantics {
                    role = Role.Button
                    contentDescription = L.openApproval
                }
                .heightIn(min = 48.dp)
                .padding(horizontal = DshSpace.s12, vertical = DshSpace.s8),
        )
        return
    }

    if (sent) {
        ApprovalSentBadge(
            choice = sentChoice ?: selected,
            status = msg.requestStatus,
        )
        return
    }
    // 方案 D1-A 诚实降级：这版 DSH 上插件接不到审批（approval/request 钩子不触发，实测），
    // 所以 takenOverByPhone 恒为 false——此时**不给选中/提交按钮**，否则点了必然 409。
    // 只有真的被手机接管（/requests 快照里有这条 pending）才画可交互的审批卡。
    if (msg.requestStatus == REQUEST_PENDING && !msg.takenOverByPhone) {
        DshStatusChip(text = L.homeApprovalOnDesktop, tone = DshChipTone.Remote)
        return
    }
    if (msg.requestStatus == REQUEST_UNKNOWN) {
        DshStatusChip(text = L.approvalStatusUnknown, tone = DshChipTone.Remote)
        return
    }

    // C5.3：不再用 heightIn(min = 160.dp) 撑高——它让选项与底部之间空出约 60dp
    // （截图 ChatApprovalsLightZh 的反馈）。卡片高度由内容决定。
    Column(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .fillMaxWidth(),
    ) {
        // 白卡片：需要处理的信号是标题旁的 6dp 琥珀点。不加描边、色边、阴影。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DshRadius.composer))
                .background(Dsh.bgCard),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = DshSpace.s12)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Dsh.warn),
                            )
                            Spacer(Modifier.width(DshSpace.s6))
                            Text(
                                L.approvalQuestion,
                                color = Dsh.labelPrimary,
                                style = DshType.titleSmall,
                                fontWeight = FontWeight(500),
                                lineHeight = 18.sp,
                            )
                        }
                        Spacer(Modifier.height(DshSpace.s4))
                        Text(
                            msg.text.ifBlank {
                                L.approvalRequest.format(msg.toolName ?: L.toolFallbackName)
                            },
                            color = Dsh.labelSecondary,
                            style = DshType.titleSmall,
                        )
                        // C3：对话内审批卡同样收不到工具参数（bb058bd 只在首页卡加过），
                        // 在工具名下方、选项上方补同一行说明，与首页审批卡共用一份文案。
                        Spacer(Modifier.height(DshSpace.s6))
                        Text(
                            L.approvalArgsMissing,
                            color = Dsh.labelSecondary,
                            style = DshType.supporting,
                            modifier = Modifier.padding(horizontal = 10.dp),
                        )
                        // C4：工具名只出现一次——副标题（请求授权执行 <工具>）已经写了，
                        // 这里不再重复一行灰色小字（截图 ChatApprovalsLightZh 的反馈）。
                    }
                    Spacer(Modifier.width(DshSpace.s8))
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable { open = false }
                            .semantics {
                                role = Role.Button
                                contentDescription = L.approvalDismiss
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(DshRadius.control)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                CloseOutline16,
                                contentDescription = null,
                                tint = Dsh.labelTertiary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(DshSpace.s8))

                ApprovalOptionRow(
                    label = L.allowOnce,
                    selected = selected == ApprovalChoice.AllowOnce,
                    radio = true,
                    onClick = {
                        submitError = null
                        if (selected != ApprovalChoice.AllowOnce) haptic(DshHaptic.Tick)
                        selected = ApprovalChoice.AllowOnce
                    },
                )
                ApprovalOptionRow(
                    label = L.reject,
                    selected = selected == ApprovalChoice.Reject,
                    radio = true,
                    onClick = {
                        submitError = null
                        if (selected != ApprovalChoice.Reject) haptic(DshHaptic.Tick)
                        selected = ApprovalChoice.Reject
                    },
                )
            }

            val shownError = submitError
            if (shownError != null) {
                Text(
                    shownError,
                    color = Dsh.error,
                    style = DshType.caption,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = DshSpace.s4)
                        .semantics { contentDescription = shownError },
                )
            }

            // C5：footer 只留提交按钮。审批永远只有一题，原来的 9dp 空心圆点
            // （「当前步」指示）对用户没有意义，看起来像渲染残留；上箭头图标也更像
            // 「收起」而不是「提交」——换成带文字的按钮，按所选项显示「拒绝 / 允许一次」，
            // 与输入框发送按钮的语义区分开。留在卡片同一层底色里：单独铺 bgCard 在浅色下
            // 与页面白底连成一片，卡片看起来像被截断。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = DshSpace.s8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                val canSend = selected != null && !submitting
                DshPillButton(
                    label = if (selected == ApprovalChoice.Reject) L.reject else L.allowOnce,
                    onClick = { selected?.let { submit(it) } },
                    enabled = canSend,
                    tone = DshPillTone.Tonal,
                )
            }
        }
    }
}

@Composable
private fun ApprovalOptionRow(
    label: String,
    selected: Boolean,
    radio: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.control))
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
                indication = dshRipple(),
                interactionSource = interaction,
            )
            .semantics {
                contentDescription = label
            }
            .heightIn(min = 48.dp)
            .padding(horizontal = DshSpace.s6, vertical = DshSpace.s6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(if (radio) CircleShape else RoundedCornerShape(DshRadius.control))
                .then(
                    if (selected) Modifier.background(Dsh.labelPrimary)
                    else Modifier.border(1.5.dp, Dsh.labelTertiary, if (radio) CircleShape else RoundedCornerShape(DshRadius.control)),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (radio) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (selected) Dsh.bgSurface else Color.Transparent),
                )
            } else if (selected) {
                Icon(
                    CheckOutline14,
                    contentDescription = null,
                    tint = Dsh.bgSurface,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Spacer(Modifier.width(DshSpace.s8))
        Text(
            label,
            color = if (selected) Dsh.labelPrimary else Dsh.labelSecondary,
            style = DshType.titleSmall,
        )
    }
}

@Composable
private fun ApprovalSentBadge(choice: ApprovalChoice?, status: String? = null) {
    val allowed = when {
        status == REQUEST_CANCELLED || status == REQUEST_EXPIRED -> false
        choice == ApprovalChoice.Reject -> false
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
        leading = if (allowed) CheckOutline14 else CloseOutline16,
        leadingTint = if (allowed) Dsh.successContent else Dsh.labelTertiary,
    )
}