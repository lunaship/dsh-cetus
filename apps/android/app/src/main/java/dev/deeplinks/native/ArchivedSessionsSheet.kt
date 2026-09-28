package dev.deeplinks.native

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.deeplinks.core.DshS

import dev.deeplinks.native.ui.DshSheet
import dev.deeplinks.native.util.SessionListKind
import dev.deeplinks.native.util.SessionSnapshot
import dev.deeplinks.native.util.catalogKind

/**
 * 首页筛选菜单「已归档」打开的底部面板（2026-09-28 重设计 · 方案 3.2）。
 *
 * 方案要求「复用 `SettingsRoute.kt` 里的 `SessionsSettingsContent`，放进底部面板」。
 * 这里只做外壳与数据装配：列表渲染、行菜单、批量清除确认全部沿用设置页那一套，
 * 因此两处的行为不会各写一遍。
 *
 * 与设置页的差别只有数据来源：首页手上就有 `sessions` 与两个 id 集合（`archivedIds` /
 * `deletedIds`），不必再为这一屏单独拉一次接口。
 */
@Composable
internal fun ArchivedSessionsSheet(
    open: Boolean,
    archivedIds: Set<String>,
    deletedIds: Set<String>,
    hiddenIds: Set<String>,
    sessions: List<MobileSession>,
    /** 加载中：会话列表还没到（首页刚启动就点进来时会看到）。 */
    loading: Boolean = false,
    loadError: String? = null,
    onRestore: (String) -> Unit,
    onClear: (String) -> Unit,
    onClearAll: (Collection<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!open) return
    val s = DshS
    // 行数据：标题 / 路径 / 时间优先取线上会话，取不到就用 id 占位（与设置页同一条回退）
    val live = remember(sessions) { sessions.associateBy { it.sessionId } }
    fun row(id: String): SessionSnapshot {
        val found = live[id]
        return if (found != null) {
            SessionSnapshot(found.sessionId, found.title, found.cwd, found.updatedAt)
        } else {
            SessionSnapshot(id, id.take(8), null, 0L)
        }
    }
    val deletedRows = remember(deletedIds, hiddenIds, live) {
        (deletedIds - hiddenIds).map(::row).sortedByDescending { it.updatedAt }
    }
    val archivedRows = remember(archivedIds, deletedIds, hiddenIds, live) {
        (archivedIds - deletedIds - hiddenIds).map(::row).sortedByDescending { it.updatedAt }
    }
    val total = archivedRows.size + deletedRows.size
    var pendingClearAll by remember { mutableStateOf(false) }

    DshSheet(
        onDismiss = onDismiss,
        title = s.homeArchivedSessions,
        showClose = true,
        skipPartiallyExpanded = true,
    ) {
        SessionsSettingsContent(
            listKind = catalogKind(hasItems = total > 0, initialLoad = loading, hasError = loadError != null),
            loadError = loadError,
            archivedRows = archivedRows,
            deletedRows = deletedRows,
            onRetry = {},
            onRestore = onRestore,
            onClear = onClear,
            onClearAll = { pendingClearAll = true },
        )
    }

    if (pendingClearAll) {
        DshConfirmDialog(
            title = s.clearAllSessionsTitle,
            message = s.clearAllSessionsMessage.format(total),
            confirmLabel = s.clearAllLocalRecords,
            danger = true,
            onDismiss = { pendingClearAll = false },
            onConfirm = {
                pendingClearAll = false
                onClearAll((archivedRows + deletedRows).map { it.sessionId })
            },
        )
    }
}

/** 便于下一片接线时少写几行：面板状态与动作各打包一件（同 NewTaskSheet 的做法）。 */
internal class ArchivedSessionsActions(
    val onRestore: (String) -> Unit,
    val onClear: (String) -> Unit,
    val onClearAll: (Collection<String>) -> Unit,
    val onDismiss: () -> Unit,
)

/** 数据源是否可用（列表还没加载时面板要显示加载态而不是「空」）。 */
internal fun archivedSheetLoading(sessionsInitialLoad: Boolean, hasSessions: Boolean): Boolean =
    sessionsInitialLoad && !hasSessions

internal val SESSION_LIST_KIND_CONTENT: SessionListKind = SessionListKind.Content
