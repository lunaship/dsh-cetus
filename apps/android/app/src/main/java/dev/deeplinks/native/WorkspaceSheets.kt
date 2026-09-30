package dev.deeplinks.native

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import dev.deeplinks.core.DshType

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.L
import dev.deeplinks.native.ui.DshListActionRow
import dev.deeplinks.native.ui.DshListNote
import dev.deeplinks.native.ui.DshListRetry
import dev.deeplinks.native.ui.DshListRow
import dev.deeplinks.native.ui.DshListSection
import dev.deeplinks.native.ui.DshListTrailing
import dev.deeplinks.native.ui.DshSheet
import dev.deeplinks.native.ui.DshSheetPrimaryButton
import dev.deeplinks.native.util.abbreviateHomePath
import dev.deeplinks.native.util.SessionListKind
import dev.deeplinks.native.util.visibleUserWorkspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------- 添加工作区 ----------

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

    DshSheet(
        onDismiss = { if (!submitting) onDismiss() },
        title = L.addWorkspace,
        subtitle = L.addWorkspaceDesc,
        skipPartiallyExpanded = true,
    ) {
        Spacer(Modifier.height(DshSpace.s12))
        OutlinedTextField(
            value = workspaceInput,
            onValueChange = {
                workspaceInput = it
                submitError = null
                pendingNotice = null
            },
            enabled = !submitting,
            singleLine = true,
            label = { Text(L.workspaceNameOrPath) },
            placeholder = { Text(L.workspacePathExample) },
            leadingIcon = {
                Icon(
                    ProjectAddOutline16,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            isError = submitError != null,
            supportingText = {
                val err = submitError
                val notice = pendingNotice
                val supporting = when {
                    err != null -> L.addWorkspaceFailed.format(err)
                    notice != null -> notice
                    isAbsolutePath -> L.workspaceRegisterExistingPath
                    creationAnchor != null -> L.workspaceCreateNextTo.format(anchorLabel)
                    else -> L.workspaceNameRequiresAnchor
                }
                Text(supporting, style = DshType.captionRelaxed)
            },
            textStyle = DshType.body.copy(color = Dsh.labelPrimary),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Dsh.labelPrimary,
                unfocusedTextColor = Dsh.labelPrimary,
                disabledTextColor = Dsh.labelTertiary,
                // 输入面统一 bgInput（docs/visual-rules.md 第二节 Input 角色）
                focusedContainerColor = Dsh.bgInput,
                unfocusedContainerColor = Dsh.bgInput,
                disabledContainerColor = Dsh.bgInput.copy(alpha = 0.6f),
                cursorColor = Dsh.brand400,
                focusedBorderColor = Dsh.brand400,
                unfocusedBorderColor = Dsh.borderSubtle,
                disabledBorderColor = Dsh.borderSubtle,
                errorBorderColor = Dsh.error,
                focusedLabelColor = Dsh.brand400,
                unfocusedLabelColor = Dsh.labelTertiary,
                errorLabelColor = Dsh.error,
                focusedLeadingIconColor = Dsh.brand400,
                unfocusedLeadingIconColor = Dsh.labelTertiary,
                errorLeadingIconColor = Dsh.error,
                focusedSupportingTextColor = Dsh.labelTertiary,
                unfocusedSupportingTextColor = Dsh.labelTertiary,
                errorSupportingTextColor = Dsh.error,
            ),
            shape = RoundedCornerShape(DshRadius.container),
            modifier = Modifier.fillMaxWidth(),
        )
        DshSheetPrimaryButton(
            label = when {
                submitting -> L.addingWorkspace
                isAbsolutePath -> L.addExistingWorkspace
                else -> L.createAndAddWorkspace
            },
            enabled = canSubmit,
        ) {
            val requestedInput = workspaceInput.trim()
            val parentWorkspaceId = if (requestedInput.startsWith("/")) null else creationAnchor?.workspaceId
            submitting = true
            submitError = null
            sheetScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        createWorkspace(requestedInput, parentWorkspaceId)
                    }
                    submitting = false
                    val created = result.workspace
                    if (result.pending || created == null) {
                        pendingNotice = L.workspaceApprovalPending
                    } else {
                        onCreated(created)
                    }
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
    }
}

// ---------- 选择工作区 ----------

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
    val filtered = remember(workspaces, query) {
        if (query.isBlank()) workspaces
        else workspaces.filter {
            it.contains(query, ignoreCase = true) || it.substringAfterLast('/').contains(query, ignoreCase = true)
        }
    }
    DshSheet(onDismiss = onDismiss, title = L.chooseWorkspaceTitle, subtitle = L.chooseWorkspaceDesc) {
        if (workspaces.size > 6) {
            Spacer(Modifier.height(DshSpace.s8))
            SheetSearchField(value = query, onValueChange = { query = it }, placeholder = L.searchWorkspace)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            DshListSection {
                WorkspaceOptionRow(
                    title = L.ungrouped,
                    path = L.noWorkspaceBinding,
                    selected = selectedPath == null,
                    onClick = {
                        onDismiss()
                        onPick(null)
                    },
                )
                when (catalogKind) {
                    SessionListKind.Loading -> DshListNote(L.loading)
                    SessionListKind.Error -> DshListRetry(catalogError ?: L.loadWorkspaceListFailed, onRetry)
                    SessionListKind.Content, SessionListKind.Empty -> {
                        filtered.forEach { ws ->
                            WorkspaceOptionRow(
                                title = ws.substringAfterLast('/'),
                                path = ws,
                                selected = ws == selectedPath,
                                onClick = {
                                    onDismiss()
                                    onPick(ws)
                                },
                            )
                        }
                        if (query.isNotBlank() && filtered.isEmpty()) {
                            DshListNote(L.noMatchingWorkspace.format(query))
                        }
                    }
                }
            }
            AddWorkspaceRow(
                onCreate = { path ->
                    onDismiss()
                    onPick(path)
                },
            )
        }
    }
}

