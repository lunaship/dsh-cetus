package dev.deeplinks.native

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.core.attachSheetTitle
import dev.deeplinks.native.ui.v4.DlBottomSheet
import dev.deeplinks.native.ui.v4.DlInsetColor
import dev.deeplinks.native.ui.v4.DlListRow
import dev.deeplinks.native.ui.v4.DlRowTrailing

/**
 * 5.5 添加附件：拍照 / 相册两个大按钮；下面一行是本会话权限（原 + 菜单里的入口）。
 * 目前只支持图片附件，所以没有「文件」和「最近的图片」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachSheet(
    permissionLabel: String,
    permissionIcon: ImageVector,
    onDismiss: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickImage: () -> Unit,
    onOpenPermissionPicker: () -> Unit,
) {
    DlBottomSheet(onDismissRequest = onDismiss, title = DshS.attachSheetTitle) {
        AttachSheetContent(
            permissionLabel = permissionLabel,
            permissionIcon = permissionIcon,
            onTakePhoto = {
                onDismiss()
                onTakePhoto()
            },
            onPickImage = {
                onDismiss()
                onPickImage()
            },
            onOpenPermissionPicker = {
                onDismiss()
                onOpenPermissionPicker()
            },
        )
    }
}

/** 弹层正文，截图直接画这一块。 */
@Composable
internal fun AttachSheetContent(
    permissionLabel: String,
    permissionIcon: ImageVector,
    onTakePhoto: () -> Unit,
    onPickImage: () -> Unit,
    onOpenPermissionPicker: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpace.s16, vertical = DshSpace.s4),
        horizontalArrangement = Arrangement.spacedBy(DshSpace.s12),
    ) {
        AttachTile(CameraOutline16, L.takePhoto, onTakePhoto, Modifier.weight(1f))
        AttachTile(ImageOutline16, L.choosePhoto, onPickImage, Modifier.weight(1f))
    }
    DlListRow(
        title = L.accessMode,
        leading = permissionIcon,
        trailing = DlRowTrailing.Value(permissionLabel),
        onClick = onOpenPermissionPicker,
        modifier = Modifier.padding(top = DshSpace.s8),
    )
}

@Composable
private fun AttachTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .heightIn(min = DshTouch.min + DshSpace.s32)
            .clip(RoundedCornerShape(DshRadius.container))
            .background(DlInsetColor)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = DshSpace.s16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DshSpace.s8, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = Dsh.labelPrimary, modifier = Modifier.size(DshIconSize.lg))
        Text(label, style = DshType.body, color = Dsh.labelPrimary)
    }
}
