package dev.deeplinks.native

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.deeplinks.core.AppSettingsStore
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.Host
import dev.deeplinks.core.L
import dev.deeplinks.native.ui.DshListRetry
import dev.deeplinks.native.ui.DshListRow
import dev.deeplinks.native.ui.DshListSection
import dev.deeplinks.native.ui.DshListTrailing
import dev.deeplinks.native.ui.DshSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------- 权限选择弹层（WI-004：真实写入服务端 permission.defaultPreset） ----------

@Composable
internal fun PermissionPickerSheet(
    context: android.content.Context,
    host: Host,
    currentPreset: String,
    sessionId: String? = null,
    onSaved: (AppSettings) -> Unit,
    onSessionPreset: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(currentPreset) }
    var showFullAccessConfirm by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun apply(preset: String) {
        if (saving) return
        saving = true
        error = null
        scope.launch(Dispatchers.IO) {
            try {
                if (sessionId != null) {
                    MobileApiClient(host).setSessionPermission(sessionId, preset)
                    withContext(Dispatchers.Main) {
                        saving = false
                        onSessionPreset(preset)
                        onDismiss()
                    }
                } else {
                    AppSettingsStore.save(host, context, "permission", org.json.JSONObject().put("defaultPreset", preset))
                    withContext(Dispatchers.Main) {
                        saving = false
                        onSaved(AppSettingsStore.cached(context, host))
                        onDismiss()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    saving = false
                    error = e.message ?: L.saveFailed
                }
            }
        }
    }

    DshSheet(
        onDismiss = { if (!saving) onDismiss() },
        title = L.accessMode,
        subtitle = if (sessionId != null) L.currentSessionPermissionDesc else L.defaultPermissionDesc,
    ) {
        DshListSection(footer = if (saving) L.saving else null) {
            PermissionModeOption(
                title = L.permReadOnly,
                desc = L.readOnlyPermissionDesc,
                sub = L.permReadOnlySub,
                accent = Dsh.labelSecondary,
                icon = BrowseOutline16,
                selected = selected == "read-only",
                enabled = !saving,
                onClick = {
                    selected = "read-only"
                    apply("read-only")
                },
            )
            PermissionModeOption(
                title = L.permWorkspaceWrite,
                desc = L.workspaceWritePermissionDesc,
                sub = L.permWorkspaceWriteSub,
                accent = Dsh.brand400,
                icon = FolderOpenOutline16,
                selected = selected == "workspace-write",
                enabled = !saving,
                onClick = {
                    selected = "workspace-write"
                    apply("workspace-write")
                },
            )
            PermissionModeOption(
                title = L.permFullAccess,
                desc = L.fullAccessPermissionDesc,
                sub = L.permFullAccessSub,
                accent = Dsh.warn,
                icon = WarningOutline16,
                selected = selected == "danger-full-access",
                enabled = !saving,
                onClick = { showFullAccessConfirm = true },
            )
        }
        error?.let { message ->
            DshListSection { DshListRetry(L.saveFailedWithMessage.format(message)) { apply(selected) } }
        }
    }

    if (showFullAccessConfirm) {
        DshConfirmDialog(
            title = L.confirmFullAccessTitle,
            message = L.confirmFullAccessMessage,
            confirmLabel = L.enableFullAccess,
            danger = true,
            onDismiss = { showFullAccessConfirm = false },
            onConfirm = {
                showFullAccessConfirm = false
                selected = "danger-full-access"
                apply("danger-full-access")
            },
        )
    }
}

/** 单选分组行：语义色图标 + 标题 + 说明，选中打勾。 */
@Composable
internal fun PermissionModeOption(
    title: String,
    desc: String,
    accent: Color,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    sub: String? = null,
) {
    DshListRow(
        title = title,
        subtitle = listOfNotNull(desc, sub).joinToString("\n"),
        icon = icon,
        iconTint = accent,
        enabled = enabled || selected,
        onClick = if (enabled) onClick else null,
        trailing = if (selected) DshListTrailing.Check else DshListTrailing.None,
    )
}
