package dev.deeplinks.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.PairClient
import dev.deeplinks.core.pairFailTitle
import dev.deeplinks.core.pairRescan
import dev.deeplinks.core.pairRouteLan
import dev.deeplinks.core.pairRouteRemote
import dev.deeplinks.core.pairWaitBody
import dev.deeplinks.core.pairWaitDevice
import dev.deeplinks.core.pairWaitHint
import dev.deeplinks.core.pairWaitRoute
import dev.deeplinks.core.pairWaitTitle
import dev.deeplinks.core.PairingSession
import dev.deeplinks.core.PairApproval
import dev.deeplinks.core.PairFailure
import dev.deeplinks.core.pairRetryConnection
import dev.deeplinks.core.pairRouteUnknown
import dev.deeplinks.core.PairRecovery
import dev.deeplinks.core.awaitPairApproval
import dev.deeplinks.core.PairWaitResult
import dev.deeplinks.native.CloudOffOutline16
import dev.deeplinks.native.DshSpace
import dev.deeplinks.native.ui.v4.DlAction
import dev.deeplinks.native.ui.v4.DlButton
import dev.deeplinks.native.ui.v4.DlButtonStyle
import dev.deeplinks.native.ui.v4.DlListRow
import dev.deeplinks.native.ui.v4.DlRowTrailing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** v4 1.5：单任务、有限等待；暂停只保留候选，不重发 /pair。 */
@Composable
internal fun PairApprovalPoller(
    session: PairingSession,
    onApproved: () -> Unit,
    onRejected: () -> Unit,
    onPaused: (PairFailure) -> Unit,
    isCurrent: () -> Boolean,
    poll: suspend (PairingSession) -> PairApproval = { PairClient.approvalStateSealed(it) },
) {
    val approved by rememberUpdatedState(onApproved)
    val rejected by rememberUpdatedState(onRejected)
    val paused by rememberUpdatedState(onPaused)
    val current by rememberUpdatedState(isCurrent)
    LaunchedEffect(session.attemptId) {
        when (val result = awaitPairApproval(session, { withContext(Dispatchers.IO) { poll(it) } }, { current() })) {
            PairWaitResult.Approved -> approved()
            PairWaitResult.Rejected -> rejected()
            is PairWaitResult.Paused -> paused(result.failure)
            null -> Unit
        }
    }
}

/** 1.5 等电脑批准：转圈 + 已发给哪台电脑 + 本机名称 / 连接方式 + 取消。 */
@Composable
internal fun PairWaitingScreen(
    session: PairingSession,
    deviceName: String,
    onCancel: () -> Unit,
) {
    val s = DshS
    val routeLabel = when (session.pairRoute) {
        dev.deeplinks.core.remote.HostRoute.REMOTE -> s.pairRouteRemote
        dev.deeplinks.core.remote.HostRoute.LAN -> s.pairRouteLan
        null -> s.pairRouteUnknown
    }
    PairStatusColumn {
        CircularProgressIndicator(
            modifier = Inset.size(40.dp),
            color = Dsh.brand400,
            trackColor = Dsh.primarySoft,
            strokeWidth = 3.dp,
        )
        Spacer(Modifier.height(DshSpace.s24))
        Text(
            s.pairWaitTitle,
            style = DshType.titleLarge,
            color = Dsh.labelPrimary,
            modifier = Inset.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(DshSpace.s8))
        Text(s.pairWaitBody.format(session.host.name), style = DshType.body, color = Dsh.labelSecondary, modifier = Inset)
        Text(s.pairWaitHint, style = DshType.body, color = Dsh.labelSecondary, modifier = Inset)
        Spacer(Modifier.height(DshSpace.s24))
        DlListRow(title = s.pairWaitDevice, trailing = DlRowTrailing.Value(deviceName, chevron = false))
        DlListRow(
            title = s.pairWaitRoute,
            trailing = DlRowTrailing.Value(routeLabel, chevron = false),
        )
        Spacer(Modifier.weight(1f).heightIn(min = DshSpace.s32))
        DlButton(DlAction(s.cancel, onCancel, DlButtonStyle.Tonal), modifier = Inset.fillMaxWidth())
    }
}

/**
 * 1.6 配对失败：一句人话的原因 + 能操作的下一步。
 * 原因始终保留，恢复动作由失败类型决定。
 */
@Composable
internal fun PairFailedScreen(
    failure: PairFailure,
    onRescan: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val s = DshS
    PairStatusColumn {
        Icon(CloudOffOutline16, contentDescription = null, tint = Dsh.err, modifier = Inset.size(40.dp))
        Spacer(Modifier.height(DshSpace.s24))
        Text(
            s.pairFailTitle,
            style = DshType.titleLarge,
            color = Dsh.labelPrimary,
            modifier = Inset.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(DshSpace.s8))
        Text(failure.message, style = DshType.body, color = Dsh.labelSecondary, modifier = Inset)
        failure.suggestion?.let { suggestion ->
            Spacer(Modifier.height(DshSpace.s8))
            Text(suggestion, style = DshType.supporting, color = Dsh.labelSecondary, modifier = Inset)
        }
        Spacer(Modifier.weight(1f).heightIn(min = DshSpace.s32))
        val retry = failure.recovery == PairRecovery.RETRY
        DlButton(DlAction(if (retry) s.pairRetryConnection else s.pairRescan, if (retry) onRetry else onRescan, DlButtonStyle.Filled), modifier = Inset.fillMaxWidth())
        Spacer(Modifier.height(DshSpace.s8))
        DlButton(DlAction(s.back, onBack, DlButtonStyle.Text), modifier = Inset.fillMaxWidth())
    }
}

/** 列表行自带 20dp 内边距；其余元素用同一缩进，与行内文字对齐。 */
private val Inset = Modifier.padding(horizontal = DshSpace.s20)

@Composable
private fun PairStatusColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Dsh.bgBase)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DshSpace.s4, vertical = DshSpace.s24)
            .padding(top = DshSpace.s32),
        content = content,
    )
}
