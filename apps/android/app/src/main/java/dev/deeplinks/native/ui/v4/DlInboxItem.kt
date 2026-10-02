package dev.deeplinks.native.ui.v4

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.native.DshRadius
import dev.deeplinks.native.DshSpace

/**
 * v4 收件箱条目（2.1）：状态点 + 状态 · 工作区 + 时间；标题；命令或问题预览；
 * 等你处理的条目带内联按钮（拒绝 / 允许一次 / 回答），主按钮放最后。
 * [running] 在预览前加转圈。
 */
@Composable
fun DlInboxItem(
    workspace: String,
    time: String,
    title: String,
    modifier: Modifier = Modifier,
    status: String? = null,
    tone: DlTone = DlTone.Neutral,
    preview: String? = null,
    command: String? = null,
    running: Boolean = false,
    actions: List<DlAction> = emptyList(),
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = DshSpace.s20, vertical = DshSpace.s12),
        verticalArrangement = Arrangement.spacedBy(DshSpace.s4),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(DshSpace.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(DshSpace.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (status != null) {
                    DlStatusDot(tone)
                    Text(status, style = DlLabelStrong, color = tone.color, maxLines = 1)
                    Text("·", style = DshType.supporting, color = Dsh.labelSecondary)
                }
                Text(
                    workspace,
                    style = DshType.supporting,
                    color = Dsh.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(time, style = DshType.caption, color = Dsh.tertiaryText, maxLines = 1)
        }
        Text(title, style = DshType.bodyStrong, color = Dsh.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (preview != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(DshSpace.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (running) DlSpinner()
                Text(preview, style = DshType.supporting, color = Dsh.labelSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (command != null) {
            Text(
                command,
                style = DshType.caption.copy(fontFamily = FontFamily.Monospace),
                color = Dsh.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = DshSpace.s4)
                    .fillMaxWidth()
                    .background(Dsh.surface1, RoundedCornerShape(DshRadius.control))
                    .padding(horizontal = DshSpace.s12, vertical = DshSpace.s8),
            )
        }
        if (actions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = DshSpace.s8),
                horizontalArrangement = Arrangement.spacedBy(DshSpace.s8, Alignment.End),
            ) {
                for (action in actions) DlButton(action, compact = true)
            }
        }
    }
}
