package dev.deeplinks.devices

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.deeplinks.core.DshType
import dev.deeplinks.core.dshRipple
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.Host
import dev.deeplinks.core.DshS
import dev.deeplinks.native.DshRadius
import dev.deeplinks.native.DshSheetShape
import dev.deeplinks.native.ui.DshSheetGrabber
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

/** 设备卡下方的说明条：文案 + 至多两个文字操作（48dp 热区）。 */
@Composable
internal fun DevicesNotice(
    message: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(DshRadius.md))
            .background(Dsh.bgSubtle)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = Dsh.labelSecondary,
            style = DshType.captionRelaxed,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        )
        listOfNotNull(
            actionLabel?.let { Triple(it, onAction, Dsh.labelPrimary) },
            secondaryLabel?.let { Triple(it, onSecondary, Dsh.labelTertiary) },
        ).forEach { (label, onClick, color) ->
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .widthIn(min = 48.dp)
                    .clip(RoundedCornerShape(DshRadius.sm))
                    .clickable(onClick = onClick)
                    .semantics {
                        role = Role.Button
                        contentDescription = label
                    }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = color, fontWeight = FontWeight(600), style = DshType.caption)
            }
        }
    }
}

// ---------- 工作区内的设备面板 ----------

/**
 * 当前电脑的状态 + 全部操作收在一张底部面板里：单设备没有「选电脑」这一步，
 * 所以不再跳到设备页再点一次卡片回来。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Dsh.bgSidePanel,
        contentColor = Dsh.labelPrimary,
        shape = DshSheetShape,
        scrimColor = Dsh.bgOverlay,
        dragHandle = null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            DshSheetGrabber()
            Text(
                s.pairingManage,
                color = Dsh.labelPrimary,
                style = DshType.headline,
                fontWeight = FontWeight(600),
            )
            Spacer(Modifier.height(12.dp))
            if (device == null) {
                Text(
                    notice ?: s.loading,
                    color = Dsh.labelSecondary,
                    style = DshType.body,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                return@Column
            }
            DeviceCard(device = device, onOpen = null, onUnpair = {}, showMenu = false)
            if (device.host.needsCloudRescan) {
                DevicesNotice(message = s.relayRouteExpired, actionLabel = s.restoreCloudScan, onAction = onRescan)
            }
            notice?.let { DevicesNotice(message = it, actionLabel = s.resync, onAction = onRecheck) }
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(DshRadius.lg))
                    .background(Dsh.bgSubtle),
            ) {
                DeviceSheetAction(Icons.Default.Refresh, s.recheckConnection, onClick = onRecheck)
                if (device.host.hasRelay) {
                    DeviceSheetAction(
                        icon = Icons.Default.SwapHoriz,
                        label = s.connectionMode,
                        value = if (device.host.preferRelay) s.preferCloud else s.lanFirst,
                        onClick = { onTogglePreferRelay(device.host) },
                    )
                }
                DeviceSheetAction(Icons.Default.QrCodeScanner, s.replaceDevice, onClick = onReplace)
                DeviceSheetAction(Icons.Default.LinkOff, s.deleteDevice, danger = true, onClick = { onUnpair(device) })
            }
        }
    }
}

@Composable
internal fun DeviceSheetAction(
    icon: ImageVector,
    label: String,
    value: String? = null,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tint = if (danger) Dsh.error else Dsh.labelPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .background(if (pressed) Dsh.pressed else Color.Transparent)
            .clickable(interactionSource = interaction, indication = dshRipple(), onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = if (value != null) "$label, $value" else label
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (danger) Dsh.error else Dsh.labelSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = tint, style = DshType.body, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, color = Dsh.labelTertiary, style = DshType.caption, maxLines = 1)
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
 * 已配对电脑卡片（96–112dp）：图标 + 名称 + 点状状态 + 次要信息，
 * 连接偏好 / 解除配对收进「更多」菜单，主操作是整行点按进入工作区。
 */
@Composable
internal fun DeviceCard(
    device: DeviceUi,
    onOpen: (() -> Unit)?,
    onUnpair: () -> Unit,
    onTogglePreferRelay: (Host) -> Unit = {},
    showMenu: Boolean = true,
) {
    val s = DshS
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var menuOpen by remember { mutableStateOf(false) }
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
    val subtitle = buildList {
        add(hostDisplayName(device.host.baseUrl))
        add(connection)
        if (device.latencyMs != null && state == DeviceState.ONLINE) add("${device.latencyMs}ms")
    }.joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp)
            .clip(RoundedCornerShape(DshRadius.lg))
            .background(if (pressed) Dsh.pressed else Dsh.bgSubtle)
            .then(
                if (onOpen != null) {
                    Modifier
                        .clickable(interactionSource = interaction, indication = dshRipple(), onClick = onOpen)
                        .semantics { role = Role.Button }
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = "${device.host.name}, $stateLabel" }
            .padding(start = 12.dp, end = if (showMenu) 4.dp else 12.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonitorGlyph()
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    device.host.name,
                    color = Dsh.labelPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    statusLabel(state),
                    color = statusColor,
                    style = DshType.microRelaxed,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                color = Dsh.labelSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (device.host.needsCloudRescan) {
                Spacer(Modifier.height(4.dp))
                DeviceTag(s.restoreCloudTag, Dsh.error, Dsh.errorBg, monospace = false)
            }
        }
        if (showMenu) Box {
            val menuInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(interactionSource = menuInteraction, indication = dshRipple()) {
                        menuOpen = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = s.moreActions,
                    tint = Dsh.labelTertiary,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = Dsh.bgCard,
                shape = RoundedCornerShape(DshRadius.lg),
                tonalElevation = 0.dp,
            ) {
                if (device.host.hasRelay) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (device.host.preferRelay) s.viaLan else s.preferCloud,
                                color = Dsh.labelPrimary,
                                style = DshType.t14,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onTogglePreferRelay(device.host)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(s.deleteDevice, color = Dsh.error, style = DshType.t14) },
                    onClick = {
                        menuOpen = false
                        onUnpair()
                    },
                )
            }
        }
    }
}

@Composable
internal fun MonitorGlyph() {
    // 统一描边图标体系（ic_device_glyph vector），不再手绘像素风 Box 堆叠
    Icon(
        painter = painterResource(dev.deeplinks.R.drawable.ic_device_glyph),
        contentDescription = null,
        tint = Dsh.labelPrimary,
        modifier = Modifier.size(26.dp),
    )
}

@Composable
internal fun DeviceTag(text: String, color: Color, bg: Color, monospace: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            color = color,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            fontWeight = FontWeight(600),
            style = DshType.caption,
            lineHeight = 16.sp
        )
    }
}
