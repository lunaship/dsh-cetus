package dev.deeplinks.native

import dev.deeplinks.native.DshIconSize
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import dev.deeplinks.core.DshType
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.L
import java.util.Locale
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import dev.deeplinks.core.traceCalls
import dev.deeplinks.core.traceEditKind
import dev.deeplinks.core.traceErrorKind
import dev.deeplinks.core.traceFilterAll
import dev.deeplinks.core.traceFilterError
import dev.deeplinks.core.traceMinutes
import dev.deeplinks.core.traceRound
import dev.deeplinks.core.traceRounds
import dev.deeplinks.core.traceRunning
import dev.deeplinks.core.traceSeconds
import dev.deeplinks.native.ui.v4.DlChip
import dev.deeplinks.native.ui.v4.DlChipStyle
import dev.deeplinks.native.ui.v4.DlLabelStrong
import dev.deeplinks.native.ui.v4.DlSpinner
import java.text.SimpleDateFormat
import java.util.Date

internal fun formatTraceDuration(ms: Long): String = when {
    ms >= 1000 -> String.format(Locale.US, "+%.1fs", ms / 1000.0)
    else -> "+" + ms + "ms"
}

internal fun traceIsError(msg: MobileMessage): Boolean {
    val outcome = msg.outcome
    if (outcome != null && outcome != "ok" && outcome != "completed" && outcome != "accepted") return true
    return msg.text.contains("\"isError\":true")
}

private data class TraceRoleVisual(
    val label: String,
    val color: Color,
    val icon: ImageVector,
)

/** DSH 注入的 system-reminder / runtime context 在轨迹里单独标为「上下文注入」，不冒充用户/助手。 */
internal const val TRACE_ROLE_CONTEXT_INJECTION = "context_injection"

internal fun traceDisplayRole(msg: MobileMessage): String =
    if (isHiddenContextInjection(msg)) TRACE_ROLE_CONTEXT_INJECTION else msg.role

@Composable
private fun traceRoleVisual(role: String): TraceRoleVisual = when (role) {
    TRACE_ROLE_CONTEXT_INJECTION -> TraceRoleVisual(L.contextInjection, Dsh.labelTertiary, ArchiveOutline20)
    "user" -> TraceRoleVisual(L.traceKindUser, Dsh.brand400, GoalOutline16)
    "reasoning" -> TraceRoleVisual(L.traceKindReasoning, Dsh.traceReasoning, ThinkOutline16)
    "tool_call" -> TraceRoleVisual(L.traceKindTool, Dsh.labelSecondary, CodeOutline16)
    "tool_result" -> TraceRoleVisual(L.traceKindResult, Dsh.labelSecondary, CheckOutline16)
    "approval" -> TraceRoleVisual(L.approvalRole, Dsh.warn, WarningOutline16)
    "todo" -> TraceRoleVisual(L.taskRole, Dsh.labelSecondary, ChecklistOutline16)
    "compaction" -> TraceRoleVisual(L.traceKindCompact, Dsh.labelTertiary, ArchiveOutline20)
    "produced_files" -> TraceRoleVisual(L.traceKindOutput, Dsh.brand400, FileOutline16)
    ROLE_WORKSPACE_CHANGES -> TraceRoleVisual(ChangesL.changes, Dsh.brand400, EditOutline16)
    else -> TraceRoleVisual(L.traceKindAssistant, Dsh.brand400, Sparkle16)
}

/** 一行 = 一次工具调用（+ 紧随其后的结果）或一条独立消息。 */
internal data class TraceRow(
    val key: String,
    val primary: MobileMessage,
    val result: MobileMessage?,
)

internal fun buildTraceRows(steps: List<MobileMessage>): List<TraceRow> {
    val out = mutableListOf<TraceRow>()
    var i = 0
    while (i < steps.size) {
        val m = steps[i]
        if (m.role == "tool_call") {
            val next = steps.getOrNull(i + 1)
            if (next != null && next.role == "tool_result") {
                out.add(TraceRow("row-" + m.id, m, next))
                i += 2
                continue
            }
        }
        out.add(TraceRow("row-" + m.id, m, null))
        i++
    }
    return out
}

internal data class TraceTurn(
    val index: Int,
    val header: MobileMessage?,
    val steps: List<MobileMessage>,
)

/** 只有用户亲手发的消息才开新回合；DSH 注入的上下文（role 可能是 user）归入当前回合。 */
internal fun isTraceTurnStart(msg: MobileMessage): Boolean =
    msg.role == "user" && traceDisplayRole(msg) == "user"

