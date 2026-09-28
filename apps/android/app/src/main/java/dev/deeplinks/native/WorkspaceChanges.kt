package dev.deeplinks.native

import dev.deeplinks.native.util.optStringOrEmpty
import org.json.JSONObject

/**
 * 本轮改动文件（插件转发 DSH `workspaceChanges`，契约见 dsh-links `docs/MOBILE_SYNC_CONTRACT.md`）。
 *
 * 数据只在 Host 进程内随 Session 存活：Host 重启后旧轮次没有摘要，也就没有卡片——
 * 这是 DSH 的既定行为，App 不补、不报错。本文件只放模型、解析与纯推导，UI 在
 * [WorkspaceChangesCard] / [WorkspaceChangesPanel]。
 */

internal const val ROLE_WORKSPACE_CHANGES = "workspace_changes"

data class ChangedFile(
    /** 工作目录内为相对路径，否则为 Host 绝对路径（仅作标识，不拼 URL）。 */
    val path: String,
    /** 斜杠分隔的展示路径：相对 / `../` / `~` / 绝对。 */
    val display: String,
    val added: Int = 0,
    val deleted: Int = 0,
    val binary: Boolean = false,
    val oversized: Boolean = false,
) {
    val name: String get() = display.substringAfterLast('/').ifBlank { display }
    val directory: String get() = display.substringBeforeLast('/', "")
}

data class WorkspaceChangesSummary(
    /** `workspace/changes` 事件的 seq：摘要与对比路由的坐标。 */
    val seq: Long,
    val turn: Int,
    /** 完整改动文件数（可能大于 [files]，被上限裁掉的部分需走摘要路由取）。 */
    val total: Int,
    val added: Int,
    val deleted: Int,
    val files: List<ChangedFile>,
) {
    val complete: Boolean get() = files.size >= total
}

data class DiffHunk(
    val oldStart: Int,
    val oldLines: Int,
    val newStart: Int,
    val newLines: Int,
    /** 每行保留 `+` / `-` / 空格前缀。 */
    val lines: List<String>,
)

sealed interface WorkspaceFileDiff {
    val path: String
    val display: String

    data class Text(
        override val path: String,
        override val display: String,
        val before: Boolean,
        val after: Boolean,
        val coarse: Boolean,
        val hunks: List<DiffHunk>,
        val shownLines: Int? = null,
        val totalLines: Int? = null,
    ) : WorkspaceFileDiff {
        val truncated: Boolean get() = shownLines != null && totalLines != null && shownLines < totalLines
    }

    data class Binary(override val path: String, override val display: String) : WorkspaceFileDiff
    data class Oversized(override val path: String, override val display: String) : WorkspaceFileDiff
}

internal fun parseChangedFile(obj: JSONObject): ChangedFile? {
    val path = obj.optStringOrEmpty("path").trim()
    if (path.isEmpty()) return null
    return ChangedFile(
        path = path,
        display = obj.optStringOrEmpty("display").trim().ifEmpty { path },
        added = obj.optInt("added", 0).coerceAtLeast(0),
        deleted = obj.optInt("deleted", 0).coerceAtLeast(0),
        binary = obj.optBoolean("binary", false),
        oversized = obj.optBoolean("oversized", false),
    )
}

/** 解析摘要（历史内嵌的 `changes` 或摘要路由的根对象）；没有文件时返回 null（不出卡片）。 */
internal fun parseWorkspaceChanges(obj: JSONObject, seq: Long): WorkspaceChangesSummary? {
    val arr = obj.optJSONArray("files") ?: return null
    val files = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseChangedFile) }
    if (files.isEmpty()) return null
    return WorkspaceChangesSummary(
        seq = seq,
        turn = obj.optInt("turn", 0),
        total = obj.optInt("total", files.size).coerceAtLeast(files.size),
        added = obj.optInt("added", files.sumOf { it.added }),
        deleted = obj.optInt("deleted", files.sumOf { it.deleted }),
        files = files,
    )
}

