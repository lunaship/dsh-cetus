package dev.deeplinks.native.ui.v4

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import dev.deeplinks.native.DshIconSize
import dev.deeplinks.native.DshSpace
import dev.deeplinks.native.DshTouch
import dev.deeplinks.native.ComposeOutline16
import dev.deeplinks.native.FolderClose16
import dev.deeplinks.native.FolderOpenOutline16

/**
 * v4 2.1：文件夹行，只有 图标 + 名字 + 一个浅色「新建」。点名字展开 / 收起，长按出 2.5 工作区菜单。
 * 不显示任务数和箭头：等你处理已经置顶，展开状态由文件夹图标开合表达。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DlWorkspaceRow(
    title: String,
    expanded: Boolean,
    online: Boolean,
    onToggle: () -> Unit,
    onCreate: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val expandedLabel = if (expanded) L.collapse else L.expand
    Row(
        Modifier.fillMaxWidth().padding(start = DshSpace.s20, end = DshSpace.s8, top = DshSpace.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f)
                .semantics { stateDescription = expandedLabel }
                .combinedClickable(role = Role.Button, onClickLabel = expandedLabel, onLongClick = onLongClick, onClick = onToggle)
                .heightIn(min = DshTouch.min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (expanded) FolderOpenOutline16 else FolderClose16, null, tint = Dsh.labelPrimary, modifier = Modifier.size(DshIconSize.md))
            Spacer(Modifier.width(DshSpace.s12))
            Text(title, style = DshType.bodyStrong, color = Dsh.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DlIconButton(ComposeOutline16, "${L.homeNewTask} · $title", onCreate, enabled = online, tint = Dsh.tertiaryText)
    }
}
