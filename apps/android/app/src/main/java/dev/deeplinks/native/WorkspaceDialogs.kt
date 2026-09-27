package dev.deeplinks.native

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
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
import dev.deeplinks.native.ui.DshTextField
import dev.deeplinks.native.util.RenameDialogKind
import dev.deeplinks.native.util.renameDialogKind

/**
 * 全 App 唯一的弹窗外壳：遮罩 + 卡片 + 进出场（淡入 + 轻缩放）。
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
                    .shadow(16.dp, RoundedCornerShape(DshRadius.dialog), ambientColor = Dsh.shadowCard, spotColor = Dsh.shadowCard)
                    .clip(RoundedCornerShape(DshRadius.dialog))
                    .background(Dsh.bgCard)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
            ) {
                content(requestDismiss)
            }
        }
    }
}

@Composable
internal fun DshDialogTitle(title: String) {
    Text(
        title,
        color = Dsh.labelPrimary,
        style = DshType.headline,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
internal fun DshDialogMessage(message: String, modifier: Modifier = Modifier) {
    Text(message, color = Dsh.labelSecondary, style = DshType.body, modifier = modifier.padding(top = 8.dp))
}

@Composable
internal fun DshDialogError(error: String?) {
    if (error.isNullOrBlank()) return
    Text(
        error,
        color = Dsh.error,
        style = DshType.captionRelaxed,
        modifier = Modifier
            .padding(top = 8.dp)
            .semantics { contentDescription = error },
    )
}

/** 底部按钮行：文字「取消」+ 实心确认（危险操作红底）；[confirmLabel] 为空时只有关闭钮。 */
@Composable
internal fun DshDialogButtons(
    dismissLabel: String,
    onDismiss: () -> Unit,
    confirmLabel: String? = null,
    onConfirm: () -> Unit = {},
    danger: Boolean = false,
    enabled: Boolean = true,
    confirmEnabled: Boolean = enabled,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onDismiss,
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = Dsh.labelSecondary),
        ) {
            Text(dismissLabel, style = DshType.labelLarge)
        }
        if (confirmLabel != null) {
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onConfirm,
                enabled = confirmEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (danger) Dsh.error else Dsh.brand400,
                    contentColor = Dsh.onBrand,
                    disabledContainerColor = Dsh.bgTrack,
                    disabledContentColor = Dsh.labelTertiary,
                ),
            ) {
                Text(confirmLabel, style = DshType.labelLarge)
            }
        }
    }
}

@Composable
internal fun DshRenameDialog(
    currentName: String,
    error: String? = null,
    saving: Boolean = false,
    onDismiss: () -> Unit,
    onClearError: () -> Unit = {},
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    val kind = renameDialogKind(saving, error)
    val canSave = name.isNotBlank() && kind != RenameDialogKind.Saving
    DshDialogFrame(onDismiss = onDismiss, dismissible = kind != RenameDialogKind.Saving) { requestDismiss ->
        DshDialogTitle(L.renameSession)
        DshDialogMessage(L.renameSessionDesc)
        Spacer(Modifier.height(16.dp))
        DshTextField(
            value = name,
            onValueChange = {
                name = it
                if (error != null) onClearError()
            },
            singleLine = true,
            contentDescription = L.renameSession,
            modifier = Modifier.fillMaxWidth(),
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
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val haptic = rememberDshHaptic()
    val kind = renameDialogKind(saving, error)
    DshDialogFrame(onDismiss = onDismiss, dismissible = kind != RenameDialogKind.Saving) { requestDismiss ->
        DshDialogTitle(title)
        DshDialogMessage(message)
        if (kind == RenameDialogKind.Failed) DshDialogError(error)
        if (secondaryLabel != null) {
            TextButton(
                onClick = onSecondary,
                enabled = kind != RenameDialogKind.Saving,
                colors = ButtonDefaults.textButtonColors(contentColor = Dsh.error),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(secondaryLabel, style = DshType.labelLarge)
            }
        }
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
        )
    }
}
