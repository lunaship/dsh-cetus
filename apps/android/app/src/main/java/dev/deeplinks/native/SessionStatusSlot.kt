package dev.deeplinks.native

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.deeplinks.core.L
import dev.deeplinks.core.previewDetected
import dev.deeplinks.core.slotReconnectMeta
import dev.deeplinks.native.ui.v4.DlAction
import dev.deeplinks.native.ui.v4.DlButton
import dev.deeplinks.native.ui.v4.DlButtonStyle
import dev.deeplinks.native.ui.v4.DlSpinner
import dev.deeplinks.native.ui.v4.DlStatusKind
import dev.deeplinks.native.ui.v4.DlStatusSlot
import dev.deeplinks.native.ui.v4.DlTone
import dev.deeplinks.native.ui.v4.topStatus
import dev.deeplinks.native.util.StreamBannerKind

/** 对话页状态槽里的一条状态（v4 4.1 / 4.5 / 4.8）。一次只显示优先级最高的那一条。 */
internal sealed interface SessionStatus {
    val kind: DlStatusKind

    /** 断线 / 电脑不可达：最高优先级。[failed] 时用红色浅底，否则是等待色。 */
    data class Offline(
        val title: String,
        val meta: String?,
        val failed: Boolean,
        val actions: List<DlAction>,
    ) : SessionStatus {
        override val kind get() = DlStatusKind.Disconnected
    }

    /** 目标和计划合成一条。 */
    data class Goal(
        val goal: SessionGoal?,
        val summary: String?,
        val plan: List<MobileTodoItem>,
    ) : SessionStatus {
        override val kind get() = DlStatusKind.Goal
    }

    /** 插件看到了本机 dev server 端口。 */
    data class Preview(val ports: List<Int>) : SessionStatus {
        override val kind get() = DlStatusKind.Preview
    }
}

/**
 * 把各路状态收成候选列表，再按优先级（断线 > 待处理 > 目标 > 预览）取一条。
 * 待处理（审批 / 提问）走决策栏，不进这里。
 */
internal fun sessionStatus(
    stream: StreamBannerKind,
    unreachableHost: String?,
    goal: SessionGoal? = null,
    goalSummary: String? = null,
    plan: List<MobileTodoItem> = emptyList(),
    running: Boolean = false,
    previewPorts: List<Int>,
    onRetryStream: () -> Unit = {},
    onRetryHost: () -> Unit = {},
    onOpenDevice: () -> Unit = {},
): SessionStatus? {
    val candidates = buildList {
        if (unreachableHost != null) {
            add(
                SessionStatus.Offline(
                    title = L.cannotConnectHost.format(unreachableHost),
                    meta = null,
                    failed = false,
                    actions = listOf(
                        DlAction(L.deviceAndPairing, onOpenDevice, DlButtonStyle.Text),
                        DlAction(L.retry, onRetryHost, DlButtonStyle.Text),
                    ),
                ),
            )
        }
        if (stream != StreamBannerKind.Hidden) {
            val title = when (stream) {
                StreamBannerKind.Connecting -> L.connecting
                StreamBannerKind.Failed -> L.connectionFailedReconnecting
                else -> L.disconnectedReconnecting
            }
            add(
                SessionStatus.Offline(
                    title = title,
                    meta = L.slotReconnectMeta,
                    failed = stream == StreamBannerKind.Failed,
                    actions = listOf(DlAction(L.retry, onRetryStream, DlButtonStyle.Text)),
                ),
            )
        }
        goalStatus(goal, goalSummary, plan, running)?.let(::add)
        if (previewPorts.isNotEmpty()) add(SessionStatus.Preview(previewPorts))
    }
    return candidates.topStatus { it.kind }
}

/**
 * 目标 + 计划合成一条；都没有时为空。对话页把它停靠在输入框上沿（[GoalStatusSlot]），
 * 顶部状态槽只剩断线和预览。
 */
internal fun goalStatus(
    goal: SessionGoal?,
    goalSummary: String?,
    plan: List<MobileTodoItem>,
    running: Boolean,
): SessionStatus.Goal? {
    // 已完成的目标不再常驻；计划全部做完且没有进行中的目标时也收起
    val live = goal?.takeIf { it.phase != "complete" }
    val openPlan = plan.takeIf { p -> live != null || p.any { planItemKind(it.status) != PlanItemKind.Done } }.orEmpty()
    // 没有结构化目标时，推断出的目标只在运行中显示，避免历史会话常驻一条旧目标
    val summary = goalSummary?.takeIf { goal == null && running && it.isNotBlank() }
    return if (live != null || openPlan.isNotEmpty() || summary != null) SessionStatus.Goal(live, summary, openPlan) else null
}

/** 顶栏下方的状态槽。[status] 为空时不占位。 */
@Composable
internal fun SessionStatusSlot(
    status: SessionStatus?,
    control: SessionControlController,
    modifier: Modifier = Modifier,
) {
    when (status) {
        null -> Unit
        is SessionStatus.Offline -> OfflineStatusSlot(status, modifier)
        is SessionStatus.Goal -> {
            var expanded by remember(status.goal?.ref?.id) { mutableStateOf(false) }
            SessionGoalStatus(status.goal, status.summary, status.plan, control, expanded, { expanded = it }, modifier)
        }
        is SessionStatus.Preview -> PreviewStatusSlot(status, modifier)
    }
}

@Composable
internal fun OfflineStatusSlot(status: SessionStatus.Offline, modifier: Modifier = Modifier) {
    DlStatusSlot(
        title = status.title,
        meta = status.meta,
        modifier = modifier,
        tone = if (status.failed) DlTone.Err else DlTone.Wait,
        icon = if (status.failed) WarningOutline16 else null,
        leading = if (status.failed) {
            null
        } else {
            { Box(Modifier.size(DshIconSize.md), contentAlignment = Alignment.Center) { DlSpinner() } }
        },
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(DshSpace.s4), verticalAlignment = Alignment.CenterVertically) {
                status.actions.forEach { DlButton(it, compact = true) }
            }
        },
    )
}

@Composable
internal fun PreviewStatusSlot(status: SessionStatus.Preview, modifier: Modifier = Modifier) {
    DlStatusSlot(
        title = status.ports.joinToString(" · ") { L.previewDetected.format(it) },
        modifier = modifier,
        icon = GlobeOutline16,
    )
}
