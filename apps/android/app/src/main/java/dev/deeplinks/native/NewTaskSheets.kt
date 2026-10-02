package dev.deeplinks.native

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.core.newTaskChatOnly
import dev.deeplinks.core.presetSheetFootnote
import dev.deeplinks.core.presetSheetTitle
import dev.deeplinks.core.workspaceSearchOrPath
import dev.deeplinks.core.workspaceSheetTitle
import dev.deeplinks.core.workspaceUsePath
import dev.deeplinks.native.ui.v4.DlAction
import dev.deeplinks.native.ui.v4.DlBottomSheet
import dev.deeplinks.native.ui.v4.DlButton
import dev.deeplinks.native.ui.v4.DlButtonStyle
import dev.deeplinks.native.ui.v4.DlListRow
import dev.deeplinks.native.ui.v4.DlRowTrailing
import dev.deeplinks.native.ui.v4.DlSectionHeader
import dev.deeplinks.native.ui.v4.DlTone
import dev.deeplinks.native.util.SessionListKind
import dev.deeplinks.native.util.abbreviateHomePath
import dev.deeplinks.native.util.catalogKind
import dev.deeplinks.native.util.sessionShowsRefreshBanner
import dev.deeplinks.native.util.visibleUserWorkspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * v4 新任务的三个弹层：3.2 选择工作区、3.3 添加工作区、3.4 智能体预设。
 */

