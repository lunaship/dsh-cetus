package dev.deeplinks.native.util

import androidx.compose.runtime.Stable
import dev.deeplinks.native.MobileMessage

/**
 * 消息流分组（WI-005 / WI-006 抽出）：把相邻 tool_call/tool_result 消息
 * 合并为一个聚合组渲染，提升 LazyColumn 性能与视觉聚合。
 *
 * 拆分规则：
 * - 连续 ≥2 条 [MobileMessage.role] == `"tool_call"` 或 `"tool_result"` 合并为一个 [ToolGroup]；
 * - <2 退化为 [Single]，逐条渲染；
 * - 其他 role 始终是 [Single]。
 *
 * 纯函数 + 数据类，无 Android 依赖 → 可 JVM 单测。
 */
@Stable
interface MessageGroup {
    val groupKey: String

    data class Single(val msg: MobileMessage) : MessageGroup {
        override val groupKey: String get() = msg.id
    }

    data class ToolGroup(val items: List<MobileMessage>) : MessageGroup {
        override val groupKey: String get() = items.first().id + "-group"
    }

    data class ToolSummary(
        /** 本批第一条工具消息 id：做 LazyColumn key，保证同一会话里多轮相同工具组合也不撞 key。 */
        val firstId: String,
        /** 工具调用次数（按 tool_call 计；没有 tool_call 时退化为条数）。 */
        val count: Int,
        val failedCount: Int,
        val durationMs: Long?,
        val toolNames: List<String>,
        /** 本批里还有工具在执行。 */
        val running: Boolean = false,
        /** 最后一个工具名（执行中时显示「正在 X…」）。 */
        val lastToolName: String? = null,
    ) : MessageGroup {
        override val groupKey: String get() = "$firstId-toolsummary"
    }
}

/** 把消息列表按相邻 tool_call/tool_result 切分（≥2 聚合；其他退化为 Single）。 */
fun groupMessages(messages: List<MobileMessage>): List<MessageGroup> {
    if (messages.isEmpty()) return emptyList()
    val out = mutableListOf<MessageGroup>()
    var i = 0
    while (i < messages.size) {
        val msg = messages[i]
        if (msg.role == "tool_call" || msg.role == "tool_result") {
            var j = i
            while (j < messages.size && (messages[j].role == "tool_call" || messages[j].role == "tool_result")) j++
            val slice = messages.subList(i, j)
            out.add(if (slice.size >= 2) MessageGroup.ToolGroup(slice) else MessageGroup.Single(slice[0]))
            i = j
        } else {
            out.add(MessageGroup.Single(msg))
            i++
        }
    }
    return out
}

/**
 * 每轮最后一条助手回复的 id：复制 / 赞踩 / 时间这一行只挂在这里，
 * 工具调用之间的过程说明不再各挂一行（长按菜单仍可复制）。
 * 以用户消息切轮；最后一轮还在运行时不算结束，免得操作行跟着新回复跳来跳去。
 */
fun turnEndAssistantIds(groups: List<MessageGroup>, running: Boolean): Set<String> {
    val out = mutableSetOf<String>()
    var pending: String? = null
    for (group in groups) {
        val msg = (group as? MessageGroup.Single)?.msg ?: continue
        when (msg.role) {
            "user" -> {
                pending?.let(out::add)
                pending = null
            }
            "assistant" -> pending = msg.id
        }
    }
    if (!running) pending?.let(out::add)
    return out
}

/** 智能格式化工具调用聚合摘要（如 "Read (3)"，"Read, bash (4)"，或回退到工具调用计数）。 */
fun formatToolGroupSummary(
    items: List<MobileMessage>,
    fallbackCountText: (Int) -> String = { "$it tool calls" },
): String {
    val toolCalls = items.filter { it.role == "tool_call" }
    val count = if (toolCalls.isNotEmpty()) toolCalls.size else items.size
    val names = items.mapNotNull { it.toolName?.takeIf { n -> n.isNotBlank() } }.distinct()
    return when {
        names.isEmpty() -> fallbackCountText(count)
        names.size == 1 -> {
            val name = names.first()
            if (count > 1) "$name ($count)" else name
        }
        names.size <= 2 -> "${names.joinToString(", ")} ($count)"
        else -> "${names.take(2).joinToString(", ")} +${names.size - 2} ($count)"
    }
}

/**
 * 过程折叠行的标题（2026-09-28 重设计 · 方案 5.2）。
 *
 * 一轮里的工具调用不再逐条铺开，收成一行：
 * - 已结束：「已完成工作 · Read (2)」；
 * - 执行中：「◌ go test ./...」——命令来自插件下发的 activity；拿不到就留空，由组头右侧的
 *   「执行中」动效标签承担说明。
 *
 * 摘要本身仍由 [formatToolGroupSummary] 生成，这里只负责「这一行以什么口吻开头」。
 */
