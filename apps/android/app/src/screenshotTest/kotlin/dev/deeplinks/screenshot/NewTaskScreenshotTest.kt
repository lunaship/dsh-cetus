package dev.deeplinks.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.native.AddWorkspaceContent
import dev.deeplinks.native.AgentPresetContent
import dev.deeplinks.native.DraftLastTask
import dev.deeplinks.native.InputBar
import dev.deeplinks.native.MobileAgentPreset
import dev.deeplinks.native.WorkspacePickerContent
import dev.deeplinks.native.newTaskDraftCanvas
import dev.deeplinks.native.ui.v4.DlBottomSheetSurface
import dev.deeplinks.native.ui.v4.DlTopBar
import dev.deeplinks.native.util.SessionListKind

/** v4 3.1：新任务草稿——中间一句话 + 工作区 chip，底部继续上次 + 输入区（预设在座位里）。 */
@Composable
private fun NewTaskWall(english: Boolean) {
    Column(Modifier.fillMaxSize().background(Dsh.bgBase)) {
        DlTopBar(title = if (english) "New task" else "新任务", subtitle = "MacBook Pro")
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize()) {
                newTaskDraftCanvas(
                    lastTask = DraftLastTask(
                        sessionId = "preview",
                        title = if (english) "Refactor settings into grouped list" else "重构设置页为分组列表",
                        workspaceLabel = "dsh-links",
                    ),
                    workspaceLabel = "dsh-links",
                    onOpenLastTask = {},
                    onOpenWorkspacePicker = {},
                )
            }
        }
        InputBar(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            inputText = "",
            onInputChange = {},
            isListening = false,
            isSending = false,
            canSend = false,
            running = false,
            modelName = "step-5-preview",
            modelEffort = "high",
            sessionStats = null,
            permissionPreset = "workspace-write",
            permissionLabel = DshS.permWorkspaceWrite,
            onOpenModelPicker = {},
            onOpenPermissionPicker = {},
            presetLabel = DshS.defaultHarnessPreset,
            onToggleVoice = {},
            onStop = {},
            onSend = {},
        )
    }
}

@PreviewTest
@Preview(name = "new task v4 light zh", showBackground = true, widthDp = 412, heightDp = 820)
@Composable
internal fun NewTaskV4LightZh() {
    ShotFrame(dark = false, english = false) { NewTaskWall(english = false) }
}

@PreviewTest
@Preview(name = "new task v4 dark en", showBackground = true, widthDp = 412, heightDp = 820)
@Composable
internal fun NewTaskV4DarkEn() {
    ShotFrame(dark = true, english = true) { NewTaskWall(english = true) }
}

/** v4 3.2 / 3.3 / 3.4：选择工作区、添加工作区、智能体预设三张弹层的静态外观。 */
@Composable
private fun NewTaskSheetsWall(english: Boolean) {
    Column(Modifier.fillMaxSize().background(Dsh.bgOverlay)) {
        DlBottomSheetSurface(title = if (english) "Choose workspace" else "选择工作区") {
            WorkspacePickerContent(
                workspaces = listOf("/Users/me/Dev/dsh-links", "/Users/me/Dev/dsh-links/relay", "/Users/me/Dev/notion-sync"),
                query = "",
                onQueryChange = {},
                selectedPath = "/Users/me/Dev/dsh-links",
                catalogKind = SessionListKind.Content,
                catalogError = null,
                onRetry = {},
                onPick = {},
                onAddWorkspace = {},
            )
        }
        Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            DlBottomSheetSurface(title = if (english) "Add workspace" else "添加工作区") {
                AddWorkspaceContent(
                    value = "notion-sync-v2",
                    onValueChange = {},
                    supporting = if (english) "Creates notion-sync-v2 next to dsh-links." else "会在 dsh-links 的同级目录新建 notion-sync-v2。",
                    isError = false,
                    submitting = false,
                    submitLabel = if (english) "Create" else "创建",
                    canSubmit = true,
                    onCancel = {},
                    onSubmit = {},
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            DlBottomSheetSurface(title = if (english) "Agent preset" else "智能体预设") {
                AgentPresetContent(
                    presets = listOf(
                        MobileAgentPreset("default", "default"),
                        MobileAgentPreset("ptc", "ptc"),
                        MobileAgentPreset("minimal", "minimal"),
                        MobileAgentPreset("creator", "creator"),
                    ),
                    currentId = "default",
                    loading = false,
                    error = null,
                    onRetry = {},
                    onSelect = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "new task sheets light zh", showBackground = true, widthDp = 412, heightDp = 1700)
@Composable
internal fun NewTaskSheetsLightZh() {
    ShotFrame(dark = false, english = false) { NewTaskSheetsWall(english = false) }
}

@PreviewTest
@Preview(name = "new task sheets dark en", showBackground = true, widthDp = 412, heightDp = 1700)
@Composable
internal fun NewTaskSheetsDarkEn() {
    ShotFrame(dark = true, english = true) { NewTaskSheetsWall(english = true) }
}
