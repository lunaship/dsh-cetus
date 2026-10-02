package dev.deeplinks.native.ui.v4

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.native.CheckOutline16
import dev.deeplinks.native.ChevronRightOutline16
import dev.deeplinks.native.DshIconSize
import dev.deeplinks.native.DshSpace

/** 列表行尾部。 */
sealed interface DlRowTrailing {
    data object None : DlRowTrailing
    data object Chevron : DlRowTrailing
    data class Value(val text: String, val chevron: Boolean = true) : DlRowTrailing
    data class Switch(val checked: Boolean, val onCheckedChange: (Boolean) -> Unit) : DlRowTrailing
    data class Radio(val selected: Boolean) : DlRowTrailing
    data class Check(val checked: Boolean) : DlRowTrailing
    data class TextAction(val label: String, val onClick: () -> Unit) : DlRowTrailing
}

/**
 * v4 列表行（7.1、7.2）：前导图标（可选）+ 标题 + 副标题 + 尾部。
 * 单行 52dp、双行 64dp；左右 20dp。[danger] 把标题和图标改成错误色（解除配对、删除）。
 * 开关 / 单选 / 复选行整行可点。
 */
@Composable
fun DlListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: ImageVector? = null,
    leadingTint: DlTone? = null,
    trailing: DlRowTrailing = DlRowTrailing.None,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val interaction = when {
        trailing is DlRowTrailing.Switch -> Modifier.toggleable(
            value = trailing.checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = trailing.onCheckedChange,
        )
        trailing is DlRowTrailing.Radio && onClick != null -> Modifier.selectable(
            selected = trailing.selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onClick,
        )
        trailing is DlRowTrailing.Check && onClick != null -> Modifier.toggleable(
            value = trailing.checked,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = { onClick() },
        )
        onClick != null -> Modifier.clickable(enabled = enabled, onClick = onClick)
        else -> Modifier
    }
    val titleColor = when {
        !enabled -> Dsh.tertiaryText
        danger -> Dsh.err
        else -> Dsh.labelPrimary
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (subtitle != null) DlSize.rowDouble else DlSize.rowSingle)
            .then(interaction)
            .padding(horizontal = DshSpace.s20, vertical = DshSpace.s12),
        horizontalArrangement = Arrangement.spacedBy(DshSpace.s16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(Modifier.width(DshIconSize.lg), contentAlignment = Alignment.Center) {
                Icon(
                    leading,
                    contentDescription = null,
                    tint = when {
                        !enabled -> Dsh.tertiaryText
                        danger -> Dsh.err
                        leadingTint != null -> leadingTint.color
                        else -> Dsh.labelSecondary
                    },
                    modifier = Modifier.size(DshIconSize.md),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = DshType.body, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = DshType.supporting,
                    color = if (enabled) Dsh.labelSecondary else Dsh.tertiaryText,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DlRowTrailingContent(trailing, enabled)
    }
}

@Composable
private fun DlRowTrailingContent(trailing: DlRowTrailing, enabled: Boolean) {
    when (trailing) {
        DlRowTrailing.None -> Unit
        DlRowTrailing.Chevron -> DlChevron()
        is DlRowTrailing.Value -> Row(
            horizontalArrangement = Arrangement.spacedBy(DshSpace.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(trailing.text, style = DshType.supporting, color = Dsh.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (trailing.chevron) DlChevron()
        }
        is DlRowTrailing.Switch -> Switch(
            checked = trailing.checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Dsh.onBrand,
                checkedTrackColor = Dsh.brand400,
                checkedBorderColor = Dsh.brand400,
                uncheckedThumbColor = Dsh.tertiaryText,
                uncheckedTrackColor = Dsh.surface2,
                uncheckedBorderColor = Dsh.outline,
                disabledCheckedTrackColor = Dsh.surface2,
                disabledCheckedThumbColor = Dsh.tertiaryText,
                disabledUncheckedTrackColor = Dsh.surface1,
                disabledUncheckedThumbColor = Dsh.outline,
                disabledUncheckedBorderColor = Dsh.outline,
            ),
        )
        is DlRowTrailing.Radio -> RadioButton(
            selected = trailing.selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(
                selectedColor = Dsh.brand400,
                unselectedColor = Dsh.tertiaryText,
                disabledSelectedColor = Dsh.tertiaryText,
                disabledUnselectedColor = Dsh.outline,
            ),
        )
        is DlRowTrailing.Check -> if (trailing.checked) {
            Icon(
                CheckOutline16,
                contentDescription = null,
                tint = if (enabled) Dsh.brand400 else Dsh.tertiaryText,
                modifier = Modifier.size(DshIconSize.md),
            )
        }
        is DlRowTrailing.TextAction -> DlButton(
            DlAction(trailing.label, trailing.onClick, DlButtonStyle.Text, enabled),
            compact = true,
        )
    }
}

@Composable
private fun DlChevron() {
    Icon(
        ChevronRightOutline16,
        contentDescription = null,
        tint = Dsh.tertiaryText,
        modifier = Modifier.size(DshIconSize.sm),
    )
}