internal fun parseWorkspaceFileDiff(obj: JSONObject): WorkspaceFileDiff? {
    val path = obj.optStringOrEmpty("path")
    val display = obj.optStringOrEmpty("display").ifEmpty { path }
    return when (obj.optStringOrEmpty("kind")) {
        "binary" -> WorkspaceFileDiff.Binary(path, display)
        "oversized" -> WorkspaceFileDiff.Oversized(path, display)
        "text" -> {
            val arr = obj.optJSONArray("hunks") ?: org.json.JSONArray()
            val hunks = (0 until arr.length()).mapNotNull { i ->
                val h = arr.optJSONObject(i) ?: return@mapNotNull null
                val lines = h.optJSONArray("lines") ?: org.json.JSONArray()
                DiffHunk(
                    oldStart = h.optInt("oldStart"),
                    oldLines = h.optInt("oldLines"),
                    newStart = h.optInt("newStart"),
                    newLines = h.optInt("newLines"),
                    lines = (0 until lines.length()).map { lines.optString(it) },
                )
            }
            val truncated = obj.optJSONObject("truncated")
            WorkspaceFileDiff.Text(
                path = path,
                display = display,
                before = obj.optBoolean("before", true),
                after = obj.optBoolean("after", true),
                coarse = obj.optBoolean("coarse", false),
                hunks = hunks,
                shownLines = truncated?.optInt("shownLines"),
                totalLines = truncated?.optInt("totalLines"),
            )
        }
        else -> null
    }
}

/** 从 SSE 原始 `workspace/changes` 事件构造的卡片占位（摘要随后由摘要路由补上）。 */
internal fun workspaceChangesMessage(summary: WorkspaceChangesSummary, time: Long, entrance: Boolean = false) =
    MobileMessage(
        id = "changes-${summary.seq}",
        role = ROLE_WORKSPACE_CHANGES,
        text = "",
        time = time,
        type = ROLE_WORKSPACE_CHANGES,
        seq = summary.seq,
        turn = summary.turn,
        changes = summary,
        entrance = entrance,
    )

/**
 * 同一轮的后一条宣告取代前一条（DSH：`The latest event for one turn replaces earlier ones`）。
 * 跨分页 / SSE 与历史合并后按 turn 只保留 seq 最大的一张卡；不含改动卡的列表原样返回。
 */
fun coalesceWorkspaceChanges(messages: List<MobileMessage>): List<MobileMessage> {
    if (messages.none { it.role == ROLE_WORKSPACE_CHANGES }) return messages
    val latestSeqByTurn = HashMap<Int, Long>()
    for (m in messages) {
        if (m.role != ROLE_WORKSPACE_CHANGES) continue
        val turn = m.turn ?: continue
        val seq = m.changes?.seq ?: m.seq
        if (seq >= (latestSeqByTurn[turn] ?: Long.MIN_VALUE)) latestSeqByTurn[turn] = seq
    }
    return messages.filter { m ->
        if (m.role != ROLE_WORKSPACE_CHANGES) return@filter true
        val turn = m.turn ?: return@filter true
        (m.changes?.seq ?: m.seq) == latestSeqByTurn[turn]
    }
}

/** 会话内全部改动卡片摘要，按轮次从新到旧（审查面的轮次切换顺序）。 */
fun sessionChangeSummaries(messages: List<MobileMessage>): List<WorkspaceChangesSummary> =
    coalesceWorkspaceChanges(messages)
        .mapNotNull { if (it.role == ROLE_WORKSPACE_CHANGES) it.changes else null }
        .sortedByDescending { it.seq }

/**
 * 钉在输入框上方的本轮改动：最后一条用户消息之后的改动卡。
 * 用户再发消息，这张卡就回到消息流原位；新一轮没改文件时不钉任何卡。
 * [messages] 为旧→新顺序、已合并去重的历史（[mergeHistoryPages] 的输出）。
 */
fun pinnedTurnChanges(messages: List<MobileMessage>): WorkspaceChangesSummary? {
    val lastUser = messages.indexOfLast { it.role == "user" }
    return messages.drop(lastUser + 1).lastOrNull { it.role == ROLE_WORKSPACE_CHANGES }?.changes
}

/** 渲染行：hunk 头 / 上下文 / 新增 / 删除，带双列行号；[emphasis] 为行内变化片段（见 IntralineDiff.kt）。 */
data class DiffRow(
    val kind: Kind,
    val oldNo: Int?,
    val newNo: Int?,
    val text: String,
    val emphasis: List<IntRange> = emptyList(),
    /** 折叠行专用：被折起来的上下文行数（0 = 普通行）。 */
    val hiddenCount: Int = 0,
) {
    enum class Kind { HUNK, CONTEXT, ADD, DELETE, FOLD }
}

fun diffRows(hunks: List<DiffHunk>): List<DiffRow> = withIntralineEmphasis(plainDiffRows(hunks))

