package dev.deeplinks.native

import dev.deeplinks.core.L

import androidx.compose.ui.unit.dp

/** 输入条 / 消息列宽度。手机原生铺满，左右 12dp 让悬浮卡更贴边。 */
internal val COMPOSER_SIDE_CLEARANCE = 12.dp

/** R11 浮岛化（2026-10-01）：输入卡到浮岛内缘的间距（岛外缘另有 COMPOSER_SIDE_CLEARANCE）。 */
internal val COMPOSER_ISLAND_INNER_CLEARANCE = 8.dp
internal val COMPOSER_COMPACT_WIDTH = 360.dp
internal val COMPOSER_MODEL_MAX_WIDTH = 132.dp

/** 访问模式座文字上限（DSH PermissionSelect 的 max-width 220px；手机上够装「Workspace Write」）。 */
internal val COMPOSER_ACCESS_MAX_WIDTH = 124.dp

fun composerIsCompact(widthDp: Float): Boolean = widthDp < COMPOSER_COMPACT_WIDTH.value

/**
 * 输入卡实际可用宽度（容器宽 - 岛外 12dp - 岛内 8dp 每侧）是否进入紧凑档。
 * 分屏/自由窗口下容器宽会变小，所以按容器宽推导，不看 screenWidthDp。
 */
fun composerSeatsCompact(containerWidthDp: Float): Boolean =
    composerIsCompact(
        containerWidthDp - 2 * (COMPOSER_SIDE_CLEARANCE + COMPOSER_ISLAND_INNER_CLEARANCE).value,
    )

/**
 * 模型座内容（DSH `conversation.input.model`）：名称与推理等级拆成两段，
 * 等级是次级文本、空间不足时先被挤掉；名称缺省时由座位显示「选择模型」。
 */
internal data class ComposerModelSeat(val name: String?, val effort: String?)

internal fun composerModelSeat(
    catalog: MobileModelCatalog?,
    pending: Triple<String, String, String?>? = null,
): ComposerModelSeat {
    val current = catalog
    val requested = pending?.second ?: current?.currentModel
    val option = requested?.let { id ->
        current?.groups?.asSequence()
            ?.flatMap { it.models.asSequence() }
            ?.firstOrNull { it.id == id }
    }
    val effort = pending?.third ?: current?.currentReasoningEffort ?: option?.defaultEffort
    val cleanEffort = effort?.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
    return ComposerModelSeat(
        // 目录里有展示名就用展示名（pending 只有 id）
        name = option?.name ?: requested,
        effort = cleanEffort,
    )
}

/**
 * 草稿态模型座兜底（N2）：目录还没回来（座位为空名）时显示全局默认模型，
 * 避免座位短暂显示「选择模型」或上个会话的模型。纯函数，可单测。
 */
internal fun composerModelSeatOrDefault(
    catalog: MobileModelCatalog?,
    pending: Triple<String, String, String?>?,
    defaultModel: String?,
    defaultEffort: String?,
): ComposerModelSeat {
    val seat = composerModelSeat(catalog, pending)
    if (!seat.name.isNullOrBlank()) return seat
    return ComposerModelSeat(
        name = defaultModel,
        effort = defaultEffort?.takeUnless { it.isBlank() || it == "null" },
    )
}

/**
 * 手机端只认 DSH 三个权限预设；表外值（含旧值、自定义）按默认的
 * `workspace-write` 显示，避免座位显示成空白或服务端拒绝的名字。
 */
internal fun canonicalComposerPermission(preset: String?): String = when (preset) {
    "read-only" -> "read-only"
    "danger-full-access" -> "danger-full-access"
    else -> "workspace-write"
}

/** Full access 在座位上要显风险色（DSH 的 Auto review/Full access 也是显式风险档）。 */
internal fun composerPermissionIsDanger(preset: String?): Boolean =
    canonicalComposerPermission(preset) == "danger-full-access"

/** 已开聊时工作区/Harness 在侧栏，输入条上不再堆只读标签。 */
fun composerShowsSetupRow(
    workspaceEditable: Boolean,
    showHarness: Boolean,
    harnessLabel: String = "",
): Boolean = workspaceEditable || (showHarness && harnessLabel.isNotBlank())

/** 发送/停止失败写在输入槽内；发送中不保留上一次错误。 */
fun composerShowsActionError(error: String?, sending: Boolean): Boolean =
    !sending && !error.isNullOrBlank()

/** 已开聊且刚改过本会话权限时，输入条 chip 显示这次选择，而不是全局默认。 */
fun composerPermissionPreset(
    sessionId: String?,
    sessionOverrides: Map<String, String>,
    defaultPreset: String,
): String = sessionId?.let { sessionOverrides[it] } ?: defaultPreset

/** 无会话时 slash 完整命令不得清空输入；有会话才提交并清空。 */
fun completableCanSubmit(hasSession: Boolean): Boolean = hasSession

/**
 * 草稿态模式行显示的预设 id（N3）：用户选过（pending 非空）就用它，否则回落全局默认。
 * 纯函数，选择器写入 pendingAgentPreset 后这里立刻反映新值。
 */
internal fun draftPresetId(pending: String?, defaultPreset: String): String =
    pending?.takeIf { it.isNotBlank() } ?: defaultPreset

/** 访问模式预设 → 显示名（输入卡与新任务面板的座位共用）。 */
internal fun composerPermissionLabel(preset: String): String = when (preset) {
    "read-only" -> L.permReadOnly
    "danger-full-access" -> L.permFullAccess
    else -> L.permWorkspaceWrite
}

/** K3 聚焦触发来源。 */
internal enum class ComposerFocusSource {
    /** 点「+ 新任务」进草稿态。 */
    NewTaskDraft,

    /** 通知「回复」动作。 */
    NotificationReply,

    /** 命令面板插入指令。 */
    CommandInsert,

    /** 首页左滑归档 / 长按删除当前会话触发的草稿态——对话页不可见。 */
    ArchiveOnHome,
}

/**
 * K3：这个来源是否应该发出聚焦令牌。
 *
 * 只有「对话页确实在显示」的来源才发；首页归档当前会话会进入草稿态但对话页/输入框根本没组合，
 * 历史上在这里直接 `requestFocus()` 会抛 `FocusRequester is not initialized` 闪退——这里显式排除。
 * 真正的 `requestFocus()` 只在 [InputBar] 内部执行，未组合时令牌也不会生效。
 */
internal fun composerFocusShouldEmit(source: ComposerFocusSource): Boolean =
    source != ComposerFocusSource.ArchiveOnHome

/**
 * 输入条占位文案：听写中 / 执行中 / 空闲。
 * 执行中要写清楚这条消息的去向（方案 5.5：补充说明，这一步结束后发给它），
 * 不能沿用空闲时的「给智能体发消息」，否则用户不知道是插话还是排队。
 */
internal fun composerPlaceholder(isListening: Boolean, running: Boolean): String = when {
    isListening -> L.listening
    running -> L.composerRunningQueue
    else -> L.chatPlaceholder
}
