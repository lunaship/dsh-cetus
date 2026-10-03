package dev.deeplinks.native

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.deeplinks.core.AppSettingsStore
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.Host
import dev.deeplinks.core.L
import dev.deeplinks.core.permissionEnable
import dev.deeplinks.core.permissionSheetSessionNote
import dev.deeplinks.native.ui.v4.DlAction
import dev.deeplinks.native.ui.v4.DlBottomSheet
import dev.deeplinks.native.ui.v4.DlButtonStyle
import dev.deeplinks.native.ui.v4.DlDialog
import dev.deeplinks.native.ui.v4.DlListRow
import dev.deeplinks.native.ui.v4.DlRowTrailing
import dev.deeplinks.native.ui.v4.DlTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------- 权限选择弹层（WI-004：真实写入服务端 permission.defaultPreset） ----------

@OptIn(ExperimentalMaterial3Api::class)
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

    DlBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, title = L.accessMode) {
        PermissionPickerContent(
            selected = selected,
            saving = saving,
            error = error,
            footnote = if (sessionId != null) DshS.permissionSheetSessionNote else L.defaultPermissionDesc,
            onSelect = { preset ->
                if (preset == "danger-full-access") {
                    showFullAccessConfirm = true
                } else {
                    selected = preset
                    apply(preset)
                }
            },
            onRetry = { apply(selected) },
        )
    }

    if (showFullAccessConfirm) {
        DlDialog(
            onDismissRequest = { showFullAccessConfirm = false },
            title = L.confirmFullAccessTitle,
            text = L.confirmFullAccessMessage,
            icon = ShieldOutline16,
            iconTone = DlTone.Wait,
            dismiss = DlAction(L.cancel, { showFullAccessConfirm = false }),
            confirm = DlAction(
                DshS.permissionEnable,
                {
                    showFullAccessConfirm = false
                    selected = "danger-full-access"
                    apply("danger-full-access")
                },
                style = DlButtonStyle.Danger,
            ),
        )
    }
}

/** 5.3 权限三项单选；「完全权限」图标和标题用橙色。弹层与截图共用。 */
@Composable
internal fun PermissionPickerContent(
    selected: String,
    saving: Boolean,
    error: String?,
    footnote: String,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
) {
    PermissionOptions.forEach { option ->
        val full = option.preset == "danger-full-access"
        DlListRow(
            title = option.title(),
            subtitle = option.subtitle(),
            leading = option.icon,
            leadingTint = if (full) DlTone.Wait else null,
            titleTone = if (full) DlTone.Wait else null,
            trailing = DlRowTrailing.Radio(selected == option.preset),
            enabled = !saving || selected == option.preset,
            onClick = { if (!saving) onSelect(option.preset) },
        )
    }
    if (error != null) {
        DlListRow(
            title = L.saveFailedWithMessage.format(error),
            danger = true,
            trailing = DlRowTrailing.TextAction(L.retry, onRetry),
        )
    }
    Text(
        if (saving) L.saving else footnote,
        style = DshType.supporting,
        color = Dsh.labelSecondary,
        modifier = Modifier.padding(horizontal = DshSpace.s24, vertical = DshSpace.s8),
    )
}

private class PermissionOption(
    val preset: String,
    val icon: ImageVector,
    val title: @Composable () -> String,
    val subtitle: @Composable () -> String,
)

private val PermissionOptions = listOf(
    PermissionOption("read-only", BrowseOutline16, { L.permReadOnly }, { L.permReadOnlySub }),
    PermissionOption("workspace-write", FolderOpenOutline16, { L.permWorkspaceWrite }, { L.permWorkspaceWriteSub }),
    PermissionOption("danger-full-access", ShieldOutline16, { L.permFullAccess }, { L.permFullAccessSub }),
)