internal fun groupTraceTurns(messages: List<MobileMessage>): List<TraceTurn> {
    if (messages.isEmpty()) return emptyList()
    val turns = mutableListOf<TraceTurn>()
    var current = mutableListOf<MobileMessage>()
    fun flush() {
        if (current.isEmpty()) return
        val header = current.firstOrNull { isTraceTurnStart(it) }
        turns.add(TraceTurn(turns.size + 1, header, current.toList()))
        current = mutableListOf()
    }
    for (m in messages) {
        if (isTraceTurnStart(m) && current.isNotEmpty()) flush()
        current.add(m)
    }
    flush()
    return turns
}

private fun traceDurations(messages: List<MobileMessage>): Map<String, Long?> {
    val out = HashMap<String, Long?>(messages.size)
    messages.forEachIndexed { i, m ->
        val next = messages.getOrNull(i + 1)?.time ?: 0L
        out[m.id] = if (m.time > 0 && next > m.time) next - m.time else null
    }
    return out
}

/** 轨迹页的筛选 chip（v4 4.7）。 */
internal enum class TraceFilter { All, Tool, Thinking, Result, Error }

internal fun traceRowIsError(row: TraceRow): Boolean =
    traceIsError(row.primary) || (row.result?.let { traceIsError(it) } == true)

internal fun traceRowMatches(row: TraceRow, filter: TraceFilter, query: String): Boolean {
    val kindOk = when (filter) {
        TraceFilter.All -> true
        TraceFilter.Tool -> row.primary.role == "tool_call"
        TraceFilter.Thinking -> row.primary.role == "reasoning"
        TraceFilter.Result -> row.primary.role in TRACE_RESULT_ROLES
        TraceFilter.Error -> traceRowIsError(row)
    }
    if (!kindOk) return false
    if (query.isBlank()) return true
    return listOfNotNull(row.primary.text, row.primary.toolName, row.primary.toolArgs, row.result?.text)
        .any { it.contains(query, ignoreCase = true) }
}

private val TRACE_RESULT_ROLES = setOf("assistant", "tool_result", "produced_files", ROLE_WORKSPACE_CHANGES)

/** 顶栏第二行：「12 轮 · 86 次调用 · 14 分钟」。没有时间戳就不写分钟。 */
internal fun traceSummaryLine(messages: List<MobileMessage>): String {
    val rounds = groupTraceTurns(messages).size
    val calls = messages.count { it.role == "tool_call" }
    val times = messages.map { it.time }.filter { it > 0 }
    val minutes = if (times.size >= 2) ((times.max() - times.min()) / 60_000L).toInt() else null
    return listOfNotNull(
        L.traceRounds.format(rounds),
        L.traceCalls.format(calls),
        minutes?.let { L.traceMinutes.format(it) },
    ).joinToString(" · ")
}

/** 工具调用行的标题：命令 / 路径 / 关键词，拿不到就用工具名。 */
internal fun traceToolTitle(msg: MobileMessage): String {
    approvalCommand(msg.toolArgs)?.let { return it }
    val args = msg.toolArgs?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
    val hint = listOf("file_path", "path", "pattern", "query", "url")
        .firstNotNullOfOrNull { key -> args?.optString(key)?.takeIf { it.isNotBlank() } }
    return hint ?: msg.toolName ?: L.toolFallbackName
}

private fun firstLine(text: String): String = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

private fun isEditTool(name: String?): Boolean {
    val n = name?.lowercase() ?: return false
    return "edit" in n || "write" in n || "patch" in n
}

/**
 * v4 4.7 轨迹二级页：搜索 + 筛选 chip + 按轮分组（新的在上）。
 * 每行是图标 + 标题 + 「类别 · 摘要」，点开看参数与结果。
 */
@Composable
internal fun TrajectoryView(
    messages: List<MobileMessage>,
    running: Boolean,
    elapsedSec: Long,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(TraceFilter.All) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    val turns = remember(messages) { groupTraceTurns(messages).map { it to buildTraceRows(it.steps) } }
    val durations = remember(messages) { traceDurations(messages) }
    val errorCount = remember(turns) { turns.sumOf { (_, rows) -> rows.count(::traceRowIsError) } }
    val lastRowKey = turns.lastOrNull()?.second?.lastOrNull()?.key
    val visibleTurns = remember(turns, query, filter) {
        turns.map { (turn, rows) -> turn to rows.filter { traceRowMatches(it, filter, query) } }
            .filter { it.second.isNotEmpty() }
            .asReversed()
    }

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        item(key = "trace-search") { TraceSearchField(value = query, onValueChange = { query = it }) }
        item(key = "trace-filters") { TraceFilterChips(filter, errorCount) { filter = it } }
        when {
            messages.isEmpty() -> item(key = "trace-empty") { TraceEmptyState() }
            visibleTurns.isEmpty() -> item(key = "trace-filter-empty") {
                Text(
                    L.noTraceFilterEmpty,
                    color = Dsh.labelSecondary,
                    style = DshType.body,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DshSpace.s24, vertical = DshSpace.s32),
                )
            }
            else -> {
                if (running) {
                    item(key = "trace-running") {
                        Column(Modifier.padding(horizontal = DshSpace.s16, vertical = DshSpace.s4)) { ThinkingStatusRow(elapsedSec) }
                    }
                }
                visibleTurns.forEach { (turn, rows) ->
                    item(key = "trace-turn-" + turn.index) { TraceRoundHeader(turn) }
                    items(items = rows.asReversed(), key = { it.key }, contentType = { "trace-row" }) { row ->
                        val open = row.key in expanded
                        TraceListRow(
                            row = row,
                            durationMs = durations[row.primary.id],
                            running = running && row.key == lastRowKey && row.result == null,
                            expanded = open,
                            onToggle = { expanded = if (open) expanded - row.key else expanded + row.key },
                        )
                    }
                }
                item(key = "trace-bottom-space") { Spacer(Modifier.height(DshSpace.s24)) }
            }
        }
    }
}

