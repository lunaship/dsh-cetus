package dev.deeplinks.native.util

/** 聊天画布空列表时的互斥态（注释约定 load / empty / error 互斥）。 */
enum class ChatCanvasKind { Loading, Error, Working, Empty, Content }

fun chatCanvasKind(
    hasMessages: Boolean,
    initialLoadInFlight: Boolean,
    hasHistoryError: Boolean,
    working: Boolean,
): ChatCanvasKind = when {
    hasMessages -> ChatCanvasKind.Content
    initialLoadInFlight -> ChatCanvasKind.Loading
    hasHistoryError -> ChatCanvasKind.Error
    working -> ChatCanvasKind.Working
    else -> ChatCanvasKind.Empty
}

enum class LoadOlderKind { Loading, Failed, Idle }

fun loadOlderKind(loading: Boolean, failed: Boolean): LoadOlderKind = when {
    loading -> LoadOlderKind.Loading
    failed -> LoadOlderKind.Failed
    else -> LoadOlderKind.Idle
}

enum class SessionListKind { Loading, Error, Empty, Content }

fun catalogKind(
    hasItems: Boolean,
    initialLoad: Boolean,
    hasError: Boolean,
): SessionListKind = when {
    hasItems -> SessionListKind.Content
    initialLoad -> SessionListKind.Loading
    hasError -> SessionListKind.Error
    else -> SessionListKind.Empty
}

fun sessionListKind(
    hasSessions: Boolean,
    initialLoad: Boolean,
    hasError: Boolean,
): SessionListKind = catalogKind(hasItems = hasSessions, initialLoad = initialLoad, hasError = hasError)

/** 已有会话时刷新失败：列表仍显示，顶上带重试，不再 toast。 */
fun sessionShowsRefreshBanner(hasSessions: Boolean, hasError: Boolean): Boolean =
    hasSessions && hasError

enum class RenameDialogKind { Idle, Saving, Failed }

/** 保存失败时弹窗保持打开，错误写在对话框内，不再先关再 toast。 */
fun renameDialogKind(saving: Boolean, error: String?): RenameDialogKind = when {
    saving -> RenameDialogKind.Saving
    !error.isNullOrBlank() -> RenameDialogKind.Failed
    else -> RenameDialogKind.Idle
}

/** 归档/删除只有服务端接受后才本机隐藏，避免列表和主机不一致。 */
fun localHideAfterRemote(accepted: Boolean): Boolean = accepted

/** 分叉响应必须带回新会话 id，空 id 视为失败。 */
fun forkAccepted(newSessionId: String?): Boolean = !newSessionId.isNullOrBlank()

/** 智能体权限预设的展示名（设置首页电脑卡与设置二级页共用，避免两处 when 各写一遍）。 */
fun permissionPresetLabel(preset: String, strings: dev.deeplinks.core.DshStrings): String = when (preset) {
    "read-only" -> strings.permReadOnly
    "danger-full-access" -> strings.permFullAccess
    else -> strings.permWorkspaceWrite
}

/**
 * 电脑显示名：本机别名 > host.name > 地址（2026-09-28 重设计 · 方案 3/7）。
 *
 * 「电脑名用用户起的名字」——没起过名才退回配对时的名字，连名字都没有就写地址，
 * 不留空字符串（顶栏空白比写地址更难懂）。
 */
fun hostDisplayLabel(alias: String?, hostName: String?, address: String?): String =
    listOfNotNull(alias, hostName, address)
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