/** 面板里的搜索框：分组卡片同色的圆角输入条，放在冷灰面板底上。 */
@Composable
internal fun SheetSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DshTouch.min)
            .clip(RoundedCornerShape(DshRadius.container))
            .background(Dsh.bgInput)
            .padding(start = DshSpace.s12),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(SearchOutline16, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(DshSpace.s8))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = DshType.body.copy(color = Dsh.labelPrimary),
            cursorBrush = SolidColor(Dsh.brand400),
            modifier = Modifier
                .weight(1f)
                .padding(end = if (value.isEmpty()) 12.dp else 0.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, color = Dsh.labelTertiary, style = DshType.body)
                    inner()
                }
            }
        )
        if (value.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = L.clearSearch
                    }
                    .clickable { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(CloseOutline16, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
internal fun WorkspaceOptionRow(
    title: String,
    path: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DshListRow(
        title = title,
        subtitle = abbreviateHomePath(path),
        icon = FolderOpenOutline16,
        iconTint = if (selected) Dsh.labelPrimary else Dsh.labelSecondary,
        onClick = onClick,
        trailing = if (selected) DshListTrailing.Check else DshListTrailing.None,
    )
}

/** 「添加工作区」卡片：点按展开路径输入，输入行与按钮同在卡片里。 */
@Composable
internal fun AddWorkspaceRow(onCreate: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf("") }
    DshListSection {
        DshListRow(
            title = L.addWorkspace,
            icon = PlusOutline16,
            onClick = { expanded = !expanded },
            trailing = DshListTrailing.None,
            trailingContent = {
                Icon(
                    if (expanded) ChevronUpOutline14 else ChevronDownOutline14,
                    contentDescription = null,
                    tint = Dsh.labelTertiary,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
        AnimatedVisibility(visible = expanded) {
            Row(
                modifier = Modifier.padding(start = DshSpace.s16, end = DshSpace.s12, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = path,
                    onValueChange = { path = it },
                    singleLine = true,
                    textStyle = DshType.body.copy(color = Dsh.labelPrimary),
                    cursorBrush = SolidColor(Dsh.brand400),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = DshTouch.min)
                        .clip(RoundedCornerShape(DshRadius.control))
                        .background(Dsh.bgSubtle)
                        .padding(horizontal = DshSpace.s12, vertical = DshSpace.s12),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (path.isEmpty()) Text(L.enterWorkspacePath, color = Dsh.labelTertiary, style = DshType.body)
                            inner()
                        }
                    }
                )
                Spacer(Modifier.width(DshSpace.s8))
                Button(
                    onClick = { onCreate(path.trim()) },
                    enabled = path.isNotBlank(),
                    shape = RoundedCornerShape(DshRadius.full),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Dsh.brand400,
                        contentColor = Dsh.onBrand,
                        disabledContainerColor = Dsh.bgTrack,
                        disabledContentColor = Dsh.labelTertiary,
                    ),
                ) {
                    Text(L.create, style = DshType.labelLarge)
                }
            }
        }
    }
}

// ---------- 子智能体 ----------

@Composable
internal fun SubagentBottomSheet(
    sessions: List<MobileSession>,
    currentSession: MobileSession?,
    currentSessionId: String?,
    onSelectSession: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val anchorParent = currentSession?.parentSessionId ?: currentSessionId
    val children = remember(sessions, anchorParent) {
        sessions.filter { it.origin == "subagent" && it.parentSessionId == anchorParent }
            .sortedByDescending { it.updatedAt }
    }
    val parentOfCurrent = currentSession?.parentSessionId
    DshSheet(
        onDismiss = onDismiss,
        title = L.subagents,
        subtitle = if (children.isEmpty()) L.noSubagentSessions else L.subagentSheetSummary.format(children.size),
    ) {
        if (children.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                DshListSection {
                    children.forEach { child ->
                        DshListRow(
                            title = child.title,
                            subtitle = if (child.running) L.runningStatus else null,
                            leading = {
                                if (child.running) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        color = Dsh.labelSecondary,
                                        strokeWidth = 1.5.dp,
                                    )
                                } else {
                                    Icon(BranchOutline16, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(18.dp))
                                }
                            },
                            onClick = {
                                onDismiss()
                                onSelectSession(child.sessionId)
                            },
                            trailing = if (child.sessionId == currentSessionId) DshListTrailing.Check else DshListTrailing.None,
                        )
                    }
                }
            }
        }
        if (!parentOfCurrent.isNullOrBlank()) {
            DshListSection {
                DshListActionRow(
                    label = L.returnToParentSession,
                    onClick = {
                        onDismiss()
                        onSelectSession(parentOfCurrent)
                    },
                )
            }
        }
    }
}