@Composable
private fun TraceFilterChips(selected: TraceFilter, errorCount: Int, onSelect: (TraceFilter) -> Unit) {
    val chips = listOf(
        TraceFilter.All to L.traceFilterAll,
        TraceFilter.Tool to L.traceKindTool,
        TraceFilter.Thinking to L.traceKindReasoning,
        TraceFilter.Result to L.traceKindResult,
        TraceFilter.Error to L.traceFilterError.format(errorCount),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = DshSpace.s16, vertical = DshSpace.s8),
        horizontalArrangement = Arrangement.spacedBy(DshSpace.s8),
    ) {
        chips.forEach { (filter, label) ->
            DlChip(
                label = label,
                onClick = { onSelect(filter) },
                selected = filter == selected,
                style = if (filter == selected) DlChipStyle.Filled else DlChipStyle.Outlined,
            )
        }
    }
}

@Composable
private fun TraceRoundHeader(turn: TraceTurn) {
    val start = turn.steps.firstOrNull { it.time > 0 }?.time
    val time = start?.let { remember(it) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DshSpace.s20, end = DshSpace.s20, top = DshSpace.s20, bottom = DshSpace.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(L.traceRound.format(turn.index), style = DlLabelStrong, color = Dsh.labelSecondary, modifier = Modifier.weight(1f))
        if (time != null) Text(time, style = DshType.supporting, color = Dsh.labelSecondary)
    }
}

@Composable
private fun TraceListRow(
    row: TraceRow,
    durationMs: Long?,
    running: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val primary = row.primary
    val error = traceRowIsError(row)
    val role = traceDisplayRole(primary)
    val visual = traceRoleVisual(role)
    val isTool = primary.role == "tool_call"
    val title = when {
        isTool -> traceToolTitle(primary)
        else -> firstLine(primary.text).ifEmpty { primary.toolName ?: visual.label }
    }
    val kind = when {
        error -> L.traceErrorKind
        isTool && isEditTool(primary.toolName) -> L.traceEditKind
        else -> visual.label
    }
    val detail = when {
        running -> L.traceRunning
        error -> firstLine(row.result?.text ?: primary.text).take(80).ifEmpty { null }
        primary.role == "reasoning" -> (primary.durationMs ?: durationMs)?.let { L.traceSeconds.format((it / 1000).coerceAtLeast(1)) }
        row.result != null -> firstLine(row.result.text).take(80).ifEmpty { null }
        else -> null
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onToggle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DshTouch.min)
                .padding(horizontal = DshSpace.s16, vertical = DshSpace.s8),
            horizontalArrangement = Arrangement.spacedBy(DshSpace.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(DshIconSize.md), contentAlignment = Alignment.Center) {
                Icon(
                    if (error) WarningOutline16 else visual.icon,
                    contentDescription = null,
                    tint = if (error) Dsh.err else Dsh.labelSecondary,
                    modifier = Modifier.size(DshIconSize.sm),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = Dsh.labelPrimary,
                    style = if (isTool) DshType.supporting.copy(fontFamily = FontFamily.Monospace) else DshType.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(kind, detail).joinToString(" · "),
                    color = if (error) Dsh.err else Dsh.labelSecondary,
                    style = DshType.supporting,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (running) DlSpinner()
        }
        if (expanded) {
            Column(Modifier.padding(start = DshSpace.s16 + DshIconSize.md + DshSpace.s12, end = DshSpace.s16, bottom = DshSpace.s12)) {
                TraceRowBody(primary = primary, result = row.result, running = running, expanded = true)
            }
        }
    }
}

