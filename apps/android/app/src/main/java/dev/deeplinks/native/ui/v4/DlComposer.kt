package dev.deeplinks.native.ui.v4

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.native.DshIconSize
import dev.deeplinks.native.DshRadius
import dev.deeplinks.native.DshSpace
import dev.deeplinks.native.MicOutline16
import dev.deeplinks.native.PlusOutline16
import dev.deeplinks.native.SendOutline16
import dev.deeplinks.native.ShieldOutline16
import dev.deeplinks.native.StopFill16

/** 发送键的四种状态：发送、停止（运行中）、语音（空输入）、不可发送（离线）。 */
enum class DlSendState { Send, Stop, Mic, Disabled }

/**
 * v4 输入区（4.1、5.6、4.8）：容器色整体，输入框在上；下方 `+`、模型 chip、权限 chip、发送键。
 * 附件缩略图放在输入框上方（[attachments]）。离线时传 [DlSendState.Disabled]：仍可编辑，不能发送。
 * [permissionRisk] 为完全权限时用等你色标出。
 */
@Composable
fun DlComposer(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    sendState: DlSendState,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    onStop: () -> Unit = {},
    onMic: () -> Unit = {},
    onAttach: (() -> Unit)? = null,
    modelLabel: String? = null,
    onModelClick: () -> Unit = {},
    permissionLabel: String? = null,
    permissionRisk: Boolean = false,
    onPermissionClick: () -> Unit = {},
    attachments: (@Composable RowScope.() -> Unit)? = null,
    /** 自定义输入框（如保住输入法 composition 的原生 EditText）；为空用内置 BasicTextField。 */
    field: (@Composable () -> Unit)? = null,
    /** 自定义下方控件行左侧（+ / 模型 / 权限）；为空用内置三项。 */
    controls: (@Composable RowScope.() -> Unit)? = null,
    /** 自定义发送键；为空按 [sendState] 画。 */
    sendButton: (@Composable () -> Unit)? = null,
    /** 控件行下方的提示（发送失败等）。 */
    footer: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .padding(horizontal = DshSpace.s12, vertical = DshSpace.s8)
            .fillMaxWidth()
            .background(Dsh.surface1, RoundedCornerShape(DshRadius.block))
            .padding(start = DshSpace.s8, end = DshSpace.s8, top = DshSpace.s12, bottom = DshSpace.s8),
    ) {
        if (attachments != null) {
            Row(
                modifier = Modifier.padding(start = DshSpace.s4, end = DshSpace.s4, bottom = DshSpace.s8),
                horizontalArrangement = Arrangement.spacedBy(DshSpace.s8),
                content = attachments,
            )
        }
        if (field != null) field() else BasicTextField(
            value = text,
            onValueChange = onTextChange,
            textStyle = DshType.body.copy(color = Dsh.labelPrimary),
            cursorBrush = SolidColor(Dsh.brand400),
            maxLines = 6,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DshSpace.s24)
                .padding(start = DshSpace.s8, end = DshSpace.s8, bottom = DshSpace.s8),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Text(placeholder, style = DshType.body, color = Dsh.tertiaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (controls != null) {
                    controls()
                } else {
                    DlComposerDefaultControls(onAttach, modelLabel, onModelClick, permissionLabel, permissionRisk, onPermissionClick)
                }
            }
            if (sendButton != null) sendButton() else DlSendButton(sendState, onSend, onStop, onMic)
        }
        footer?.invoke()
    }
}

@Composable
private fun DlComposerDefaultControls(
    onAttach: (() -> Unit)?,
    modelLabel: String?,
    onModelClick: () -> Unit,
    permissionLabel: String?,
    permissionRisk: Boolean,
    onPermissionClick: () -> Unit,
) {
    if (onAttach != null) {
        DlIconButton(PlusOutline16, DshS.addAttachment, onAttach, tint = Dsh.labelSecondary)
    }
    if (modelLabel != null) DlComposerChip(modelLabel, null, Dsh.labelSecondary, onModelClick)
    if (permissionLabel != null) {
        DlComposerChip(
            permissionLabel,
            ShieldOutline16,
            if (permissionRisk) Dsh.wait else Dsh.labelSecondary,
            onPermissionClick,
        )
    }
}

@Composable
private fun DlComposerChip(
    label: String,
    icon: ImageVector?,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .heightIn(min = DlSize.button)
            .clip(DlPill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = DshSpace.s8),
        horizontalArrangement = Arrangement.spacedBy(DshSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(DshIconSize.sm))
        Text(label, style = DshType.supporting, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DlSendButton(state: DlSendState, onSend: () -> Unit, onStop: () -> Unit, onMic: () -> Unit) {
    val (icon, label) = when (state) {
        DlSendState.Stop -> StopFill16 to DshS.stop
        DlSendState.Mic -> MicOutline16 to DshS.voiceInput
        DlSendState.Send, DlSendState.Disabled -> SendOutline16 to DshS.sendMessage
    }
    val container = when (state) {
        DlSendState.Send -> Dsh.brand400
        DlSendState.Stop -> Dsh.inkFill
        DlSendState.Mic -> Dsh.surface1
        DlSendState.Disabled -> Dsh.surface2
    }
    val tint = when (state) {
        DlSendState.Send -> Dsh.onBrand
        DlSendState.Stop -> Dsh.onInk
        DlSendState.Mic -> Dsh.labelSecondary
        DlSendState.Disabled -> Dsh.tertiaryText
    }
    Box(
        modifier = Modifier
            .size(DlSize.button)
            .clickable(
                enabled = state != DlSendState.Disabled,
                role = Role.Button,
                onClick = when (state) {
                    DlSendState.Stop -> onStop
                    DlSendState.Mic -> onMic
                    else -> onSend
                },
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(DlSize.send).background(container, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(DshIconSize.sm))
        }
    }
}
