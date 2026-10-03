package dev.deeplinks.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import dev.deeplinks.core.Host
import dev.deeplinks.core.PairClient
import dev.deeplinks.core.pairFailNetworkBody
import dev.deeplinks.core.pairFailNetworkTitle
import dev.deeplinks.core.pairFailTipRemote
import dev.deeplinks.core.pairFailTipTailscale
import dev.deeplinks.core.pairFailTipWifi
import dev.deeplinks.core.pairFailTitle
import dev.deeplinks.core.pairFailTry
import dev.deeplinks.core.pairRescan
import dev.deeplinks.core.pairRouteLan
import dev.deeplinks.core.pairRouteRemote
import dev.deeplinks.core.pairWaitBody
import dev.deeplinks.core.pairWaitDevice
import dev.deeplinks.core.pairWaitHint
import dev.deeplinks.core.pairWaitRoute
import dev.deeplinks.core.pairWaitTitle
import dev.deeplinks.native.CloudOffOutline16
import dev.deeplinks.native.DshSpace
import dev.deeplinks.native.ui.v4.DlAction
import dev.deeplinks.native.ui.v4.DlButton
import dev.deeplinks.native.ui.v4.DlButtonStyle
import dev.deeplinks.native.ui.v4.DlListRow
import dev.deeplinks.native.ui.v4.DlRowTrailing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 等批准时的轮询间隔。插件的 pending 有效期是分钟级，2 秒足够跟手又不吵。 */
private const val PAIR_POLL_MS = 2_000L

/**
 * 轮询配对是否已被电脑批准（v4 1.5）。[poll] 默认走 [PairClient.approvalState]，测试可注入。
 * 批准 → [onApproved]；被拒或超时 → [onRejected]；网络抖动继续等。
 */
@Composable
internal fun PairApprovalPoller(
    host: Host,
    onApproved: () -> Unit,
    onRejected: () -> Unit,
    poll: (Host) -> PairClient.Approval = PairClient::approvalState,
) {
    val approved by rememberUpdatedState(onApproved)
    val rejected by rememberUpdatedState(onRejected)
    LaunchedEffect(host.baseUrl, host.token) {
        while (true) {
            when (withContext(Dispatchers.IO) { poll(host) }) {
                PairClient.Approval.Approved -> return@LaunchedEffect approved()
                PairClient.Approval.Rejected -> return@LaunchedEffect rejected()
                PairClient.Approval.Pending, PairClient.Approval.Unknown -> delay(PAIR_POLL_MS)
            }
        }
    }
}

/** 1.5 等电脑批准：转圈 + 已发给哪台电脑 + 本机名称 / 连接方式 + 取消。 */
@Composable
internal fun PairWaitingScreen(
    computerName: String,
    deviceName: String,
    viaRemote: Boolean,
    onCancel: () -> Unit,
) {
    val s = DshS
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
        Text(s.pairWaitBody.format(computerName), style = DshType.body, color = Dsh.labelSecondary, modifier = Inset)
        Text(s.pairWaitHint, style = DshType.body, color = Dsh.labelSecondary, modifier = Inset)
        Spacer(Modifier.height(DshSpace.s24))
        DlListRow(title = s.pairWaitDevice, trailing = DlRowTrailing.Value(deviceName, chevron = false))
        DlListRow(
            title = s.pairWaitRoute,
            trailing = DlRowTrailing.Value(if (viaRemote) s.pairRouteRemote else s.pairRouteLan, chevron = false),
        )
        Spacer(Modifier.weight(1f).heightIn(min = DshSpace.s32))
        DlButton(DlAction(s.cancel, onCancel, DlButtonStyle.Tonal), modifier = Inset.fillMaxWidth())
    }
}

/**
 * 1.6 配对失败：一句人话的原因 + 能操作的下一步。
 * [network] = 地址都连不上（给三条排查建议）；否则是配对码过期、证书不符等，直接写原因。
 */
@Composable
internal fun PairFailedScreen(
    message: String,
    network: Boolean,
    onRescan: () -> Unit,
    onBack: () -> Unit,
) {
    val s = DshS
    PairStatusColumn {
        Icon(CloudOffOutline16, contentDescription = null, tint = Dsh.err, modifier = Inset.size(40.dp))
        Spacer(Modifier.height(DshSpace.s24))
        Text(
            if (network) s.pairFailNetworkTitle else s.pairFailTitle,
            style = DshType.titleLarge,
            color = Dsh.labelPrimary,
            modifier = Inset.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(DshSpace.s8))
        Text(if (network) s.pairFailNetworkBody else message, style = DshType.body, color = Dsh.labelSecondary, modifier = Inset)
        if (network) {
            Spacer(Modifier.height(DshSpace.s24))
            Text(s.pairFailTry, style = DshType.supporting, color = Dsh.labelSecondary, modifier = Inset)
            Spacer(Modifier.height(DshSpace.s8))
            for (tip in listOf(s.pairFailTipWifi, s.pairFailTipTailscale, s.pairFailTipRemote)) {
                Row(Inset.fillMaxWidth().padding(vertical = DshSpace.s4)) {
                    Text("•", style = DshType.body, color = Dsh.labelSecondary)
                    Spacer(Modifier.width(DshSpace.s8))
                    Text(tip, style = DshType.body, color = Dsh.labelPrimary)
                }
            }
        }
        Spacer(Modifier.weight(1f).heightIn(min = DshSpace.s32))
        DlButton(DlAction(s.pairRescan, onRescan, DlButtonStyle.Filled), modifier = Inset.fillMaxWidth())
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
