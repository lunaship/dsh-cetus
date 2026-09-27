package dev.deeplinks.devices

import androidx.compose.runtime.getValue
import dev.deeplinks.native.DshTileShape
import dev.deeplinks.native.DshRadius
import dev.deeplinks.native.RefreshOutline16
import dev.deeplinks.native.ScanOutline16
import dev.deeplinks.native.SwapOutline16
import dev.deeplinks.native.UnlinkOutline16
import dev.deeplinks.core.DshType
import dev.deeplinks.core.dshRipple
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.Host
import dev.deeplinks.core.DshS
import dev.deeplinks.native.ChevronRightOutline14
import dev.deeplinks.native.ui.DshListActionRow
import dev.deeplinks.native.ui.DshListNote
import dev.deeplinks.native.ui.DshListSection
import dev.deeplinks.native.ui.DshSelectRow
import dev.deeplinks.native.ui.DshSheet
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** 需要用户处理的说明（线路过期 / 离线）：一张卡片，说明文字 + 至多两个按钮行。 */
@Composable
internal fun DevicesNotice(
    message: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
) {
    DshListSection {
        DshListNote(message)
        if (actionLabel != null) DshListActionRow(label = actionLabel, onClick = onAction)
        if (secondaryLabel != null) DshListActionRow(label = secondaryLabel, onClick = onSecondary)
    }
}

/**
 * 已配对电脑的全部分组（整页与工作区面板共用）：
 * 电脑卡 → 待处理说明 → 连接（重新检测 / 连接方式）→ 更换电脑 → 解除配对。
 */
@Composable
internal fun DeviceDetailSections(
    device: DeviceUi,
    notice: String?,
    onOpen: (() -> Unit)?,
    onRecheck: () -> Unit,
    onTogglePreferRelay: (Host) -> Unit,
    onRescan: () -> Unit,
    onRescanLater: (() -> Unit)?,
    onReplace: () -> Unit,
    onUnpair: () -> Unit,
) {
    val s = DshS
    DshListSection { DeviceCard(device = device, onOpen = onOpen) }
    if (device.host.needsCloudRescan) {
        DevicesNotice(
            message = s.relayRouteExpired,
            actionLabel = s.restoreCloudScan,
            onAction = onRescan,
            secondaryLabel = onRescanLater?.let { s.restoreCloudLater },
            onSecondary = onRescanLater ?: {},
        )
    }
    notice?.let { DevicesNotice(message = it, actionLabel = s.resync, onAction = onRecheck) }
    // 顺序：设备 → 设置 → 操作 → 危险操作。状态已在设备卡里，操作行不再重复显示。
    if (device.host.hasRelay) {
        DshListSection {
            DshSelectRow(
                title = s.connectionMode,
                icon = SwapOutline16,
                value = if (device.host.preferRelay) s.preferCloud else s.lanFirst,
                options = listOf(s.lanFirst to "lan", s.preferCloud to "cloud"),
                selectedId = if (device.host.preferRelay) "cloud" else "lan",
                onSelect = { _, id ->
                    if ((id == "cloud") != device.host.preferRelay) onTogglePreferRelay(device.host)
                },
            )
        }
    }
    DshListSection(footer = s.replaceDeviceHint) {
        DshListActionRow(label = s.recheckConnection, icon = RefreshOutline16, onClick = onRecheck)
        DshListActionRow(label = s.replaceDevice, icon = ScanOutline16, onClick = onReplace)
    }
    DshListSection {
        DshListActionRow(label = s.deleteDevice, icon = UnlinkOutline16, destructive = true, onClick = onUnpair)
    }
}

// ---------- 工作区内的设备面板 ----------

/**
 * 当前电脑的状态 + 全部操作收在一张底部面板里：单设备没有「选电脑」这一步，
 * 所以不再跳到设备页再点一次卡片回来。
 */
@Composable
internal fun DeviceSheet(
    device: DeviceUi?,
    notice: String?,
    onDismiss: () -> Unit,
    onRecheck: () -> Unit,
    onTogglePreferRelay: (Host) -> Unit,
    onRescan: () -> Unit,
    onReplace: () -> Unit,
    onUnpair: (DeviceUi) -> Unit,
) {
    val s = DshS
    DshSheet(onDismiss = onDismiss, title = s.pairingManage, skipPartiallyExpanded = true) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (device == null) {
                DshListSection { DshListNote(notice ?: s.loading) }
                return@Column
            }
            DeviceDetailSections(
                device = device,
                notice = notice,
                onOpen = null,
                onRecheck = onRecheck,
                onTogglePreferRelay = onTogglePreferRelay,
                onRescan = onRescan,
                onRescanLater = null,
                onReplace = onReplace,
                onUnpair = { onUnpair(device) },
            )
        }
    }
}

// ---------- 设备卡片 ----------

@Composable
internal fun statusLabel(state: DeviceState): String {
    val s = DshS
    return when (state) {
        DeviceState.CHECKING -> s.statusChecking
        DeviceState.ONLINE -> s.statusOnline
        DeviceState.OFFLINE -> s.statusOffline
        DeviceState.CONNECTING -> s.statusConnecting
    }
}

/**
 * 电脑卡（放在分组卡片里）：品牌色图标块 + 名称 + 状态点 · 地址 · 线路 · 延迟；
 * [onOpen] 非空时整卡点按进入工作区。
 */
@Composable
internal fun DeviceCard(
    device: DeviceUi,
    onOpen: (() -> Unit)?,
) {
    val s = DshS
    val state = device.state
    val statusColor = when (state) {
        DeviceState.ONLINE -> Dsh.successContent
        DeviceState.CONNECTING -> Dsh.brand400
        else -> Dsh.labelTertiary
    }
    val connection = when {
        device.host.hasRelay && device.host.preferRelay -> s.preferCloud
        device.host.hasRelay -> s.viaCloud
        else -> s.viaLan
    }
    val stateLabel = statusLabel(state)
    val meta = buildList {
        add(stateLabel)
        if (device.latencyMs != null && state == DeviceState.ONLINE) add("${device.latencyMs}ms")
        add(connection)
    }.joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onOpen != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = dshRipple(),
                        role = Role.Button,
                        onClick = onOpen,
                    )
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = "${device.host.name}, $stateLabel" }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(DshTileShape)
                .background(Dsh.brand400.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            MonitorGlyph(tint = Dsh.brand400)
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                device.host.name,
                color = Dsh.labelPrimary,
                style = DshType.headline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    meta,
                    color = Dsh.labelSecondary,
                    style = DshType.supporting,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                hostDisplayName(device.host.baseUrl),
                color = Dsh.labelTertiary,
                style = DshType.supporting,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (device.host.needsCloudRescan) {
                Spacer(Modifier.height(6.dp))
                DeviceTag(s.restoreCloudTag, Dsh.error, Dsh.errorBg)
            }
        }
        if (onOpen != null) {
            Spacer(Modifier.width(8.dp))
            Icon(ChevronRightOutline14, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
internal fun MonitorGlyph(tint: Color = Dsh.labelPrimary) {
    // 统一描边图标体系（ic_device_glyph vector），不再手绘像素风 Box 堆叠
    Icon(
        painter = painterResource(dev.deeplinks.R.drawable.ic_device_glyph),
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(28.dp),
    )
}

@Composable
internal fun DeviceTag(text: String, color: Color, bg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(DshRadius.sm))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(text, color = color, fontWeight = FontWeight(600), style = DshType.caption)
    }
}
