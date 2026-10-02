package dev.deeplinks.native.ui.v4

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.native.DshIconSize
import dev.deeplinks.native.DshRadius
import dev.deeplinks.native.DshSpace

/**
 * v4 对话框（5.4、5.10、5.11、5.13）：包 M3 [BasicAlertDialog]。
 * 标题 + 说明 + 文字按钮；危险操作只用红色文字（[DlButtonStyle.Danger]），不用红色实心按钮。
 * [leading] 是放在左侧的动作（如「清除目标」）；[content] 放输入框等。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DlDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirm: DlAction,
    modifier: Modifier = Modifier,
    text: String? = null,
    icon: ImageVector? = null,
    iconTone: DlTone = DlTone.Err,
    dismiss: DlAction? = null,
    leading: DlAction? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    BasicAlertDialog(onDismissRequest = onDismissRequest, modifier = modifier) {
        DlDialogSurface(title, confirm, text = text, icon = icon, iconTone = iconTone, dismiss = dismiss, leading = leading, content = content)
    }
}

/** 对话框的静态外观，供截图测试与 [DlDialog] 共用。 */
@Composable
fun DlDialogSurface(
    title: String,
    confirm: DlAction,
    modifier: Modifier = Modifier,
    text: String? = null,
    icon: ImageVector? = null,
    iconTone: DlTone = DlTone.Err,
    dismiss: DlAction? = null,
    leading: DlAction? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(DlOverlayColor, RoundedCornerShape(DshRadius.modal))
            .padding(start = DshSpace.s24, end = DshSpace.s24, top = DshSpace.s24, bottom = DshSpace.s16),
    ) {
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
            style = DshType.titleLarge,
            color = Dsh.labelPrimary,
            modifier = Modifier.padding(bottom = DshSpace.s12).semantics { heading() },
        )
        if (text != null) Text(text, style = DshType.body, color = Dsh.labelSecondary)
        if (content != null) {
            Column(Modifier.padding(top = DshSpace.s12), verticalArrangement = Arrangement.spacedBy(DshSpace.s12)) { content() }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = DshSpace.s20),
            horizontalArrangement = Arrangement.spacedBy(DshSpace.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) DlButton(leading.textStyled(), compact = true)
            Spacer(Modifier.weight(1f))
            if (dismiss != null) DlButton(dismiss.textStyled(), compact = true)
            DlButton(confirm.textStyled(), compact = true)
        }
    }
}

private fun DlAction.textStyled(): DlAction =
    if (style == DlButtonStyle.Danger) this else copy(style = DlButtonStyle.Text)