/** 3.2 选择工作区：搜索框兼做路径输入（输入绝对路径直接用它）；最近 → 不绑定工作区 → 添加工作区。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspacePickerSheet(
    sessions: List<MobileSession>,
    deletedWorkspaces: Set<String> = emptySet(),
    registeredPaths: List<String> = emptyList(),
    registryReady: Boolean = false,
    selectedPath: String? = null,
    catalogKind: SessionListKind = SessionListKind.Content,
    catalogError: String? = null,
    onRetry: () -> Unit = {},
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
    onAddWorkspace: (() -> Unit)? = null,
) {
    val workspaces = remember(sessions, deletedWorkspaces, registeredPaths, registryReady) {
        visibleUserWorkspaces(
            sessionCwds = sessions.map { it.cwd },
            deletedWorkspaces = deletedWorkspaces,
            registeredPaths = registeredPaths,
            // 与 Web 对齐：注册表就绪后不再用历史会话 cwd 撑出幽灵工作区
            requireRegistered = registryReady,
        )
    }
    var query by remember { mutableStateOf("") }
    DlBottomSheet(onDismissRequest = onDismiss, title = DshS.workspaceSheetTitle) {
        WorkspacePickerContent(
            workspaces = workspaces,
            query = query,
            onQueryChange = { query = it },
            selectedPath = selectedPath,
            catalogKind = catalogKind,
            catalogError = catalogError,
            onRetry = onRetry,
            onPick = { onDismiss(); onPick(it) },
            onAddWorkspace = onAddWorkspace?.let { add -> { onDismiss(); add() } },
        )
    }
}

@Composable
internal fun WorkspacePickerContent(
    workspaces: List<String>,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedPath: String?,
    catalogKind: SessionListKind,
    catalogError: String?,
    onRetry: () -> Unit,
    onPick: (String?) -> Unit,
    onAddWorkspace: (() -> Unit)?,
) {
    val needle = query.trim()
    val filtered = if (needle.isEmpty()) workspaces else workspaces.filter {
        it.contains(needle, ignoreCase = true) || it.substringAfterLast('/').contains(needle, ignoreCase = true)
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = DshSpace.s16)) {
            SheetSearchField(value = query, onValueChange = onQueryChange, placeholder = DshS.workspaceSearchOrPath)
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            if (needle.startsWith("/") && needle !in workspaces) {
                DlListRow(
                    title = DshS.workspaceUsePath.format(needle.trimEnd('/').substringAfterLast('/')),
                    subtitle = abbreviateHomePath(needle),
                    leading = PlusOutline16,
                    leadingTint = DlTone.Brand,
                    onClick = { onPick(needle) },
                )
            }
            DlSectionHeader(DshS.homeRecent)
            when (catalogKind) {
                SessionListKind.Loading -> DlListRow(title = L.loading, enabled = false)
                SessionListKind.Error -> DlListRow(
                    title = catalogError ?: L.loadWorkspaceListFailed,
                    trailing = DlRowTrailing.TextAction(L.retry, onRetry),
                )
                SessionListKind.Content, SessionListKind.Empty -> {
                    filtered.forEach { ws ->
                        DlListRow(
                            title = ws.trimEnd('/').substringAfterLast('/'),
                            subtitle = abbreviateHomePath(ws),
                            leading = FolderClose16,
                            trailing = DlRowTrailing.Check(ws == selectedPath),
                            onClick = { onPick(ws) },
                        )
                    }
                    if (needle.isNotEmpty() && filtered.isEmpty() && !needle.startsWith("/")) {
                        DlListRow(title = L.noMatchingWorkspace.format(needle), enabled = false)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = DshSpace.s8), thickness = 1.dp, color = Dsh.outline)
            DlListRow(
                title = DshS.noWorkspaceBinding,
                subtitle = DshS.newTaskChatOnly,
                leading = ChatOutline16,
                trailing = DlRowTrailing.Check(selectedPath == null),
                onClick = { onPick(null) },
            )
            if (onAddWorkspace != null) {
                DlListRow(title = DshS.addWorkspace, leading = PlusOutline16, leadingTint = DlTone.Brand, onClick = onAddWorkspace)
            }
        }
    }
}

/** 3.3 添加工作区：一个输入框兼顾「新建同级目录」和「登记已有目录」，说明随输入变化。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddWorkspaceSheet(
    creationAnchor: MobileWorkspace?,
    onDismiss: () -> Unit,
    createWorkspace: (String, String?) -> MobileWorkspaceCreation,
    onCreated: (MobileWorkspace) -> Unit,
    onAuthExpired: (Throwable) -> Unit,
) {
    val sheetScope = rememberCoroutineScope()
    var workspaceInput by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    var pendingNotice by remember { mutableStateOf<String?>(null) }
    val trimmedInput = workspaceInput.trim()
    val isAbsolutePath = trimmedInput.startsWith("/")
    val anchorLabel = creationAnchor?.title?.takeIf { it.isNotBlank() }
        ?: creationAnchor?.path?.trimEnd('/', '\\')?.substringAfterLast('/')
        ?: ""
    val canSubmit = trimmedInput.isNotBlank() && !submitting && (isAbsolutePath || creationAnchor != null)
    val submit: () -> Unit = {
        val requestedInput = workspaceInput.trim()
        val parentWorkspaceId = if (requestedInput.startsWith("/")) null else creationAnchor?.workspaceId
        submitting = true
        submitError = null
        sheetScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { createWorkspace(requestedInput, parentWorkspaceId) }
                submitting = false
                val created = result.workspace
                if (result.pending || created == null) pendingNotice = L.workspaceApprovalPending else onCreated(created)
            } catch (error: Exception) {
                submitting = false
                if (isMobileAuthFailure(error)) {
                    onAuthExpired(error)
                } else {
                    submitError = error.message?.takeIf { it.isNotBlank() } ?: L.unknownError
                }
            }
        }
    }
    DlBottomSheet(onDismissRequest = { if (!submitting) onDismiss() }, title = L.addWorkspace) {
        AddWorkspaceContent(
            value = workspaceInput,
            onValueChange = {
                workspaceInput = it
                submitError = null
                pendingNotice = null
            },
            supporting = when {
                submitError != null -> L.addWorkspaceFailed.format(submitError)
                pendingNotice != null -> pendingNotice.orEmpty()
                isAbsolutePath -> L.workspaceRegisterExistingPath
                creationAnchor != null -> L.workspaceCreateNextTo.format(anchorLabel)
                else -> L.workspaceNameRequiresAnchor
            },
            isError = submitError != null,
            submitting = submitting,
            submitLabel = if (submitting) L.addingWorkspace else L.create,
            canSubmit = canSubmit,
            onCancel = onDismiss,
            onSubmit = submit,
        )
    }
}

@Composable
internal fun AddWorkspaceContent(
    value: String,
    onValueChange: (String) -> Unit,
    supporting: String,
    isError: Boolean,
    submitting: Boolean,
    submitLabel: String,
    canSubmit: Boolean,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpace.s24),
        verticalArrangement = Arrangement.spacedBy(DshSpace.s8),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = !submitting,
            singleLine = true,
            label = { Text(L.workspaceNameOrPath) },
            placeholder = { Text(L.workspacePathExample) },
            isError = isError,
            textStyle = DshType.body.copy(color = Dsh.labelPrimary),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Dsh.labelPrimary,
                unfocusedTextColor = Dsh.labelPrimary,
                disabledTextColor = Dsh.tertiaryText,
                focusedContainerColor = Dsh.bgBase.copy(alpha = 0f),
                unfocusedContainerColor = Dsh.bgBase.copy(alpha = 0f),
                disabledContainerColor = Dsh.bgBase.copy(alpha = 0f),
                cursorColor = Dsh.brand400,
                focusedBorderColor = Dsh.brand400,
                unfocusedBorderColor = Dsh.outline,
                disabledBorderColor = Dsh.outline,
                errorBorderColor = Dsh.err,
                focusedLabelColor = Dsh.brand400,
                unfocusedLabelColor = Dsh.labelSecondary,
                errorLabelColor = Dsh.err,
            ),
            shape = RoundedCornerShape(DshRadius.container),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(supporting, style = DshType.supporting, color = if (isError) Dsh.err else Dsh.labelSecondary)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = DshSpace.s8),
            horizontalArrangement = Arrangement.spacedBy(DshSpace.s8, androidx.compose.ui.Alignment.End),
        ) {
            DlButton(DlAction(L.cancel, onCancel, DlButtonStyle.Text, enabled = !submitting))
            DlButton(DlAction(submitLabel, onSubmit, DlButtonStyle.Filled, enabled = canSubmit))
        }
    }
}

/** 3.4 智能体预设：单选 + 一行说明；底部注明只影响之后新建的会话。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentPresetPickerSheet(
    presets: List<MobileAgentPreset>,
    currentId: String,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    DlBottomSheet(onDismissRequest = onDismiss, title = DshS.presetSheetTitle) {
        AgentPresetContent(presets, currentId, loading, error, onRetry, onSelect)
    }
}

@Composable
internal fun AgentPresetContent(
    presets: List<MobileAgentPreset>,
    currentId: String,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val kind = catalogKind(hasItems = presets.isNotEmpty(), initialLoad = loading && presets.isEmpty(), hasError = error != null)
    Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
        when (kind) {
            SessionListKind.Loading -> DlListRow(title = L.loadingPresets, enabled = false)
            SessionListKind.Error -> DlListRow(title = error ?: L.noAgentPresets, trailing = DlRowTrailing.TextAction(L.retry, onRetry))
            SessionListKind.Empty -> DlListRow(title = L.noAgentPresets, enabled = false)
            SessionListKind.Content -> {
                if (sessionShowsRefreshBanner(presets.isNotEmpty(), error != null)) {
                    DlListRow(title = error ?: L.loadFailed, trailing = DlRowTrailing.TextAction(L.retry, onRetry))
                }
                presets.forEach { preset ->
                    DlListRow(
                        title = presetDisplayName(preset.id, preset.name),
                        subtitle = presetDisplayDescription(preset.id, preset.description).ifBlank { null },
                        trailing = DlRowTrailing.Radio(preset.id == currentId),
                        onClick = { onSelect(preset.id) },
                    )
                }
                Text(
                    DshS.presetSheetFootnote,
                    style = DshType.supporting,
                    color = Dsh.labelSecondary,
                    modifier = Modifier.padding(horizontal = DshSpace.s24, vertical = DshSpace.s8),
                )
            }
        }
    }
}
