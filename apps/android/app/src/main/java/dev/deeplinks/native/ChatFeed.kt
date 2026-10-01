package dev.deeplinks.native

import dev.deeplinks.native.DshIconSize
import dev.deeplinks.core.DshType

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width

import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshNotifier
import dev.deeplinks.core.Host
import dev.deeplinks.core.L
import dev.deeplinks.native.MobileMessage
import dev.deeplinks.native.util.MessageGroup
import dev.deeplinks.native.util.turnEndAssistantIds
import dev.deeplinks.native.util.copiedNeedsAppToast
import dev.deeplinks.native.util.goalRoundObjective
import dev.deeplinks.native.util.foldToolCalls
import dev.deeplinks.native.util.MarkdownSplitCache
import dev.deeplinks.native.util.isContextInjectionText
import dev.deeplinks.native.util.isModelChangedNotice
import dev.deeplinks.native.isTurnEnd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 从消息列表提取最上方（最新）的 goal 摘要文本。 */
internal fun latestGoalSummary(messages: List<MobileMessage>): String? {
    // 优先取显式 role=goal 消息
    for (msg in messages.asReversed()) {
        if (msg.role == "goal" && msg.goalSummary != null) return msg.goalSummary
    }
    // 降级：从最新一条 goal_round 注入文本中提取 objective
    for (msg in messages.asReversed()) {
        if ((msg.role == "assistant" || msg.role == "user")) {
            val obj = goalRoundObjective(msg.text)
            if (obj != null) return obj
        }
    }
    return null
}

/** todo 进度统计。 */
internal data class TodoProgress(
    val pending: Int = 0,
    val inProgress: Int = 0,
    val done: Int = 0,
    val total: Int = 0,
) {
    val hasActive: Boolean get() = pending > 0 || inProgress > 0
}

/** 吸顶摘要条：执行中的目标 + todo 进度，悬浮在顶部 chrome 里，只出现这一处。 */
@Composable
internal fun ChatStickySummary(
    goalOverride: String?,
    messages: List<MobileMessage>,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
    /** 插件给了结构化目标（含 CAS 引用）时显示可操作的目标行，未执行（如已暂停）也常驻。 */
    goal: SessionGoal? = null,
    control: SessionControlController? = null,
) {
    val derivedGoal = remember(messages) { latestGoalSummary(messages) }
    val todos = remember(messages) { latestTodoProgress(messages) }
    StickyTaskSummaryCard(
        goalSummary = goalOverride?.takeIf { it.isNotBlank() } ?: derivedGoal,
        todoProgress = todos,
        isRunning = isRunning,
        modifier = modifier,
        goal = goal?.takeIf { it.manageable && control != null },
        control = control,
    )
}

/** 工具折叠摘要行（对话视图下一批工具调用的收拢展示）：点按跳到轨迹看明细。 */
@Composable
private fun ToolSummaryCard(summary: MessageGroup.ToolSummary, onOpenTrace: () -> Unit) {
    val mainText = if (summary.running && summary.lastToolName != null) {
        L.toolRunning.format(summary.lastToolName)
    } else {
        L.toolSummary.format(summary.count)
    }
    val failedText = summary.failedCount.takeIf { it > 0 }?.let { " · ${L.toolSummaryFailed.format(it)}" }.orEmpty()
    val durationText = summary.durationMs?.takeIf { it > 0 && !summary.running }?.let { " · ${formatTraceDuration(it)}" }.orEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DshTouch.min)
            .clip(RoundedCornerShape(DshRadius.control))
            .clickable(role = Role.Button, onClickLabel = L.viewInTrace, onClick = onOpenTrace)
            .padding(horizontal = DshSpace.s12, vertical = DshSpace.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            DocumentCheckOutline16,
            contentDescription = null,
            tint = Dsh.labelTertiary,
            modifier = Modifier.size(DshIconSize.sm),
        )
        Spacer(Modifier.width(DshSpace.s8))
        Text(
            text = mainText + failedText + durationText,
            color = Dsh.labelSecondary,
            style = DshType.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(DshSpace.s8))
        Text(
            text = L.viewInTrace + " ›",
            color = Dsh.labelTertiary,
            style = DshType.caption,
            maxLines = 1,
        )
    }
}

internal fun latestTodoProgress(messages: List<MobileMessage>): TodoProgress {
    var pending = 0
    var inProgress = 0
    var done = 0
    var latestTodoMsg: MobileMessage? = null
    for (msg in messages.asReversed()) {
        if (msg.role == "todo") { latestTodoMsg = msg; break }
    }
    if (latestTodoMsg != null) {
        for (t in latestTodoMsg.todos) {
            when (t.status) {
                "pending", "todo" -> pending++
                "in_progress", "inprogress", "running" -> inProgress++
                "done", "completed", "complete" -> done++
            }
        }
    }
    return TodoProgress(pending, inProgress, done, pending + inProgress + done)
}

