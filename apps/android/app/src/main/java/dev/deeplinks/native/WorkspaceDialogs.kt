package dev.deeplinks.native

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.core.renameFieldLabel
import dev.deeplinks.native.ui.v4.DlAction
import dev.deeplinks.native.ui.v4.DlButton
import dev.deeplinks.native.ui.v4.DlButtonStyle
import dev.deeplinks.native.ui.v4.DlOverlayColor
import dev.deeplinks.native.ui.v4.DlTextField
import dev.deeplinks.native.ui.v4.DlTone
import dev.deeplinks.native.ui.v4.color
import dev.deeplinks.native.util.RenameDialogKind
import dev.deeplinks.native.util.renameDialogKind

/**
 * 全 App 唯一的弹窗外壳：遮罩 + 卡片 + 进出场（淡入 + 轻缩放）。外观同 v4 [dev.deeplinks.native.ui.v4.DlDialog]：
 * 28dp 圆角、无阴影、浮层底色，按钮全是文字按钮（危险操作红字）。
 * [content] 拿到 requestDismiss：所有关闭入口都先播出场动画，播完才回调 [onDismiss]；
 * [dismissible] 为 false（例如保存中）时遮罩与返回键不响应。
 */
@Composable
internal fun DshDialogFrame(
    onDismiss: () -> Unit,
    dismissible: Boolean = true,
    maxWidth: Dp = 320.dp,
    cardModifier: Modifier = Modifier,
    content: @Composable ColumnScope.(requestDismiss: () -> Unit) -> Unit,
) {
    val motion = dialogMotionState(onDismiss)
    val requestDismiss = { if (dismissible) motion.requestDismiss() }
    Dialog(
        onDismissRequest = requestDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .graphicsLayer { alpha = motion.alpha.value }
                .background(Dsh.bgOverlay)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = requestDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .fillMaxWidth(0.86f)
                    .then(cardModifier)
                    .graphicsLayer {
                        alpha = motion.alpha.value
                        scaleX = motion.scale.value
                        scaleY = motion.scale.value
                    }
                    .clip(RoundedCornerShape(DshRadius.modal))
                    .background(DlOverlayColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(start = DshSpace.s24, end = DshSpace.s24, top = DshSpace.s24, bottom = DshSpace.s16),
            ) {
                content(requestDismiss)
            }
        }
    }
}

@Composable
internal fun DshDialogTitle(title: String, icon: ImageVector? = null, iconTone: DlTone = DlTone.Err) {
    if (icon != null) {
        Icon(
            icon,
            contentDescription = null,
            tint = iconTone.color,
            modifier = Modifier.padding(bottom = DshSpace.s12).size(DshIconSize.lg),
        )
    }
    Text(
        title,
        color = Dsh.labelPrimary,
        style = DshType.titleLarge,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
internal fun DshDialogMessage(message: String, modifier: Modifier = Modifier) {
    Text(message, color = Dsh.labelSecondary, style = DshType.body, modifier = modifier.padding(top = DshSpace.s12))
}

@Composable
internal fun DshDialogError(error: String?) {
    if (error.isNullOrBlank()) return
    Text(
        error,
        color = Dsh.err,
        style = DshType.supporting,
        modifier = Modifier
            .padding(top = DshSpace.s8)
            .semantics { contentDescription = error },
    )
}

/**
 * 底部按钮行：全是文字按钮。「取消」+ 确认（危险操作红字）；[confirmLabel] 为空时只有关闭钮。
 * [leadingLabel] 是放在最左边的红字动作（5.13「清除目标」）。
 */
@Composable
internal fun DshDialogButtons(
    dismissLabel: String,
    onDismiss: () -> Unit,
    confirmLabel: String? = null,
    onConfirm: () -> Unit = {},
    danger: Boolean = false,
    enabled: Boolean = true,
    confirmEnabled: Boolean = enabled,
    leadingLabel: String? = null,
    onLeading: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = DshSpace.s20),
        horizontalArrangement = Arrangement.spacedBy(DshSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingLabel != null) {
            DlButton(DlAction(leadingLabel, onLeading, DlButtonStyle.Danger, enabled = enabled), compact = true)
        }
        Spacer(Modifier.weight(1f))
        DlButton(DlAction(dismissLabel, onDismiss, DlButtonStyle.Text, enabled = enabled), compact = true)
        if (confirmLabel != null) {
            DlButton(
                DlAction(
                    confirmLabel,
                    onConfirm,
                    if (danger) DlButtonStyle.Danger else DlButtonStyle.Text,
                    enabled = confirmEnabled,
                ),
                compact = true,
            )
        }
    }
}

@Composable
internal fun DshRenameDialog(
    currentName: String,
    error: String? = null,
    saving: Boolean = false,
    /** 标题/说明可换：会话改名与电脑改名共用同一个对话框。 */
    title: String = L.renameSession,
    message: String? = null,
    fieldLabel: String = L.renameFieldLabel,
    onDismiss: () -> Unit,
    onClearError: () -> Unit = {},
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    val kind = renameDialogKind(saving, error)
    val canSave = name.isNotBlank() && kind != RenameDialogKind.Saving
    DshDialogFrame(onDismiss = onDismiss, dismissible = kind != RenameDialogKind.Saving) { requestDismiss ->
        DshDialogTitle(title)
        if (message != null) DshDialogMessage(message)
        Spacer(Modifier.height(DshSpace.s16))
        DlTextField(
            value = name,
            onValueChange = {
                name = it
                if (error != null) onClearError()
            },
            label = fieldLabel,
            enabled = kind != RenameDialogKind.Saving,
            isError = kind == RenameDialogKind.Failed,
        )
        if (kind == RenameDialogKind.Failed) DshDialogError(error)
        DshDialogButtons(
            dismissLabel = L.cancel,
            onDismiss = requestDismiss,
            confirmLabel = if (kind == RenameDialogKind.Saving) L.saving else L.save,
            onConfirm = { onSave(name) },
            enabled = kind != RenameDialogKind.Saving,
            confirmEnabled = canSave,
        )
    }
}

@Composable
internal fun DshConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    danger: Boolean = false,
    error: String? = null,
    saving: Boolean = false,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
    icon: ImageVector? = null,
    iconTone: DlTone = DlTone.Err,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val haptic = rememberDshHaptic()
    val kind = renameDialogKind(saving, error)
    DshDialogFrame(onDismiss = onDismiss, dismissible = kind != RenameDialogKind.Saving) { requestDismiss ->
        DshDialogTitle(title, icon, iconTone)
        DshDialogMessage(message)
        if (kind == RenameDialogKind.Failed) DshDialogError(error)
        DshDialogButtons(
            dismissLabel = L.cancel,
            onDismiss = requestDismiss,
            confirmLabel = if (kind == RenameDialogKind.Saving) L.saving else confirmLabel,
            onConfirm = {
                // 危险确认给负向触觉，普通确认给正向触觉
                haptic(if (danger) DshHaptic.Reject else DshHaptic.Confirm)
                onConfirm()
            },
            danger = danger,
            enabled = kind != RenameDialogKind.Saving,
            leadingLabel = secondaryLabel,
            onLeading = onSecondary,
        )
    }
}
