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
        /** 本批相邻思考的累计时长（2026-10-02 Lody 简化：对话视图思考收进摘要行）。 */
        val thinkingMs: Long? = null,
        /** 分类计数（命令 / 阅读 / 编辑…）：对话与轨迹两个视图共用同一口径。 */
        val activity: ActivityCounts = ActivityCounts(),
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

/**
 * 过程折叠行的标题（2026-09-28 重设计 · 方案 5.2；2026-10-02 Lody 简化 4.3 口径统一）。
 *
 * 一轮里的工具调用收成一行，与对话视图的活动摘要行同一 [activityLine] 口径：
 * - 已结束：「调用了 12 个命令 · 阅读了 38 个文件 · 编辑 4 次」——L7：编辑写次数，
 *   文件数只认改动卡（git diff），不再在这里数「N 个文件」；
 * - 执行中：「◌ go test ./...」——命令来自插件下发的 activity；拿不到就留空，由组头右侧的
 *   「执行中」动效标签承担说明。
 */
fun toolGroupRowLabel(
    items: List<MobileMessage>,
    running: Boolean,
    runningCommand: String? = null,
): String {
    if (running) {
        // 只给命令本身：组头右侧本来就有「执行中」的动效标签，再写一遍「正在运行」会重复。
        // 拿不到命令（旧插件）时返回空串，那一行只显示「执行中」。
        val command = runningCommand?.trim()?.takeIf { it.isNotEmpty() }
        return command?.let { "◌ $it" } ?: ""
    }
    return activityLine(activityCounts(items)).joinToString(" · ")
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
 * 对话视图下：把连续 [MessageGroup.ToolGroup] 合并为 [MessageGroup.ToolSummary]。
 * 审批卡（ApprovalCard）等非 ToolGroup item 会打断连续序列。
 *
 * 2026-10-02 Lody 简化 4.3：同一段内相邻的 reasoning 也收进摘要（只留 thinkingMs，
 * 思考正文在对话视图不再展开；轨迹视图保留逐条展开）。单独一段纯思考（无工具）
 * 也产出摘要行，避免对话视图里冒出独立的思考条。
 */
fun foldToolCalls(groups: List<MessageGroup>, viewMode: String): List<MessageGroup> {
    if (viewMode != "chat") return groups
    val out = mutableListOf<MessageGroup>()
    var batch: MutableList<MessageGroup.ToolGroup> = mutableListOf()
    fun flushBatch() {
        if (batch.isEmpty()) return
        val allItems = batch.flatMap { it.items }
        val calls = allItems.count { it.role == "tool_call" }
        // 计数只认工具消息：reasoning 并进摘要但不冒充工具调用
        val toolMsgCount = allItems.count { it.role == "tool_call" || it.role == "tool_result" }
        val failedCount = allItems.count { it.role == "tool_result" && it.text.startsWith("error", ignoreCase = true) }
        val durationMs = allItems.mapNotNull { it.durationMs }.takeIf { it.isNotEmpty() }?.sum()
        val activity = activityCounts(allItems)
        out += MessageGroup.ToolSummary(
            firstId = allItems.first().id,
            count = if (calls > 0) calls else toolMsgCount,
            failedCount = failedCount,
            durationMs = durationMs,
            toolNames = allItems.mapNotNull { it.toolName }.distinct(),
            running = allItems.any { it.running == true },
            lastToolName = allItems.lastOrNull { it.toolName != null }?.toolName,
            thinkingMs = activity.thinkingMs,
            activity = activity,
        )
        batch = mutableListOf()
    }
    for (g in groups) {
        when {
            g is MessageGroup.ToolGroup -> batch += g
            g is MessageGroup.Single && (g.msg.role == "tool_call" || g.msg.role == "tool_result") ->
                // 单条工具消息（groupMessages 不足 2 条不成组）也收进摘要，否则对话视图仍会冒出零散命令卡
                batch += MessageGroup.ToolGroup(listOf(g.msg))
            g is MessageGroup.Single && g.msg.role == "reasoning" ->
                batch += MessageGroup.ToolGroup(listOf(g.msg))
            else -> {
                flushBatch()
                out += g
            }
        }
    }
    flushBatch()
    return out
}

// ===== 工具活动分类计数（2026-10-02 Lody 简化 4.3）=====
// 分类表参照 dsh-mobile ToolActivitySummary（MIT，已登记 THIRD_PARTY_NOTICES）。
// 两个视图（对话摘要行 / 轨迹组头）共用同一 activityLine 口径，杜绝两个「N 个文件」。

/** 工具大类。 */
enum class ToolKind { Command, Search, Read, Edit, Fetch, Other }

/** 工具名 → 大类；大小写不敏感，未知工具归 Other。 */
fun classifyTool(name: String?): ToolKind {
    val n = name?.trim()?.lowercase() ?: return ToolKind.Other
    return when (n) {
        "bash", "shell", "exec", "exec_command", "run_code", "terminal" -> ToolKind.Command
        "grep", "glob", "search", "ripgrep", "find" -> ToolKind.Search
        "read", "read_file", "readfile", "list", "ls", "list_directory" -> ToolKind.Read
        "write", "write_file", "edit", "edit_file", "apply_patch", "str_replace_editor" -> ToolKind.Edit
        "web_fetch", "webfetch", "fetch" -> ToolKind.Fetch
        else -> ToolKind.Other
    }
}

/** 一段工具活动的分类计数。 */
data class ActivityCounts(
    val command: Int = 0,
    val search: Int = 0,
    /** 阅读按 path 去重计文件数；参数解析不出 path 时按调用次数计。 */
    val read: Int = 0,
    /** 编辑按调用次数计（文件数只认改动卡，L7）。 */
    val edit: Int = 0,
    val fetch: Int = 0,
    val other: Int = 0,
    /** 相邻 reasoning 的累计思考时长；没有思考时为 null。 */
    val thinkingMs: Long? = null,
)

/** 阅读类工具的路径参数（与插件 produced-files.js 的 mutation 路径键同一组）。 */
private fun readPath(toolArgs: String?): String? {
    if (toolArgs.isNullOrBlank()) return null
    val args = runCatching { org.json.JSONObject(toolArgs) }.getOrNull() ?: return null
    return listOf("file_path", "path", "notebook_path")
        .firstNotNullOfOrNull { key -> args.optString(key).takeIf { it.isNotBlank() } }
}

/** 统计一段消息里的工具活动（tool_call 分类计数 + reasoning 思考时长）。 */
fun activityCounts(items: List<MobileMessage>): ActivityCounts {
    var command = 0
    var search = 0
    var edit = 0
    var fetch = 0
    var other = 0
    var thinkingMs = 0L
    var hasThinking = false
    val readPaths = mutableSetOf<String>()
    var readWithoutPath = 0
    for (msg in items) {
        when (msg.role) {
            "tool_call" -> when (classifyTool(msg.toolName)) {
                ToolKind.Command -> command++
                ToolKind.Search -> search++
                ToolKind.Read -> {
                    val path = readPath(msg.toolArgs)
                    if (path != null) readPaths += path else readWithoutPath++
                }
                ToolKind.Edit -> edit++
                ToolKind.Fetch -> fetch++
                ToolKind.Other -> other++
            }
            "reasoning" -> {
                hasThinking = true
                thinkingMs += msg.durationMs ?: 0L
            }
        }
    }
    return ActivityCounts(
        command = command,
        search = search,
        read = readPaths.size + readWithoutPath,
        edit = edit,
        fetch = fetch,
        other = other,
        thinkingMs = if (hasThinking) thinkingMs else null,
    )
}

/**
 * 活动摘要文案片段（UI 用「 · 」拼接）。
 * 顺序：思考时长 → 命令 → 阅读 → 编辑 → 搜索 → 获取 → 其他；最多 [maxKinds] 类，
 * 其余并为「+N」（并进最后一个展示片段，例：「阅读了 38 个文件 +2」）。
 * 全部为 Other 时自然退回「调用了 N 个工具」。
 */
fun activityLine(c: ActivityCounts, maxKinds: Int = 3): List<String> {
    val s = dev.deeplinks.core.L
    val fragments = buildList {
        if (c.thinkingMs != null && c.thinkingMs > 0) {
            add(s.activityThinking.format(thinkingSecondsLabel(c.thinkingMs)))
        }
        if (c.command > 0) add(s.activityCommands.format(c.command))
        if (c.read > 0) add(s.activityReads.format(c.read))
        if (c.edit > 0) add(s.activityEdits.format(c.edit))
        if (c.search > 0) add(s.activitySearches.format(c.search))
        if (c.fetch > 0) add(s.activityFetches.format(c.fetch))
        if (c.other > 0) add(s.activityTools.format(c.other))
    }
    val limit = maxOf(1, maxKinds)
    if (fragments.size <= limit) return fragments
    val overflow = fragments.size - limit
    return fragments.take(limit)
        .mapIndexed { i, f -> if (i == limit - 1) "$f ${s.activityMore.format(overflow)}" else f }
}

/** 思考时长的秒数文案：<1s 也按 1 秒起算（不写 0 秒）。 */
private fun thinkingSecondsLabel(ms: Long): String {
    val seconds = (ms / 1000L).coerceAtLeast(1)
    return dev.deeplinks.core.L.secondsShort.format(seconds)
}
