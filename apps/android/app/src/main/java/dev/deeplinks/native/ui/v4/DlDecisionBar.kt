package dev.deeplinks.native.ui.v4

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.native.DshIconSize
import dev.deeplinks.native.DshRadius
import dev.deeplinks.native.DshSpace
import dev.deeplinks.native.EditOutline16

/** 决策栏里的一个选项；[custom] 是「自己写答案」入口。 */
data class DlDecisionOption(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val custom: Boolean = false,
)

/**
 * v4 决策栏（4.3、4.4）：审批和提问替换输入区，不进消息流。
 * 状态行（等你色）+ 问题 + 命令块 / 选项 + 次按钮（左，容器色）与主按钮（右，品牌实心）。
 */
@Composable
fun DlDecisionBar(
    status: String,
    question: String,
    secondary: DlAction,
    primary: DlAction,
    modifier: Modifier = Modifier,
    meta: String? = null,
    tone: DlTone = DlTone.Wait,
    command: String? = null,
    note: String? = null,
    options: List<DlDecisionOption> = emptyList(),
) {
    Column(modifier.fillMaxWidth().background(Dsh.bgBase)) {
        HorizontalDivider(thickness = 1.dp, color = Dsh.outline)
        Column(
            Modifier.padding(start = DshSpace.s16, end = DshSpace.s16, top = DshSpace.s16, bottom = DshSpace.s12),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = DshSpace.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(DshSpace.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DlStatusDot(tone)
                    Text(status, style = DlLabelStrong, color = tone.color, maxLines = 1)
                }
                if (meta != null) Text(meta, style = DshType.caption, color = Dsh.tertiaryText, maxLines = 1)
            }
            Text(
                question,
                style = DshType.titleLarge,
                color = Dsh.labelPrimary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = DshSpace.s8),
            )
            if (command != null) {
                Text(
                    command,
                    style = DshType.supporting.copy(fontFamily = FontFamily.Monospace),
                    color = Dsh.labelPrimary,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Dsh.surface1, RoundedCornerShape(DshRadius.container))
                        .padding(DshSpace.s12),
                )
            }
            if (note != null) {
                Text(
                    note,
                    style = DshType.supporting,
                    color = Dsh.labelSecondary,
                    modifier = Modifier.padding(top = DshSpace.s8),
                )
            }
            if (options.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(DshSpace.s8)) {
                    for (option in options) DlDecisionOptionRow(option)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = DshSpace.s12),
                horizontalArrangement = Arrangement.spacedBy(DshSpace.s12),
            ) {
                DlButton(secondary.copy(style = DlButtonStyle.Tonal), Modifier.weight(1f))
                DlButton(primary.copy(style = DlButtonStyle.Filled), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DlDecisionOptionRow(option: DlDecisionOption) {
    val shape = RoundedCornerShape(DshRadius.container)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (option.selected) Dsh.primarySoft else Dsh.bgBase)
            .border(1.dp, if (option.selected) Dsh.brand400 else Dsh.outline, shape)
            .selectable(selected = option.selected, role = Role.RadioButton, onClick = option.onClick)
            .padding(horizontal = DshSpace.s12, vertical = DshSpace.s4),
        horizontalArrangement = Arrangement.spacedBy(DshSpace.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (option.custom) {
            Icon(
                EditOutline16,
                contentDescription = null,
                tint = Dsh.tertiaryText,
                modifier = Modifier.padding(DshSpace.s12).size(DshIconSize.sm),
            )
        } else {
            RadioButton(
                selected = option.selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = Dsh.brand400, unselectedColor = Dsh.tertiaryText),
                modifier = Modifier.padding(DshSpace.s8),
            )
        }
        Text(
            option.label,
            style = DshType.body,
            color = if (option.custom) Dsh.tertiaryText else Dsh.labelPrimary,
            modifier = Modifier.padding(vertical = DshSpace.s8),
        )
    }
}