/** 折叠阈值：一段未改动的上下文超过这么多行才折（保留首尾各 [CONTEXT_FOLD_KEEP] 行）。 */
const val CONTEXT_FOLD_MIN = 10
const val CONTEXT_FOLD_KEEP = 3

/**
 * 把过长的未改动上下文折成一行「展开中间 N 行」（2026-09-28 重设计 · 方案 6.3）。
 *
 * [expandedFolds] 放的是「被折叠的上下文片段在**输入 rows 里**的起始下标」（不是输出里的
 * 折叠行下标），由 UI 持有一份；纯函数只负责按它决定这一轮该显示哪些行，便于单测。
 *
 * hunk 头是天然的分隔符：不在 hunk 之间跨行合并，否则会把两段互不相邻的改动连起来。
 */
fun foldContextRows(rows: List<DiffRow>, expandedFolds: Set<Int> = emptySet()): List<DiffRow> {
    val out = mutableListOf<DiffRow>()
    var i = 0
    while (i < rows.size) {
        if (rows[i].kind != DiffRow.Kind.CONTEXT) {
            out += rows[i]
            i++
            continue
        }
        var j = i
        while (j < rows.size && rows[j].kind == DiffRow.Kind.CONTEXT) j++
        val length = j - i
        if (length <= CONTEXT_FOLD_MIN || i in expandedFolds) {
            out += rows.subList(i, j)
        } else {
            out += rows.subList(i, i + CONTEXT_FOLD_KEEP)
            out += DiffRow(
                kind = DiffRow.Kind.FOLD,
                oldNo = null,
                newNo = null,
                text = "",
                hiddenCount = length - CONTEXT_FOLD_KEEP * 2,
            )
            out += rows.subList(j - CONTEXT_FOLD_KEEP, j)
        }
        i = j
    }
    return out
}


private fun plainDiffRows(hunks: List<DiffHunk>): List<DiffRow> = buildList {
    for (hunk in hunks) {
        add(DiffRow(DiffRow.Kind.HUNK, null, null, "@@ -${hunk.oldStart},${hunk.oldLines} +${hunk.newStart},${hunk.newLines} @@"))
        var oldNo = hunk.oldStart
        var newNo = hunk.newStart
        for (line in hunk.lines) {
            val body = if (line.isEmpty()) "" else line.substring(1)
            when (line.firstOrNull()) {
                '+' -> add(DiffRow(DiffRow.Kind.ADD, null, newNo++, body))
                '-' -> add(DiffRow(DiffRow.Kind.DELETE, oldNo++, null, body))
                else -> add(DiffRow(DiffRow.Kind.CONTEXT, oldNo++, newNo++, body))
            }
        }
    }
}

/** 对比说明行：新建 / 删除 / 两侧相同 / 逐行超时 / 截断。 */
enum class DiffNote { CREATED, DELETED, UNCHANGED, COARSE, TRUNCATED }

fun diffNotes(diff: WorkspaceFileDiff.Text): List<DiffNote> = buildList {
    if (!diff.before && diff.after) add(DiffNote.CREATED)
    if (diff.before && !diff.after) add(DiffNote.DELETED)
    if (diff.hunks.isEmpty()) add(DiffNote.UNCHANGED)
    if (diff.coarse) add(DiffNote.COARSE)
    if (diff.truncated) add(DiffNote.TRUNCATED)
}

/** 卡片里默认展开的文件行数（DSH Web：折叠前 4 行）。 */
internal const val CHANGES_CARD_VISIBLE_FILES = 4

/**
 * 审查面宽度（dp）。窄屏（<600dp）全屏——对齐 DSH 桌面「窗口低于 768px 时打开右栏自动全屏」
 * 的手机形态；宽屏首开 45% 容器宽，至少 360dp，且给主区留足 400dp 可读宽度。
 */
fun changesPanelWidthDp(containerDp: Float): Float {
    if (containerDp < 600f) return containerDp
    val preferred = (containerDp * 0.45f).coerceAtLeast(360f)
    return preferred.coerceAtMost(containerDp - 400f).coerceAtLeast(360f).coerceAtMost(containerDp)
}

/** 松手判定：拖过 35% 或向打开方向快速甩出即打开，反向快速甩回即关闭。 */
fun settleChangesPanelOpen(fraction: Float, velocityTowardOpenPxPerSec: Float, flingThresholdPxPerSec: Float): Boolean = when {
    velocityTowardOpenPxPerSec > flingThresholdPxPerSec -> true
    velocityTowardOpenPxPerSec < -flingThresholdPxPerSec -> false
    else -> fraction > 0.35f
}
