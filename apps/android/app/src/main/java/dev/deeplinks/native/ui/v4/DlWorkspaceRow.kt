package dev.deeplinks.native.ui.v4

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.core.homeTaskCount
import dev.deeplinks.native.ChevronDownOutline16
import dev.deeplinks.native.ChevronRightOutline16
import dev.deeplinks.native.DshIconSize
import dev.deeplinks.native.DshSpace
import dev.deeplinks.native.DshTouch
import dev.deeplinks.native.EditOutline16
import dev.deeplinks.native.FolderClose16
import dev.deeplinks.native.FolderOpenOutline16

/** v4 2.1：文件夹行；展开和新建分别拥有独立的触控区域。 */
@Composable
fun DlWorkspaceRow(
    title: String,
    count: Int,
    expanded: Boolean,
    awaitingCount: Int,
    runningCount: Int,
    online: Boolean,
    onToggle: () -> Unit,
    onCreate: () -> Unit,
) {
    val summary = listOfNotNull(
        L.homeTaskCount.format(count),
        L.homeAwaiting.takeIf { awaitingCount > 0 }?.let { "$it $awaitingCount" },
        L.homeRunning.takeIf { runningCount > 0 }?.let { "$it $runningCount" },
    ).joinToString(" · ")
    val expandedLabel = if (expanded) L.collapse else L.expand
    Row(
        Modifier.fillMaxWidth().padding(start = DshSpace.s20, end = DshSpace.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f)
                .semantics { stateDescription = expandedLabel }
                .clickable(role = Role.Button, onClickLabel = expandedLabel, onClick = onToggle)
                .heightIn(min = DshTouch.min)
                .padding(vertical = DshSpace.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (expanded) FolderOpenOutline16 else FolderClose16, null, tint = Dsh.labelPrimary, modifier = Modifier.size(DshIconSize.md))
            Spacer(Modifier.width(DshSpace.s12))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DshSpace.s4)) {
                Text(title, style = DshType.bodyStrong, color = Dsh.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(summary, style = DshType.caption, color = if (awaitingCount > 0) Dsh.wait else Dsh.labelSecondary)
            }
            Icon(if (expanded) ChevronDownOutline16 else ChevronRightOutline16, null, tint = Dsh.labelSecondary, modifier = Modifier.size(DshIconSize.sm))
        }
        DlIconButton(EditOutline16, "${L.homeNewTask} · $title", onCreate, enabled = online)
    }
}