fun toolGroupRowLabel(
    items: List<MobileMessage>,
    running: Boolean,
    runningCommand: String? = null,
    donePrefix: String = "已完成工作",
    runningPrefix: String = "正在运行",
): String {
    // 稿 03 的过程折叠行写的是「完成了什么」而不是「调了几次工具」：能数出改动文件就写文件数。
    if (!running) {
        val edited = editedFileCount(items)
        if (edited != null) return "$donePrefix · ${dev.deeplinks.native.ChangesL.editedFiles.format(edited)}"
    }
    if (running) {
        // 只给命令本身：组头右侧本来就有「执行中」的动效标签，再写一遍「正在运行」会重复。
        // 拿不到命令（旧插件）时返回空串，那一行只显示「执行中」。
        val command = runningCommand?.trim()?.takeIf { it.isNotEmpty() }
        return command?.let { "◌ $it" } ?: ""
    }
    return "$donePrefix · ${formatToolGroupSummary(items)}"
}

data class UserTurnJump(
    val messageId: String,
    val preview: String,
)

/** 已加载历史里的用户轮次，供长会话跳转（对标 DSH 分页轮次导航）。 */
fun userTurnJumps(messages: List<MobileMessage>, maxPreviewChars: Int = 72): List<UserTurnJump> {
    if (maxPreviewChars <= 0) return emptyList()
    return messages.mapNotNull { msg ->
        if (msg.role != "user") return@mapNotNull null
        val preview = msg.text.lineSequence().firstOrNull()?.trim().orEmpty()
        if (preview.isEmpty()) return@mapNotNull null
        UserTurnJump(
            messageId = msg.id,
            preview = preview.take(maxPreviewChars),
        )
    }
}

/**
 * 会改文件的工具名与它们的路径参数——与插件 `src/produced-files.js` 的 `mutationPath` 对齐。
 * 只在「确实是写操作」时返回路径：`edit` 要求带了 old_str，避免把空参数当成改动。
 */
fun mutationPath(toolName: String?, toolArgs: String?): String? {
    if (toolName == null || toolArgs.isNullOrBlank()) return null
    val args = runCatching { org.json.JSONObject(toolArgs) }.getOrNull() ?: return null
    val path = listOf("file_path", "path", "notebook_path")
        .firstNotNullOfOrNull { key -> args.optString(key).takeIf { it.isNotBlank() } }
        ?: return null
    return when (toolName) {
        "write" -> path.takeIf { args.has("content") }
        "edit" -> path.takeIf { args.optString("old_str").isNotEmpty() }
        "str_replace_editor" -> path.takeIf {
            args.optString("command") in listOf("create", "str_replace", "insert") ||
                args.has("file_text") || args.has("old_str") || args.has("insert_line")
        }
        else -> null
    }
}

/**
 * 一组工具调用里被**改动**的文件数（去重）；一个都没有时返回 null，
 * 让 [toolGroupRowLabel] 退回到「工具名 + 次数」——不为了让文案好看而编一个 0。
 */
fun editedFileCount(items: List<MobileMessage>): Int? = items
    .mapNotNull { mutationPath(it.toolName, it.toolArgs) }
    .distinct()
    .takeIf { it.isNotEmpty() }
    ?.size

/**
 * 对话视图下：把连续 [MessageGroup.ToolGroup] 合并为 [MessageGroup.ToolSummary]。
 * 审批卡（ApprovalCard）等非 ToolGroup item 会打断连续序列。
 */
fun foldToolCalls(groups: List<MessageGroup>, viewMode: String): List<MessageGroup> {
    if (viewMode != "chat") return groups
    val out = mutableListOf<MessageGroup>()
    var batch: MutableList<MessageGroup.ToolGroup> = mutableListOf()
    fun flushBatch() {
        if (batch.isEmpty()) return
        val allItems = batch.flatMap { it.items }
        val calls = allItems.count { it.role == "tool_call" }
        val failedCount = allItems.count { it.role == "tool_result" && it.text.startsWith("error", ignoreCase = true) }
        val durationMs = allItems.mapNotNull { it.durationMs }.takeIf { it.isNotEmpty() }?.sum()
        out += MessageGroup.ToolSummary(
            firstId = allItems.first().id,
            count = if (calls > 0) calls else allItems.size,
            failedCount = failedCount,
            durationMs = durationMs,
            toolNames = allItems.mapNotNull { it.toolName }.distinct(),
            running = allItems.any { it.running == true },
            lastToolName = allItems.lastOrNull { it.toolName != null }?.toolName,
        )
        batch = mutableListOf()
    }
    for (g in groups) {
        if (g is MessageGroup.ToolGroup) {
            batch += g
        } else if (g is MessageGroup.Single && (g.msg.role == "tool_call" || g.msg.role == "tool_result")) {
            // 单条工具消息（groupMessages 不足 2 条不成组）也收进摘要，否则对话视图仍会冒出零散命令卡
            batch += MessageGroup.ToolGroup(listOf(g.msg))
        } else {
            flushBatch()
            out += g
        }
    }
    flushBatch()
    return out
}
