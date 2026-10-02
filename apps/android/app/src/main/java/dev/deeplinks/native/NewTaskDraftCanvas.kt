package dev.deeplinks.native

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.newTaskHeadline
import dev.deeplinks.native.ui.v4.DlListRow
import dev.deeplinks.native.ui.v4.DlPill
import dev.deeplinks.native.ui.v4.DlRowTrailing

/** 「继续上次」要显示的最近一条会话。 */
internal data class DraftLastTask(
    val sessionId: String,
    val title: String,
    val workspaceLabel: String?,
)

/**
 * v4 3.1 新任务草稿画布——对话页 `messages.isEmpty()` 的草稿态。
 *
 * 中间一句「要做点什么？」+ 工作区选择 chip（点开 3.2）；底部贴着输入区放「继续上次的任务」。
 * 智能体预设收进输入区的座位（[InputBar] 的 presetLabel），这里不再单独一行。
 * 整块用一个 item 撑满视口，键盘弹起时随输入区一起上移。
 */
internal fun LazyListScope.newTaskDraftCanvas(
    lastTask: DraftLastTask?,
    workspaceLabel: String?,
    onOpenLastTask: (String) -> Unit,
    onOpenWorkspacePicker: () -> Unit,
) {
    item(key = "new-task-draft") {
        Column(Modifier.fillParentMaxSize()) {
            Spacer(Modifier.weight(1f))
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpace.s24),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DshSpace.s16),
            ) {
                Text(DshS.newTaskHeadline, style = DshType.headlineMedium, color = Dsh.labelPrimary, textAlign = TextAlign.Center)
                DraftWorkspaceChip(label = workspaceLabel ?: DshS.noWorkspaceBinding, onClick = onOpenWorkspacePicker)
            }
            Spacer(Modifier.weight(1f))
            if (lastTask != null) {
                DlListRow(
                    title = DshS.newTaskContinueLast,
                    subtitle = listOfNotNull(lastTask.title, lastTask.workspaceLabel).filter { it.isNotBlank() }.joinToString(" · "),
                    leading = ClockOutline16,
                    trailing = DlRowTrailing.Chevron,
                    onClick = { onOpenLastTask(lastTask.sessionId) },
                )
            }
        }
    }
}

/** 工作区 chip：文件夹 + 名称 + ⌄，容器色胶囊。 */
@Composable
private fun DraftWorkspaceChip(label: String, onClick: () -> Unit) {
    val aria = DshS.selectWorkspaceShort
    Row(
        modifier = Modifier
            .heightIn(min = DshSpace.s32 + DshSpace.s4)
            .clip(DlPill)
            .background(Dsh.surface1)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$aria: $label" }
            .padding(horizontal = DshSpace.s16),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpace.s8),
    ) {
        Icon(FolderClose16, contentDescription = null, tint = Dsh.labelSecondary, modifier = Modifier.size(DshIconSize.sm))
        Text(label, style = DshType.body, color = Dsh.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(ChevronDownOutline16, contentDescription = null, tint = Dsh.labelSecondary, modifier = Modifier.size(DshIconSize.sm))
    }
}
