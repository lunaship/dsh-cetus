package dev.deeplinks.native

import dev.deeplinks.native.DshIconSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.native.ui.DshFilterChip
import dev.deeplinks.native.ui.DshSectionLabel
import dev.deeplinks.native.util.draftWorkspaceChipLabels

/** 「继续上次」要显示的最近一条会话。 */
internal data class DraftLastTask(
    val sessionId: String,
    val title: String,
    val workspaceLabel: String?,
)

/**
 * 新任务草稿画布（N1）——对话页 `messages.isEmpty()` 的草稿态。
 *
 * 三块贴底内容，靠输入栏、单手可达：
 * 1. 继续上次（有会话时才有）；
 * 2. 工作区胶囊（最多 4 个最近用过的 + 行尾常驻「更多」打开选择器）；
 * 3. 智能体预设（仅新建时可改）。
 *
 * 属于 `LazyListScope`：整块用一个 item 撑满视口并按 `Arrangement.Bottom` 贴底，
 * 键盘弹起时随输入区一起上移；不放品牌标志和标语。
 */
internal fun LazyListScope.newTaskDraftCanvas(
    lastTask: DraftLastTask?,
    workspaces: List<String>,
    selectedWorkspace: String?,
    modeLabel: String,
    onOpenLastTask: (String) -> Unit,
    onSelectWorkspace: (String) -> Unit,
    onOpenWorkspacePicker: () -> Unit,
    onOpenModePicker: () -> Unit,
) {
    item(key = "new-task-draft") {
        Column(
            modifier = Modifier
                .fillParentMaxSize()
                .padding(horizontal = COMPOSER_SIDE_CLEARANCE),
            verticalArrangement = Arrangement.Bottom,
        ) {
            if (lastTask != null) {
                DraftLastTaskRow(task = lastTask, onOpenLastTask = onOpenLastTask)
                Spacer(Modifier.height(DshSpace.sectionGap))
            }

            DshSectionLabel(L.newTaskWorkspace)
            Spacer(Modifier.height(DshSpace.s8))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val visible = workspaces.take(4)
                // 同名末级目录（/a/app 与 /b/app 都只显示 app）在胶囊上无法区分：带上父级尾段。
                val chipLabels = draftWorkspaceChipLabels(visible)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(DshSpace.s6),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    visible.forEachIndexed { index, cwd ->
                        DshFilterChip(
                            label = chipLabels[index],
                            selected = cwd == selectedWorkspace,
                            onClick = { onSelectWorkspace(cwd) },
                        )
                    }
                }
                Spacer(Modifier.width(DshSpace.s6))
                // 「更多」常驻行尾、不随胶囊横向滚走：首屏即可发现工作区选择器
                DshFilterChip(
                    label = L.moreWorkspaces,
                    selected = false,
                    onClick = onOpenWorkspacePicker,
                    contentDescription = L.selectWorkspaceShort,
                )
            }

            Spacer(Modifier.height(DshSpace.sectionGap))

            DshSectionLabel(L.newTaskMode)
            Spacer(Modifier.height(DshSpace.s8))
            DraftModeRow(label = modeLabel, onClick = onOpenModePicker)
        }
    }
}

/** 继续上次：普通行（无卡片底色），两行小字加右向箭头。 */
@Composable
private fun DraftLastTaskRow(task: DraftLastTask, onOpenLastTask: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DshRowHeight.default)
            .clickable(role = Role.Button) { onOpenLastTask(task.sessionId) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                L.newTaskContinueLast,
                color = Dsh.labelTertiary,
                style = DshType.captionRelaxed,
                maxLines = 1,
            )
            Text(
                listOfNotNull(task.title, task.workspaceLabel)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                color = Dsh.labelPrimary,
                style = DshType.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            ChevronRightOutline16,
            contentDescription = null,
            tint = Dsh.labelTertiary,
            modifier = Modifier.size(DshIconSize.xs),
        )
    }
}

/** 智能体预设行：当前预设名 + ⌄，点击打开 AgentPresetPickerSheet。 */
@Composable
private fun DraftModeRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DshRowHeight.default)
            .semantics {
                role = Role.Button
                contentDescription = L.agentPresetSeatAria.format(label)
            }
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = Dsh.labelPrimary,
            style = DshType.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            ChevronDownOutline16,
            contentDescription = null,
            tint = Dsh.labelTertiary,
            modifier = Modifier.size(DshIconSize.xs),
        )
    }
}