/**
 * 粘性任务摘要卡片：在消息列表顶端粘住，始终可见（不随消息滚出视野）。
 * 生产打磨参考：hermes-mobile #943（TaskProgressChip 模式）、AgenticX StickyTaskBar。
 *
 * 显示内容：
 * - 活动目标（goal_round objective 或显式 goal 消息）
 * - todo 进度（pending / in_progress / done 计数条）
 *
 * 条件：仅在 isRunning == true 且存在可显示内容时出现。
 */
@Composable
internal fun StickyTaskSummaryCard(
    goalSummary: String?,
    todoProgress: TodoProgress,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
    goal: SessionGoal? = null,
    control: SessionControlController? = null,
) {
    val managedGoal = goal?.takeIf { control != null }
    if (!isRunning && managedGoal == null) return
    val hasGoal = !goalSummary.isNullOrBlank()
    val hasTodos = isRunning && todoProgress.hasActive
    if (!hasGoal && !hasTodos && managedGoal == null) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpace.s12, vertical = DshSpace.s4)
            .clip(RoundedCornerShape(DshRadius.container))
            .background(Dsh.bgInput.copy(alpha = 0.92f))
            .padding(horizontal = DshSpace.s12, vertical = DshSpace.s8),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DshSpace.s4)) {
            // 目标行：结构化目标可操作（暂停 / 继续 / 编辑 / 清除），否则只读摘要
            if (managedGoal != null && control != null) {
                GoalControlRow(managedGoal, control)
            } else if (hasGoal) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DshSpace.s6),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        GoalOutline16,
                        contentDescription = null,
                        tint = Dsh.labelSecondary,
                        modifier = Modifier.size(DshIconSize.sm),
                    )
                    Text(
                        text = L.goalRole,
                        color = Dsh.labelSecondary,
                        style = DshType.microMedium,
                    )
                    Text(
                        text = goalSummary.take(60) + if (goalSummary.length > 60) "…" else "",
                        color = Dsh.labelPrimary,
                        style = DshType.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            // todo 进度条
            if (hasTodos) {
                val total = todoProgress.total
                if (total > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DshSpace.s6),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "${todoProgress.done}/$total",
                            color = Dsh.labelTertiary,
                            style = DshType.microRelaxed,
                            maxLines = 1,
                        )
                        val segments = buildList {
                            repeat(todoProgress.done) { add(Dsh.success) }
                            repeat(todoProgress.inProgress) { add(Dsh.labelSecondary) }
                            repeat(todoProgress.pending) { add(Dsh.labelTertiary) }
                        }
                        if (segments.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(DshRadius.full)),
                                horizontalArrangement = Arrangement.spacedBy(1.dp),
                            ) {
                                segments.forEach { color ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(1f / segments.size)
                                            .height(4.dp)
                                            .background(color),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 消息渲染所需的状态与副作用（从 WorkspaceScreen 抽出，COM-001 拆解）。
 *
 * 状态用 getter/setter 注入而不是持有 Compose State，因此实例可稳定复用，
 * 回调在调用时读取最新值。真正的 IO（审批/提问/反馈/重生成）留在类里，
 * 与原先内联在 composable 中的行为一一对应。
 */
internal class ChatFeedActions(
    private val client: MobileApiClient,
    private val scope: CoroutineScope,
    private val context: Context,
    private val host: Host,
    private val currentSessionId: () -> String?,
    private val messages: () -> List<MobileMessage>,
    private val setMessages: (List<MobileMessage>) -> Unit,
    private val olderMessages: () -> List<MobileMessage>,
    private val composerText: () -> String,
    private val setComposerText: (String) -> Unit,
    private val setComposerError: (String?) -> Unit,
    private val isRunning: () -> Boolean,
    private val busyEnter: () -> String,
    private val isFeedbackSupported: () -> Boolean,
    private val feedbackFor: (String) -> Pair<String, String>?,
    private val updateFeedback: (transform: (Map<String, Pair<String, String>>) -> Map<String, Pair<String, String>>) -> Unit,
    private val refreshSessions: () -> Unit,
    private val fork: (String) -> Unit,
    /** 打开改动审查面：轮次 seq + 文件下标（null = 文件列表）。 */
    val openChanges: (Long, Int?) -> Unit = { _, _ -> },
    /** 工具摘要行点按：切到轨迹视图看明细。 */
    val openTrace: () -> Unit = {},
) {
    fun onAnswerApproval(): (String, String, (Boolean) -> Unit) -> Unit = { approvalId, outcome, onDone ->
        val sid = currentSessionId()
        if (sid == null) {
            onDone(false)
        } else {
            scope.launch(Dispatchers.IO) {
                val accepted = try {
                    client.answerApproval(sid, approvalId, outcome)
                } catch (_: Exception) {
                    false
                }
                withContext(Dispatchers.Main) {
                    if (accepted) {
                        setMessages(
                            messages().map { msg ->
                                if (msg.approvalId == approvalId) {
                                    applyRequestState(msg, approvalUiStatus(outcome), outcome)
                                } else msg
                            },
                        )
                        DshNotifier.cancelApproval(context, host, sid)
                    }
                    onDone(accepted)
                }
            }
        }
    }

    fun onAnswerQuestion(): (String, JSONObject, (Boolean) -> Unit) -> Unit = { rpcId, answer, onDone ->
        val sid = currentSessionId()
        if (sid == null) {
            onDone(false)
        } else {
            scope.launch(Dispatchers.IO) {
                val accepted = try {
                    client.answerQuestion(sid, rpcId, answer)
                } catch (_: Exception) {
                    false
                }
                withContext(Dispatchers.Main) { onDone(accepted) }
            }
        }
    }

    fun onCopy(msg: MobileMessage): () -> Unit = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("dsh message", msg.text))
        if (copiedNeedsAppToast(android.os.Build.VERSION.SDK_INT)) {
            Toast.makeText(context, L.copied, Toast.LENGTH_SHORT).show()
        }
    }

    fun onQuote(msg: MobileMessage): () -> Unit = {
        // DSH 引用：把消息首行作为引用注入输入框
        val firstLine = msg.text.lineSequence().firstOrNull().orEmpty().take(120)
        val current = composerText()
        setComposerText(if (current.isBlank()) "> $firstLine\n" else "> $firstLine\n$current")
    }

    fun onFork(): () -> Unit = {
        val sid = currentSessionId()
        if (sid != null) fork(sid)
    }

    fun onRegenerate(msg: MobileMessage): (() -> Unit)? {
        if (msg.role != "assistant") return null
        return {
            val sid = currentSessionId()
            if (sid != null) {
                val all = mergeHistoryPages(olderMessages(), messages())
                val idx = all.indexOfFirst { it.id == msg.id }
                val prevUser = if (idx > 0) {
                    all.subList(0, idx).lastOrNull { it.role == "user" }
                } else {
                    null
                }
                val prompt = prevUser?.text?.takeIf { it.isNotBlank() }
                if (prompt.isNullOrBlank()) {
                    setComposerError(L.cannotFindUserMessageToRegenerate)
                } else {
                    setComposerError(null)
                    scope.launch(Dispatchers.IO) {
                        try {
                            client.sendPrompt(sid, prompt, mode = resolvePromptMode(isRunning(), busyEnter()))
                            withContext(Dispatchers.Main) { refreshSessions() }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                setComposerError(L.regenerateFailed.format(e.message ?: L.unknownError))
                            }
                        }
                    }
                }
            }
        }
    }

    fun onFeedback(msg: MobileMessage): (() -> Unit)? {
        if (msg.role != "assistant") return null
        return { setComposerText(insertFeedbackCommand(composerText())) }
    }

    fun feedbackRating(msg: MobileMessage): String? =
        if (isFeedbackSupported()) feedbackFor(msg.id)?.first else null

    fun onRate(msg: MobileMessage): ((String) -> Unit)? {
        if (msg.role != "assistant" || !isFeedbackSupported()) return null
        return { rating ->
            val sid = currentSessionId()
            if (sid != null) {
                val current = feedbackFor(msg.id)
                scope.launch(Dispatchers.IO) {
                    try {
                        val root = client.putMessageFeedback(sid, msg.id, rating, current?.second)
                        val ver = root.optJSONObject("value")?.optString("version").orEmpty()
                        withContext(Dispatchers.Main) {
                            updateFeedback { it + (msg.id to (rating to ver)) }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    fun onRetract(msg: MobileMessage): (() -> Unit)? {
        if (msg.role != "assistant" || !isFeedbackSupported()) return null
        return {
            val sid = currentSessionId()
            val current = feedbackFor(msg.id)
            if (sid != null && current != null) {
                scope.launch(Dispatchers.IO) {
                    try {
                        client.deleteMessageFeedback(sid, msg.id, current.second)
                        withContext(Dispatchers.Main) {
                            updateFeedback { it - msg.id }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    fun onFetchProducedFile(msg: MobileMessage): ((String) -> Pair<String, ByteArray>)? {
        if (msg.role != "produced_files") return null
        return { path ->
            val sid = currentSessionId() ?: throw IllegalStateException(L.unknownError)
            client.getSessionFile(sid, path)
        }
    }
}

/**
 * 消息列表渲染（从 WorkspaceScreen 的 LazyColumn 抽出，COM-001 拆解）。
 * 纯展示 + 通过 [actions] 触发副作用。
 */
internal fun LazyListScope.chatMessageItems(
    visibleGroups: List<MessageGroup>,
    sweepingId: String?,
    actions: ChatFeedActions,
    isRunning: Boolean = false,
    pinnedChangesSeq: Long? = null,
) {
    val groups = if (pinnedChangesSeq == null) {
        visibleGroups
    } else {
        visibleGroups.filterNot { group ->
            group is MessageGroup.Single &&
                group.msg.role == ROLE_WORKSPACE_CHANGES &&
                group.msg.changes?.seq == pinnedChangesSeq
        }
    }
    val turnEnds = turnEndAssistantIds(groups, isRunning)
    val foldedGroups = foldToolCalls(groups, viewMode = "chat")
    val rows = chatFeedRows(foldedGroups, isRunning)
    items(
        items = rows,
        key = { it.key },
        contentType = { row: ChatFeedRow ->
            when {
                row.group is MessageGroup.ToolGroup -> "toolgroup"
                row.group is MessageGroup.ToolSummary -> "toolsummary"
                row.partCount > 1 -> "assistant-part"
                else -> "single"
            }
        },
    ) { row: ChatFeedRow ->
        val group = row.group
        // 入场只交给 animateItem：AnimatedVisibility(visible = true) 首帧即可见，enter 永远不会播。
        // 仅本机刚收到的消息（entrance）淡入；历史分页、切会话载入的消息直接出现。
        val live = group is MessageGroup.Single && group.msg.entrance
        Box(
            modifier = Modifier.animateItem(
                fadeInSpec = if (live) tween(motionDuration(DshDuration.normal), easing = DshEasing.out) else null,
                fadeOutSpec = null,
            ),
        ) {
            when (group) {
                is MessageGroup.Single -> {
                    MessageItem(
                        msg = group.msg,
                        running = sweepingId != null && group.msg.id == sweepingId,
                        onAnswerApproval = actions.onAnswerApproval(),
                        onAnswerQuestion = actions.onAnswerQuestion(),
                        onCopy = actions.onCopy(group.msg),
                        onQuote = actions.onQuote(group.msg),
                        onFork = actions.onFork(),
                        onRegenerate = actions.onRegenerate(group.msg),
                        onFeedback = actions.onFeedback(group.msg),
                        feedbackRating = actions.feedbackRating(group.msg),
                        onRate = actions.onRate(group.msg),
                        onRetract = actions.onRetract(group.msg),
                        onFetchProducedFile = actions.onFetchProducedFile(group.msg),
                        onOpenChanges = actions.openChanges,
                        showActions = group.msg.id in turnEnds,
                        isTurnEnd = row.isTurnEnd,
                        textPart = row.part,
                        isLastPart = row.partIndex == row.partCount - 1,
                    )
                }
                is MessageGroup.ToolGroup -> ToolGroupHeader(
                    group = group,
                    sweepingId = sweepingId,
                    showActions = false,
                )
                is MessageGroup.ToolSummary -> ToolSummaryCard(summary = group, onOpenTrace = actions.openTrace)
            }
        }
    }
}
/** 消息流里的一行：普通分组原样一行；长助手回复按 Markdown 块拆成多行（各自独立测量）。 */
internal data class ChatFeedRow(
    val group: MessageGroup,
    val key: String,
    val isTurnEnd: Boolean,
    val part: String? = null,
    val partIndex: Int = 0,
    val partCount: Int = 1,
)

private val chatSplitCache = MarkdownSplitCache()

internal fun chatFeedRows(foldedGroups: List<MessageGroup>, isRunning: Boolean): List<ChatFeedRow> {
    val rows = ArrayList<ChatFeedRow>(foldedGroups.size)
    foldedGroups.forEachIndexed { idx, group ->
        val turnEnd = isTurnEnd(foldedGroups, idx, isRunning)
        val msg = (group as? MessageGroup.Single)?.msg
        val parts = if (msg != null && splittableAssistant(msg)) chatSplitCache.parts(msg.id, msg.text) else null
        if (parts == null || parts.size <= 1) {
            rows += ChatFeedRow(group, group.groupKey, turnEnd)
        } else {
            parts.forEachIndexed { i, part ->
                // 第 0 段沿用消息 id 作 key：滚动锚点 / 加载更早恢复仍能按 id 找到它
                val key = if (i == 0) group.groupKey else "${group.groupKey}#p$i"
                rows += ChatFeedRow(group, key, turnEnd, part, i, parts.size)
            }
        }
    }
    return rows
}

private fun splittableAssistant(msg: MobileMessage): Boolean =
    msg.role == "assistant" && !isRawFallback(msg) &&
        !isContextInjectionText(msg.text) && !isModelChangedNotice(msg.text)
