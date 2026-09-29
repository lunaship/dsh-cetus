package dev.deeplinks.native

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.native.ui.DshFilterChip
import dev.deeplinks.native.ui.DshIconAction
import dev.deeplinks.native.ui.DshSectionLabel
import dev.deeplinks.native.ui.DshSheet

/**
 * 新任务底部面板（2026-09-28 重设计 · 方案阶段 4 · 稿 02）。
 *
 * 新任务从首页浮起来，不再是一个空页面：面板里先给「继续上次的任务」和「在哪个工作区」，
 * 再是真正要写的任务内容。对话页里那套新会话草稿起始块也随之删掉。
 *
 * 状态全部由参数注入，面板本身不碰网络；创建会话→发首条消息由 Activity 在 [onSend] 里做，
 * 失败时把错误写回 [error]，面板不关（方案 4.5）。
 */

/** 「继续上次的任务」卡要显示的内容。 */
internal data class LastTaskSummary(
    val title: String,
    val workspaceLabel: String?,
)

/**
 * 面板的展示状态与动作各打包成一件：调用点在 WorkspaceActivity 里，
 * 那个文件有 CodeHygieneTest 的行数预算（只降不升），参数铺开会把预算顶掉。
 */
internal data class NewTaskSheetState(
    val workspaces: List<String>,
    val selectedWorkspace: String?,
    /** 最近一条会话；标题与工作区名由宿主自己映射，免掉调用点的样板。 */
    val lastSession: MobileSession?,
    val input: String,
    val modelName: String?,
    val modelEffort: String?,
    val permissionPreset: String,
    val permissionLabel: String,
    val sending: Boolean,
    val error: String?,
)

internal class NewTaskSheetActions(
    val onSelectWorkspace: (String) -> Unit,
    val onOpenWorkspacePicker: () -> Unit,
    val onOpenLastTask: (String) -> Unit,
    val onInputChange: (String) -> Unit,
    val onOpenModelPicker: () -> Unit,
    val onOpenModePicker: () -> Unit,
    val onAttach: () -> Unit,
    val onSend: () -> Unit,
    val onDismiss: () -> Unit,
)

@Composable
internal fun NewTaskSheetHost(state: NewTaskSheetState, actions: NewTaskSheetActions) {
    NewTaskSheet(
        workspaces = state.workspaces,
        selectedWorkspace = state.selectedWorkspace,
        onSelectWorkspace = actions.onSelectWorkspace,
        onOpenWorkspacePicker = actions.onOpenWorkspacePicker,
        lastTask = state.lastSession?.let { last ->
            LastTaskSummary(
                title = displaySessionTitle(last.title),
                workspaceLabel = last.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() },
            )
        },
        onOpenLastTask = { state.lastSession?.let { actions.onOpenLastTask(it.sessionId) } },
        input = state.input,
        onInputChange = actions.onInputChange,
        modelName = state.modelName,
        modelEffort = state.modelEffort,
        permissionPreset = state.permissionPreset,
        permissionLabel = state.permissionLabel,
        onOpenModelPicker = actions.onOpenModelPicker,
        onOpenModePicker = actions.onOpenModePicker,
        onAttach = actions.onAttach,
        sending = state.sending,
        error = state.error,
        onSend = actions.onSend,
        onDismiss = actions.onDismiss,
    )
}

@Composable
internal fun NewTaskSheet(
    workspaces: List<String>,
    selectedWorkspace: String?,
    onSelectWorkspace: (String) -> Unit,
    onOpenWorkspacePicker: () -> Unit,
    lastTask: LastTaskSummary?,
    onOpenLastTask: () -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    modelName: String?,
    modelEffort: String?,
    permissionPreset: String,
    permissionLabel: String,
    onOpenModelPicker: () -> Unit,
    onOpenModePicker: () -> Unit,
    onAttach: () -> Unit,
    sending: Boolean,
    error: String?,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    DshSheet(
        onDismiss = onDismiss,
        title = DshS.homeNewTask,
        showClose = true,
        skipPartiallyExpanded = true,
    ) {
        NewTaskSheetContent(
            workspaces = workspaces,
            selectedWorkspace = selectedWorkspace,
            onSelectWorkspace = onSelectWorkspace,
            onOpenWorkspacePicker = onOpenWorkspacePicker,
            lastTask = lastTask,
            onOpenLastTask = onOpenLastTask,
            input = input,
            onInputChange = onInputChange,
            modelName = modelName,
            modelEffort = modelEffort,
            permissionPreset = permissionPreset,
            permissionLabel = permissionLabel,
            onOpenModelPicker = onOpenModelPicker,
            onOpenModePicker = onOpenModePicker,
            onAttach = onAttach,
            sending = sending,
            error = error,
            onSend = onSend,
        )
    }
}

/**
 * 面板内容（不含 ModalBottomSheet 外壳）：外壳只管浮层与把手，内容可单独渲染与复用。
 *
 * 注意：**这套截图测试覆盖不到它**——输入卡用的是 ComposerEditField（真实 EditText，
 * 为了中文输入法稳定），AndroidView 在 Compose 预览截图宿主里无法渲染，AGP 会报
 * ScreenshotRenderException。面板的视觉验收只能走真机截图（与稿 02 对照）。
 */
