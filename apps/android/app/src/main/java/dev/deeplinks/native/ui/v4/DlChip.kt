package dev.deeplinks.native.ui.v4

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.deeplinks.native.DshSpace
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.native.DshIconSize

/** chip 外观：描边（筛选、建议）或容器色底（输入区标签）。 */
enum class DlChipStyle { Outlined, Filled }

/**
 * v4 chip（4.2 建议、2.4 筛选、5.2）：可选中时包 M3 [FilterChip]（选中 = primarySoft 底 + 品牌色字），
 * 否则包 [AssistChip]。[tone] 只用于等你色（完全权限）。
 */
@Composable
fun DlChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    icon: ImageVector? = null,
    style: DlChipStyle = DlChipStyle.Outlined,
    tone: DlTone = DlTone.Neutral,
    enabled: Boolean = true,
) {
    val content = if (tone == DlTone.Neutral) Dsh.labelSecondary else tone.color
    val container = if (style == DlChipStyle.Filled) Dsh.surface1 else Dsh.bgBase
    val border = if (style == DlChipStyle.Outlined) BorderStroke(1.dp, Dsh.outline) else null
    val leading: (@Composable () -> Unit)? = icon?.let {
        { Icon(it, contentDescription = null, modifier = Modifier.size(DshIconSize.sm)) }
    }
    if (selected != null) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            enabled = enabled,
            label = { DlChipLabel(label, selected) },
            leadingIcon = leading,
            shape = DlPill,
            modifier = modifier.height(DlSize.chip),
            colors = FilterChipDefaults.filterChipColors(
                containerColor = container,
                labelColor = content,
                iconColor = content,
                selectedContainerColor = Dsh.primarySoft,
                selectedLabelColor = Dsh.brand400,
                selectedLeadingIconColor = Dsh.brand400,
                disabledContainerColor = container,
                disabledLabelColor = Dsh.tertiaryText,
                disabledLeadingIconColor = Dsh.tertiaryText,
            ),
            border = if (selected) null else border,
            elevation = null,
        )
    } else {
        AssistChip(
            onClick = onClick,
            enabled = enabled,
            label = { DlChipLabel(label, false) },
            leadingIcon = leading,
            shape = DlPill,
            modifier = modifier.height(DlSize.chip),
            colors = AssistChipDefaults.assistChipColors(
                containerColor = container,
                labelColor = content,
                leadingIconContentColor = content,
                disabledContainerColor = container,
                disabledLabelColor = Dsh.tertiaryText,
                disabledLeadingIconContentColor = Dsh.tertiaryText,
            ),
            border = border,
            elevation = null,
        )
    }
}

@Composable
private fun DlChipLabel(text: String, selected: Boolean) {
    Text(
        text,
        style = if (selected) DlLabelStrong else DshType.supporting,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * v4 分段（5.2 推理等级、7.5 主题）：包 M3 [SingleChoiceSegmentedButtonRow]，
 * 选中段 primarySoft 底 + 品牌色字，不显示勾。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DlSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SingleChoiceSegmentedButtonRow(modifier) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            SegmentedButton(
                selected = selected,
                onClick = { onSelect(index) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size, baseShape = DlPill),
                icon = {},
                // 长文案（英文、四段）不截断：收窄内边距，放不下就折成两行
                contentPadding = PaddingValues(horizontal = DshSpace.s8),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Dsh.primarySoft,
                    activeContentColor = Dsh.brand400,
                    activeBorderColor = Dsh.outline,
                    inactiveContainerColor = Dsh.bgBase,
                    inactiveContentColor = Dsh.labelPrimary,
                    inactiveBorderColor = Dsh.outline,
                    disabledActiveContainerColor = Dsh.surface1,
                    disabledActiveContentColor = Dsh.tertiaryText,
                    disabledInactiveContainerColor = Dsh.bgBase,
                    disabledInactiveContentColor = Dsh.tertiaryText,
                ),
                label = {
                    Text(
                        label,
                        style = if (selected) DshType.bodyStrong else DshType.body,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                },
            )
        }
    }
}
