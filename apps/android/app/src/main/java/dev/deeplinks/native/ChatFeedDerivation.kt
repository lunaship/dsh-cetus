package dev.deeplinks.native

import dev.deeplinks.native.util.MessageGroup
import dev.deeplinks.native.util.groupMessages
import dev.deeplinks.native.util.MessageKind
import dev.deeplinks.native.util.resolvedMessageKind

internal data class ChatFeedModel(
    val lastCompletedAssistantId: String?,
    val visibleGroups: List<MessageGroup>,
)

/**
 * 消息流数据推导（从 WorkspaceScreen 抽出，COM-001 拆解）。
 *
 * 纯函数、无 Compose 依赖，因此这几条原本埋在 composable 里无法验证的规则现在可单测：
 * 1. 合并更早的历史页；
 * 2. 丢弃「空的、且不在运行中的」思考行；
 * 3. 按相邻 tool_call/tool_result 聚合（见 groupMessages）。
 */
internal fun deriveChatFeed(
    olderMessages: List<MobileMessage>,
    messages: List<MobileMessage>,
): ChatFeedModel {
    val allMessages = mergeHistoryPages(olderMessages, messages)
    val displayMessages = dedupeById(
        allMessages.filterNot {
            (it.role == "reasoning" && it.text.isBlank() && it.running != true) ||
                isHiddenContextInjection(it)
        },
    )
    val lastCompletedAssistantId = displayMessages.lastOrNull {
        it.role == "assistant" && it.running != true
    }?.id
    val messageGroups = groupMessages(displayMessages)
    return ChatFeedModel(lastCompletedAssistantId, messageGroups)
}

/**
 * K4：过滤上下文注入后仍可能因服务端返回重复 messageId 而出现重复 key
 * （`LazyColumn` 抛「Key was already used」）。这里按 id 去重、保留第一条；
 * id 为空的（极少见）原样保留，避免把不同消息折叠成一条。
 */
internal fun dedupeById(messages: List<MobileMessage>): List<MobileMessage> {
    val seen = HashSet<String>()
    return messages.filter { it.id.isBlank() || seen.add(it.id) }
}

/**
 * 对话 Tab 是否隐藏这条消息（C3）：DSH 注入的 system-reminder / runtime context / skill catalog
 * 不再铺进对话流（轨迹 Tab 仍能看到过程）；目标轮次（`<goal_round>`）是用户可见的目标信息，保留。
 */
internal fun isHiddenContextInjection(msg: MobileMessage): Boolean =
    resolvedMessageKind(msg.role, msg.kind, msg.text) == MessageKind.INJECTION

/**
 * 「正在扫过」的行 id：只有运行中的 tool_call / tool_result / reasoning 才能抢状态条，
 * 已定稿的「已思考」不行。纯函数、可单测。
 */
internal fun resolveSweepingId(messages: List<MobileMessage>, running: Boolean): String? {
    if (!running) return null
    return messages.lastOrNull {
        (it.role == "tool_call" || it.role == "tool_result" || it.role == "reasoning") &&
            it.running == true
    }?.id
}

/** 当 running 且最后一项是运行中的 Reasoning 时，不显示顶部的「思考中」行。 */
internal fun shouldShowTurnStatus(items: List<MobileMessage>, running: Boolean): Boolean {
    if (!running) return false
    // 已有正在流式的思考行时，它本身就是「思考中」指示，流尾不再叠一条
    return items.none { it.role == "reasoning" && it.running == true }
}

/** 本条助手消息是否是一轮的末尾（下一条是用户消息，或是最后一条且已停止）。 */
internal fun isTurnEnd(groups: List<MessageGroup>, index: Int, running: Boolean): Boolean {
    val current = groups.getOrNull(index) ?: return false
    val msg = (current as? MessageGroup.Single)?.msg ?: return false
    if (msg.role != "assistant") return false
    // 轮末 = 到下一条用户消息（或列表末尾且未在运行）之前，后面只剩系统提示 / 改动 / 产出文件这类尾项
    for (j in index + 1..groups.lastIndex) {
        val next = groups[j]
        val role = (next as? MessageGroup.Single)?.msg?.role ?: return false
        when (role) {
            "user" -> return true
            "assistant", "reasoning", "tool_call", "tool_result", "approval", "question" -> return false
        }
    }
    return !running
}