@Composable
internal fun NewTaskSheetContent(
    workspaces: List<String>,
    selectedWorkspace: String?,
    onSelectWorkspace: (String) -> Unit,
    onOpenWorkspacePicker: () -> Unit,
    lastTask: LastTaskSummary?,
    onOpenLastTask: () -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    modelName: String?,
    modelEffort: String?,
    permissionPreset: String,
    permissionLabel: String,
    onOpenModelPicker: () -> Unit,
    onOpenModePicker: () -> Unit,
    onAttach: () -> Unit,
    sending: Boolean,
    error: String?,
    onSend: () -> Unit,
) {
    val s = DshS
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
            if (lastTask != null) {
                LastTaskCard(task = lastTask, onClick = onOpenLastTask)
                Spacer(Modifier.size(DshSpace.s16))
            }

            DshSectionLabel(s.newTaskWorkspace)
            Spacer(Modifier.size(DshSpace.s8))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(DshSpace.s6),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                workspaces.forEach { cwd ->
                    DshFilterChip(
                        label = cwd.substringAfterLast('/'),
                        selected = cwd == selectedWorkspace,
                        onClick = { onSelectWorkspace(cwd) },
                    )
                }
                DshIconAction(
                    icon = ChevronDownOutline14,
                    contentDescription = s.selectWorkspaceShort,
                    onClick = onOpenWorkspacePicker,
                    size = 48.dp,
                    iconSize = 16.dp,
                )
            }
            Spacer(Modifier.size(DshSpace.s16))

            DshSectionLabel(s.newTaskContent)
            Spacer(Modifier.size(DshSpace.s8))
            InputCard(
                input = input,
                onInputChange = onInputChange,
                modelName = modelName,
                modelEffort = modelEffort,
                permissionPreset = permissionPreset,
                permissionLabel = permissionLabel,
                onOpenModelPicker = onOpenModelPicker,
                onOpenModePicker = onOpenModePicker,
                onAttach = onAttach,
                sending = sending,
                onSend = onSend,
            )
            if (!error.isNullOrBlank()) {
                Spacer(Modifier.size(DshSpace.s8))
                Text(
                    error,
                    color = Dsh.error,
                    style = DshType.supporting,
                    modifier = Modifier.padding(horizontal = DshSpace.s4),
                )
            }
        }
    }

/** 「继续上次的任务」：tonal 底、不加描边（SurfaceHierarchyTest 禁容器描边），整卡可点。 */
@Composable
private fun LastTaskCard(task: LastTaskSummary, onClick: () -> Unit) {
    val s = DshS
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(DshRadius.container))
            .background(Dsh.bgSubtle)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = DshSpace.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                s.newTaskContinueLast,
                color = Dsh.labelPrimary,
                style = DshType.bodyStrong,
                maxLines = 1,
            )
            Spacer(Modifier.size(DshSpace.s2))
            val detail = listOfNotNull(task.title, task.workspaceLabel)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            Text(
                detail,
                color = Dsh.labelSecondary,
                style = DshType.captionRelaxed,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            ChevronRightOutline14,
            contentDescription = null,
            tint = Dsh.labelTertiary,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** 输入卡：「+ 附件 / 模型 ⌄ / 模式 ⌄ / 发送」，多行输入复用 ComposerEditField（保住中文输入法）。 */
@Composable
private fun InputCard(
    input: String,
    onInputChange: (String) -> Unit,
    modelName: String?,
    modelEffort: String?,
    permissionPreset: String,
    permissionLabel: String,
    onOpenModelPicker: () -> Unit,
    onOpenModePicker: () -> Unit,
    onAttach: () -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
) {
    val s = DshS
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.composer))
            .background(Dsh.bgInput)
            .padding(start = DshSpace.s16, end = DshSpace.s12, top = DshSpace.s12, bottom = DshSpace.s8),
    ) {
        ComposerEditField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp),
            hint = s.newTaskHint,
            textColor = Dsh.labelPrimary,
            hintColor = Dsh.labelTertiary,
            cursorColor = Dsh.brand500,
        )
        Spacer(Modifier.size(DshSpace.s8))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DshIconAction(
                icon = PlusOutline16,
                contentDescription = s.newTaskAttach,
                onClick = onAttach,
                size = 44.dp,
                iconSize = 18.dp,
            )
            Spacer(Modifier.width(DshSpace.s4))
            ComposerSeatsRow(
                modelName = modelName,
                // 稿 02 的面板里只写模型名、不写推理档（档位在模型选择器里改）：
                // 带「High」后缀时这一行放不下第二个座，真机上「标准模式」被截成「标准…」。
                modelEffort = null,
                permissionPreset = permissionPreset,
                permissionLabel = permissionLabel,
                onOpenModelPicker = onOpenModelPicker,
                onOpenPermissionPicker = onOpenModePicker,
            )
            Spacer(Modifier.weight(1f))
            // 发送中：整体降透明并拦住重复点击（DshIconAction 没有 enabled 参数）
            Box(modifier = Modifier.alpha(if (sending) 0.55f else 1f)) {
                DshIconAction(
                    icon = SendOutline16,
                    contentDescription = s.sendMessage,
                    onClick = { if (!sending) onSend() },
                    size = 44.dp,
                    iconSize = 18.dp,
                    containerColor = Dsh.brand500,
                )
            }
        }
    }
}