@Composable
private fun TraceEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = DshSpace.s32),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Dsh.brand400.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(ThinkOutline16, contentDescription = null, tint = Dsh.brand400, modifier = Modifier.size(DshIconSize.md))
        }
        Spacer(Modifier.height(DshSpace.s16))
        Text(L.noTrace, color = Dsh.labelPrimary, style = DshType.title, fontWeight = FontWeight(500))
        Spacer(Modifier.height(DshSpace.s8))
        Text(
            L.noTraceEmpty,
            color = Dsh.labelTertiary,
            style = DshType.body,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = DshSpace.s24)
        )
    }
}

@Composable
private fun TraceSearchField(value: String, onValueChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpace.s12, vertical = DshSpace.s4)
            .heightIn(min = DshTouch.min)
            .clip(RoundedCornerShape(DshRadius.container))
            .background(Dsh.bgInput)
            .padding(horizontal = DshSpace.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(SearchOutline16, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(DshIconSize.xs))
        Spacer(Modifier.width(DshSpace.s8))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = DshType.body.copy(color = Dsh.labelPrimary),
            cursorBrush = SolidColor(Dsh.brand400),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(L.traceSearchPlaceholder, color = Dsh.labelTertiary, style = DshType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
            }
        )
        if (value.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .widthIn(min = 48.dp)
                    .clip(CircleShape)
                    .clickable { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.size(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(CloseOutline16, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(DshIconSize.xs))
                }
            }
        }
    }
}

@Composable
private fun TraceRowBody(primary: MobileMessage, result: MobileMessage?, running: Boolean, expanded: Boolean) {
    when {
        primary.role == "tool_call" && result != null -> {
            TraceCodeBlock(primary.toolArgs ?: primary.text, running, expanded)
            Spacer(Modifier.height(DshSpace.s4))
            TraceCodeBlock(result.text, running = false, expanded = expanded)
        }
        primary.role == "tool_call" -> TraceCodeBlock(primary.toolArgs ?: primary.text, running, expanded)
        primary.role == "tool_result" -> TraceCodeBlock(primary.text, false, expanded)
        primary.role == "reasoning" -> TraceExpandableText(primary.text, maxLines = 4, forceExpanded = expanded)
        primary.role == "todo" -> {
            val done = primary.todos.count { it.status == "completed" }
            Text(
                if (primary.todos.isNotEmpty()) L.todoUpdate.format(done, primary.todos.size) else L.todoListUpdated,
                color = Dsh.labelSecondary,
                style = DshType.body,
                lineHeight = 20.sp
            )
        }
        primary.role == "compaction" -> Text(
            if (primary.running == true) L.compressing else primary.text.lineSequence().firstOrNull().orEmpty().ifBlank { L.contextCompressed },
            color = Dsh.labelSecondary,
            style = DshType.body,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis
        )
        primary.role == "produced_files" -> Text(
            primary.files.joinToString("\n").ifBlank { primary.text },
            color = Dsh.labelSecondary,
            style = DshType.body,
            lineHeight = 20.sp
        )
        primary.role == ROLE_WORKSPACE_CHANGES -> TraceExpandableText(
            primary.changes?.files.orEmpty().joinToString("\n") { f ->
                f.display + if (f.added > 0 || f.deleted > 0) "  +${f.added} −${f.deleted}" else ""
            },
            maxLines = 6,
            forceExpanded = expanded,
        )
        else -> TraceExpandableText(primary.text, maxLines = 6, forceExpanded = expanded)
    }
}

@Composable
private fun TraceCodeBlock(text: String, running: Boolean, expanded: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.container))
            .background(Dsh.bgCode)
            .padding(horizontal = DshSpace.s12, vertical = DshSpace.s8)
    ) {
        TraceExpandableText(text, maxLines = 5, mono = true, forceExpanded = expanded)
    }
}

@Composable
private fun TraceExpandableText(
    text: String,
    maxLines: Int = 4,
    mono: Boolean = false,
    forceExpanded: Boolean = false,
) {
    var localExpanded by rememberSaveable { mutableStateOf(false) }
    val expanded = forceExpanded || localExpanded
    // 走 DshType 字阶（12/17 mono、13/20 密集正文），不在业务代码里写裸字号
    val style = if (mono) DshType.caption.copy(fontFamily = FontFamily.Monospace) else DshType.body
    Column {
        Text(
            text,
            color = Dsh.labelSecondary,
            style = style,
            maxLines = if (expanded) Int.MAX_VALUE else maxLines,
            overflow = TextOverflow.Ellipsis
        )
        if (!forceExpanded && text.length > (if (mono) 140 else 100)) {
            Text(
                if (expanded) L.collapse else L.expand,
                color = Dsh.brand400,
                style = DshType.microRelaxed,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .padding(top = DshSpace.s4)
                    .clip(RoundedCornerShape(DshRadius.control))
                    .clickable { localExpanded = !localExpanded }
                    .padding(horizontal = DshSpace.s4)
                    .wrapContentHeight(Alignment.CenterVertically)
            )
        }
    }
}
