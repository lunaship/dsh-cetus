package dev.deeplinks.native

import dev.deeplinks.native.DshIconSize
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.core.dshRipple
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 本轮改动审查面（对照 Paseo 移动端的 explorer 面板 + DSH 桌面右栏 changes review tab）。
 *
 * 交互：对话区内容中段左滑跟手拉出、右滑收回；返回键先从文件对比退回文件列表，再关面板
 * （预测性返回跟手）；顶栏改动入口与轮末卡片是显式入口，手势不是唯一入口。
 * 起手点落在系统手势区（左右边缘返回手势）的触摸一律让给系统；内部横向滚动（代码块、
 * 不换行的对比）先消费，面板手势只接没人要的横滑。
 */
@Stable
internal class ChangesPanelState(initialProgress: Float = 0f) {
    /** 0 = 收起，1 = 完全展开；拖动时跟手，松手后动画到端点。 */
    val progress = Animatable(initialProgress)

    /** 正在看的轮次（`workspace/changes` 的 seq）；null 表示最新一轮。 */
    var seq by mutableStateOf<Long?>(null)

    /** 正在看的文件下标；null 表示文件列表。 */
    var fileIndex by mutableStateOf<Int?>(null)
    var wrap by mutableStateOf(true)
    internal var animationMs = DshDuration.slow
    internal val fullSummaries = mutableStateMapOf<Long, WorkspaceChangesSummary>()

    // 对比单份可达上百万字符，只留最近几份
    private val diffCache = object : LinkedHashMap<String, WorkspaceFileDiff>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WorkspaceFileDiff>?) = size > 6
    }

    val visible: Boolean get() = progress.value > 0f || progress.targetValue > 0f
    val opened: Boolean get() = progress.targetValue >= 1f

    suspend fun open(seq: Long? = null, fileIndex: Int? = null) {
        this.seq = seq
        this.fileIndex = fileIndex
        settle(true)
    }

    suspend fun settle(open: Boolean) {
        progress.animateTo(if (open) 1f else 0f, tween(animationMs, easing = DshEasing.out))
        if (!open) fileIndex = null
    }

    suspend fun reset() {
        progress.snapTo(0f)
        seq = null
        fileIndex = null
        fullSummaries.clear()
        diffCache.clear()
    }

    internal fun cachedDiff(seq: Long, index: Int): WorkspaceFileDiff? = diffCache["$seq:$index"]
    internal fun cacheDiff(seq: Long, index: Int, diff: WorkspaceFileDiff) {
        diffCache["$seq:$index"] = diff
    }
}

/** SSE 收到 `workspace/changes`：按事件 seq 拉摘要，拼成卡片交给 [deliver]；取不到就不出卡片。 */
internal fun CoroutineScope.fetchLiveWorkspaceChanges(
    client: MobileApiClient,
    sessionId: String?,
    seq: Long,
    time: Long,
    isCurrent: (String) -> Boolean,
    deliver: (MobileMessage) -> Unit,
) {
    val sid = sessionId ?: return
    launch(Dispatchers.IO) {
        val summary = runCatching { client.getWorkspaceChanges(sid, seq) }.getOrNull() ?: return@launch
        withContext(Dispatchers.Main) {
            if (isCurrent(sid)) deliver(workspaceChangesMessage(summary, time))
        }
    }
}

/**
 * 对话区容器：在 [content] 之上识别「中段左滑」拉出审查面。
 * [enabled] 为 false（本会话没有改动）时完全不拦截触摸。
 */
@Composable
internal fun ChangesSwipeArea(
    state: ChangesPanelState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val windowWidth = LocalWindowInfo.current.containerSize.width.toFloat()
    val gestures = WindowInsets.systemGestures
    val edgeLeft by rememberUpdatedState(gestures.getLeft(density, layoutDirection).toFloat())
    val edgeRight by rememberUpdatedState(gestures.getRight(density, layoutDirection).toFloat())
    val panelWidthPx = with(density) { changesPanelWidthDp(windowWidth / density.density).dp.toPx() }
    var originX by remember { mutableStateOf(0f) }
    Box(
        modifier = modifier
            .onGloballyPositioned { originX = it.positionInWindow().x }
            .pointerInput(enabled, panelWidthPx) {
                if (!enabled) return@pointerInput
                detectPanelSwipe(
                    state = state,
                    scope = scope,
                    panelWidthPx = panelWidthPx,
                    opening = true,
                    inSystemEdge = { x -> val wx = originX + x; wx < edgeLeft || wx > windowWidth - edgeRight },
                )
            },
        content = content,
    )
}

/**
 * 横滑识别（打开：向左；关闭：向右）。与 M3 抽屉同一 touchSlop：先纵向越过 slop 的交给列表，
 * 反方向越过 slop 的交给抽屉 / 放弃；本方向越过 slop 后消费事件，抽屉在同一事件上看到已消费而放弃。
 */
private suspend fun PointerInputScope.detectPanelSwipe(
    state: ChangesPanelState,
    scope: CoroutineScope,
    panelWidthPx: Float,
    opening: Boolean,
    inSystemEdge: (Float) -> Boolean,
) {
    val sign = if (opening) -1f else 1f
    val flingPx = 600.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (opening == state.opened || inSystemEdge(down.position.x)) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
        var dx = 0f
        var dy = 0f
        var dragging = false
        var fraction = if (opening) 0f else 1f
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (!dragging && change.isConsumed) return@awaitEachGesture
            val delta = change.positionChange()
            dx += delta.x
            dy += delta.y
            tracker.addPosition(change.uptimeMillis, change.position)
            if (!dragging) {
                val along = dx * sign
                if (abs(dy) > slop && abs(dy) > abs(dx)) return@awaitEachGesture
                if (along < -slop) return@awaitEachGesture
                if (along <= slop) continue
                dragging = true
                if (opening) {
                    state.seq = null
                    state.fileIndex = null
                }
            }
            change.consume()
            val moved = (dx * sign / panelWidthPx).coerceIn(0f, 1f)
            fraction = if (opening) moved else 1f - moved
            val target = fraction
            scope.launch { state.progress.snapTo(target) }
        }
        if (dragging) {
            val towardOpen = -tracker.calculateVelocity().x
            val open = settleChangesPanelOpen(fraction, towardOpen, flingPx)
            scope.launch { state.settle(open) }
        }
    }
}

private sealed interface DiffLoad {
    data object Loading : DiffLoad
    data class Ready(val diff: WorkspaceFileDiff) : DiffLoad
    data class Failed(val message: String) : DiffLoad
}

/**
 * 审查面本体：叠在工作区根容器之上。窄屏全屏；宽屏贴右、左侧留遮罩（点按关闭）。
 * [summaries] 按轮次从新到旧；内嵌摘要被上限裁掉时用 [loadSummary] 补全列表。
 */
@Composable
internal fun WorkspaceChangesPanel(
    state: ChangesPanelState,
    summaries: List<WorkspaceChangesSummary>,
    loadSummary: suspend (Long) -> WorkspaceChangesSummary?,
    loadDiff: suspend (Long, Int) -> WorkspaceFileDiff,
    /** 稿 04 的底部提问条：带这个文件的上下文回到对话页输入框（null 时不画那一条）。 */
    onAskAboutFile: ((ChangedFile) -> Unit)? = null,
) {
    state.animationMs = motionDuration(DshDuration.slow)
    val scope = rememberCoroutineScope()
    BackHandler(enabled = state.opened && state.fileIndex != null) { state.fileIndex = null }
    PredictiveBackHandler(enabled = state.opened && state.fileIndex == null) { events ->
        try {
            events.collect { e -> state.progress.snapTo(1f - e.progress * 0.3f) }
            state.settle(false)
        } catch (e: CancellationException) {
            scope.launch { state.settle(true) }
            throw e
        }
    }
    if (!state.visible) return

    val base = summaries.firstOrNull { it.seq == state.seq } ?: summaries.firstOrNull()
    val current = base?.let { state.fullSummaries[it.seq] ?: it }
    LaunchedEffect(base?.seq) {
        if (base != null && !base.complete && state.fullSummaries[base.seq] == null) {
            loadSummary(base.seq)?.let { state.fullSummaries[base.seq] = it }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthDp = changesPanelWidthDp(maxWidth.value)
        val fullScreen = widthDp >= maxWidth.value
        val widthPx = with(LocalDensity.current) { widthDp.dp.toPx() }
        if (!fullScreen) {
            val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = state.progress.value }
                    .background(scrim)
                    .clickable(interactionSource = null, indication = null) { scope.launch { state.settle(false) } },
            )
        }
        val hairline = Dsh.borderSubtle
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(widthDp.dp)
                .fillMaxHeight()
                .graphicsLayer { translationX = (1f - state.progress.value) * widthPx }
                .background(Dsh.bgBase)
                .drawWithContent {
                    drawContent()
                    if (!fullScreen) drawLine(hairline, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx())
                }
                .pointerInput(widthPx) {
                    detectPanelSwipe(state, scope, widthPx, opening = false, inSystemEdge = { false })
                }
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            val index = state.fileIndex
            val file = index?.let { current?.files?.getOrNull(it) }
            if (current == null) {
                PanelHeader(title = ChangesL.changes, onClose = { scope.launch { state.settle(false) } })
                Box(Modifier.fillMaxSize().padding(DshSpace.s24), contentAlignment = Alignment.Center) {
                    Text(ChangesL.empty, color = Dsh.labelTertiary, style = DshType.body)
                }
            } else if (file == null) {
                TurnHeader(
                    summary = current,
                    summaries = summaries,
                    onSelectTurn = { state.seq = it },
                    onClose = { scope.launch { state.settle(false) } },
                )
                FileList(current, onOpenFile = { state.fileIndex = it })
            } else {
                FileHeader(
                    file = file,
                    index = index,
                    count = current.files.size,
                    wrap = state.wrap,
                    onToggleWrap = { state.wrap = !state.wrap },
                    onSelectFile = { state.fileIndex = it },
                    onBack = { state.fileIndex = null },
                )
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f)) { FileDiffBody(state, current.seq, index, file, loadDiff) }
                    if (onAskAboutFile != null) {
                        AskAboutFileBar(file = file, onClick = { onAskAboutFile(file) })
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelIconButton(icon: ImageVector, description: String, onClick: () -> Unit, tint: androidx.compose.ui.graphics.Color = Dsh.labelSecondary) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(interactionSource = null, indication = dshRipple(), onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(DshIconSize.sm))
    }
}

@Composable
private fun PanelHeader(title: String, onClose: () -> Unit, subtitle: String? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = DshSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PanelIconButton(CloseOutline16, L.close, onClose)
        Column(modifier = Modifier.weight(1f).padding(horizontal = DshSpace.s4)) {
            Text(title, color = Dsh.labelPrimary, style = DshType.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, color = Dsh.labelTertiary, style = DshType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
    }
}

@Composable
private fun TurnHeader(
    summary: WorkspaceChangesSummary,
    summaries: List<WorkspaceChangesSummary>,
    onSelectTurn: (Long) -> Unit,
    onClose: () -> Unit,
) {
    // summaries 从新到旧：下标越大越旧
    val position = summaries.indexOfFirst { it.seq == summary.seq }
    PanelHeader(
        title = ChangesL.cardTitle(summary),
        subtitle = ChangesL.turn.format(summary.turn),
        onClose = onClose,
    ) {
        DiffStat(summary.added, summary.deleted)
        if (summaries.size > 1) {
            Spacer(Modifier.width(DshSpace.s4))
            val older = summaries.getOrNull(position + 1)
            val newer = if (position > 0) summaries[position - 1] else null
            PanelIconButton(
                ChevronLeftOutline16,
                ChangesL.olderTurn,
                onClick = { older?.let { onSelectTurn(it.seq) } },
                tint = if (older != null) Dsh.labelSecondary else Dsh.labelDimmed,
            )
            PanelIconButton(
                ChevronRightOutline16,
                ChangesL.newerTurn,
                onClick = { newer?.let { onSelectTurn(it.seq) } },
                tint = if (newer != null) Dsh.labelSecondary else Dsh.labelDimmed,
            )
        } else {
            Spacer(Modifier.width(DshSpace.s12))
        }
    }
}

@Composable
private fun FileList(summary: WorkspaceChangesSummary, onOpenFile: (Int) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(summary.files, key = { i, f -> "$i:${f.path}" }) { index, file ->
            ChangedFileRow(file = file, onClick = { onOpenFile(index) }, startPadding = 16.dp)
        }
        if (!summary.complete) {
            item(key = "partial") {
                Text(
                    ChangesL.moreFiles.format(summary.total - summary.files.size),
                    color = Dsh.labelTertiary,
                    style = DshType.caption,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpace.s16, vertical = DshSpace.s12),
                )
            }
        }
    }
}

@Composable
private fun FileHeader(
    file: ChangedFile,
    index: Int,
    count: Int,
    wrap: Boolean,
    onToggleWrap: () -> Unit,
    onSelectFile: (Int) -> Unit,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = DshSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PanelIconButton(ChevronLeftOutline16, ChangesL.backToFiles, onBack)
        Column(modifier = Modifier.weight(1f).padding(horizontal = DshSpace.s4)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    file.name,
                    color = Dsh.labelPrimary,
                    style = DshType.bodyStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // 稿 04 的文件条：‹ 文件名 1 / N ›——知道「第几个 / 共几个」才敢用左右滑
                Spacer(Modifier.width(DshSpace.s6))
                Text(
                    "${index + 1} / $count",
                    color = Dsh.labelTertiary,
                    style = DshType.caption,
                    maxLines = 1,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (file.directory.isNotEmpty()) {
                    Text(
                        file.directory,
                        color = Dsh.labelTertiary,
                        style = DshType.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(DshSpace.s6))
                }
                DiffStat(file.added, file.deleted)
            }
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (wrap) Dsh.brandTint else Dsh.bgBase)
                .clickable(interactionSource = null, indication = dshRipple(), onClick = onToggleWrap)
                .semantics {
                    role = Role.Switch
                    contentDescription = ChangesL.wrapLines
                    stateDescription = if (wrap) "on" else "off"
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                WrapOutline16,
                contentDescription = null,
                tint = if (wrap) Dsh.brand500 else Dsh.labelSecondary,
                modifier = Modifier.size(DshIconSize.sm),
            )
        }
        if (count > 1) {
            PanelIconButton(
                ChevronUpOutline16,
                ChangesL.previousFile,
                onClick = { if (index > 0) onSelectFile(index - 1) },
                tint = if (index > 0) Dsh.labelSecondary else Dsh.labelDimmed,
            )
            PanelIconButton(
                ChevronDownOutline16,
                ChangesL.nextFile,
                onClick = { if (index < count - 1) onSelectFile(index + 1) },
                tint = if (index < count - 1) Dsh.labelSecondary else Dsh.labelDimmed,
            )
        }
    }
}

@Composable
private fun FileDiffBody(
    state: ChangesPanelState,
    seq: Long,
    index: Int,
    file: ChangedFile,
    loadDiff: suspend (Long, Int) -> WorkspaceFileDiff,
) {
    // 摘要已标明二进制 / 过大：Host 也不会给出行，免一次往返
    if (file.binary || file.oversized) {
        DiffNoteRow(if (file.binary) ChangesL.binary else ChangesL.oversized)
        return
    }
    var attempt by remember(seq, index) { mutableIntStateOf(0) }
    val load by produceState<DiffLoad>(state.cachedDiff(seq, index)?.let(DiffLoad::Ready) ?: DiffLoad.Loading, seq, index, attempt) {
        val cached = state.cachedDiff(seq, index)
        if (cached != null) {
            value = DiffLoad.Ready(cached)
            return@produceState
        }
        value = DiffLoad.Loading
        value = try {
            val diff = withContext(Dispatchers.IO) { loadDiff(seq, index) }
            state.cacheDiff(seq, index, diff)
            DiffLoad.Ready(diff)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiffLoad.Failed(e.message.orEmpty())
        }
    }
    when (val current = load) {
        DiffLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Dsh.brand400)
        }
        is DiffLoad.Failed -> Column(
            modifier = Modifier.fillMaxSize().padding(DshSpace.s24),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(ChangesL.loadFailed, color = Dsh.labelPrimary, style = DshType.body)
            if (current.message.isNotBlank()) {
                Text(current.message, color = Dsh.labelTertiary, style = DshType.caption, modifier = Modifier.padding(top = DshSpace.s4))
            }
            Text(
                L.retry,
                color = Dsh.brand400,
                style = DshType.title,
                modifier = Modifier
                    .padding(top = DshSpace.s12)
                    .heightIn(min = DshTouch.min)
                    .clip(RoundedCornerShape(DshRadius.full))
                    .clickable(interactionSource = null, indication = dshRipple()) { attempt++ }
                    .padding(horizontal = DshSpace.s16, vertical = DshSpace.s12),
            )
        }
        is DiffLoad.Ready -> when (val diff = current.diff) {
            is WorkspaceFileDiff.Binary -> DiffNoteRow(ChangesL.binary)
            is WorkspaceFileDiff.Oversized -> DiffNoteRow(ChangesL.oversized)
            is WorkspaceFileDiff.Text -> DiffLines(diff, state.wrap)
        }
    }
}

@Composable
private fun DiffNoteRow(text: String) {
    Text(
        text,
        color = Dsh.labelSecondary,
        style = DshType.caption,
        modifier = Modifier
            .fillMaxWidth()
            .background(Dsh.bgTrack)
            .padding(horizontal = DshSpace.s16, vertical = 10.dp),
    )
}

@Composable
private fun DiffLines(diff: WorkspaceFileDiff.Text, wrap: Boolean) {
    val allRows = remember(diff) { diffRows(diff.hunks) }
    // 已展开的折叠段（存「被折叠片段在 allRows 里的起始下标」）；切换文件时随 diff 重置
    var expandedFolds by remember(diff) { mutableStateOf(emptySet<Int>()) }
    val rows = remember(allRows, expandedFolds) { foldContextRows(allRows, expandedFolds) }
    val notes = remember(diff) { diffNotes(diff) }
    val digits = remember(rows) {
        rows.maxOfOrNull { maxOf(it.oldNo ?: 0, it.newNo ?: 0) }?.toString()?.length?.coerceAtLeast(2) ?: 2
    }
    val codeStyle = DshType.caption.copy(fontFamily = FontFamily.Monospace)
    val measurer = rememberTextMeasurer()
    val charWidthPx = remember(codeStyle) { measurer.measure("0", codeStyle).size.width.toFloat() }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Dsh.bgCode)) {
        val density = LocalDensity.current
        val gutterChars = digits * 2 + 4
        val longest = remember(rows) { rows.maxOfOrNull { it.text.length }?.coerceAtMost(4_000) ?: 0 }
        val contentWidth = with(density) {
            maxOf(maxWidth, ((gutterChars + longest) * charWidthPx).toDp() + 24.dp)
        }
        // 自动换行时文本列的真实可用宽度：整宽 −（实测的行号列 + 标记列）− 内边距 − 安全余量。
        // 上一版按「字符数 = 宽度 / 单字宽」估算，偏乐观导致长行被裁；这次改成实测像素宽，
        // 并用 TextMeasurer 按这个宽度拿真实断点（见 DiffLineRow），两者一致才不会裁字。
        val gutterPx = remember(codeStyle, digits) {
            val numbers = "0".repeat(digits) + " "
            measurer.measure(numbers, codeStyle).size.width.toFloat() +
                measurer.measure(" + ", codeStyle).size.width.toFloat()
        }
        val textWidthPx = with(density) {
            (maxWidth.toPx() - gutterPx - 16f - 8f).toInt().coerceAtLeast(48)
        }
        val hScroll = rememberScrollState()
        val listModifier = if (wrap) {
            Modifier.fillMaxSize()
        } else {
            Modifier.width(contentWidth).fillMaxHeight()
        }
        Box(modifier = if (wrap) Modifier.fillMaxSize() else Modifier.fillMaxSize().horizontalScroll(hScroll)) {
            LazyColumn(modifier = listModifier) {
                itemsIndexed(notes, key = { i, _ -> "note-$i" }) { _, note ->
                    DiffNoteRow(ChangesL.note(note, diff))
                }
                itemsIndexed(rows, key = { i, _ -> i }, contentType = { _, row -> row.kind }) { index, row ->
                    DiffLineRow(
                        row = row,
                        digits = digits,
                        wrap = wrap,
                        style = codeStyle,
                        textWidthPx = textWidthPx,
                        // 折叠行自带被折叠片段的起始下标（数据层给的），UI 不用反推
                        onExpandFold = { c ->
                            if (c >= 0) expandedFolds = expandedFolds + c
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DiffLineRow(
    row: DiffRow,
    digits: Int,
    wrap: Boolean,
    style: androidx.compose.ui.text.TextStyle,
    /** 自动换行时文本列的可用像素宽；<= 0 表示走横向滚动那条路径，不做悬挂缩进。 */
    textWidthPx: Int = 0,
    onExpandFold: (Int) -> Unit = {},
) {
    val (bg, signColor, sign) = when (row.kind) {
        DiffRow.Kind.ADD -> Triple(Dsh.success.copy(alpha = 0.12f), Dsh.success, "+")
        DiffRow.Kind.DELETE -> Triple(Dsh.error.copy(alpha = 0.12f), Dsh.error, "−")
        DiffRow.Kind.HUNK -> Triple(Dsh.bgTrack, Dsh.labelTertiary, "")
        DiffRow.Kind.CONTEXT -> Triple(Dsh.bgCode, Dsh.labelTertiary, " ")
        // 折叠行：灰底、无行号，点一下展开（本轮先落到能渲染且不崩，交互下一片接）
        DiffRow.Kind.FOLD -> Triple(Dsh.bgCodeBanner, Dsh.labelSecondary, "")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = DshSpace.s8, vertical = 1.dp),
    ) {
        if (row.kind == DiffRow.Kind.HUNK) {
            Text(row.text, color = Dsh.labelTertiary, style = style, maxLines = 1, softWrap = false)
            return@Row
        }
        if (row.kind == DiffRow.Kind.FOLD) {
            // 稿 04：「展开中间 N 行」——点一下把这段未改动的上下文放出来
            Text(
                ChangesL.expandHiddenRows.format(row.hiddenCount),
                color = Dsh.labelSecondary,
                style = style,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = null, indication = dshRipple()) { onExpandFold(row.foldStart) },
            )
            return@Row
        }
        // 稿 04：行号列只显示**新文件**的行号；删除行没有新行号，就留空（不是显示旧行号）
        Text(
            (row.newNo?.toString() ?: "").padStart(digits),
            color = Dsh.labelTertiary,
            style = style,
            maxLines = 1,
            softWrap = false,
        )
        Text(" $sign ", color = signColor, style = style, maxLines = 1, softWrap = false)
        val lineText = remember(row, signColor) { diffLineText(row, signColor.copy(alpha = 0.3f)) }
        // 稿 04：折行的续行要 2ch 悬挂缩进。断点由真实排版给出（TextMeasurer + 约束宽度），
        // 不按字符数猜；切片走 AnnotatedString，行内改动的加深底色不会丢。
        val lineMeasurer = rememberTextMeasurer()
        val lineRanges = remember(lineText, textWidthPx, wrap) {
            if (!wrap || textWidthPx <= 0) {
                emptyList()
            } else {
                val measured = lineMeasurer.measure(
                    text = lineText,
                    style = style,
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = textWidthPx),
                    softWrap = true,
                    maxLines = Int.MAX_VALUE,
                )
                if (measured.lineCount <= 1) {
                    emptyList()
                } else {
                    (0 until measured.lineCount).map { i ->
                        measured.getLineStart(i) to measured.getLineEnd(i, visibleEnd = true)
                    }
                }
            }
        }
        if (lineRanges.isEmpty()) {
            Text(
                lineText,
                color = Dsh.labelPrimary,
                style = style,
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.weight(1f),
            )
        } else {
            Column(modifier = Modifier.weight(1f)) {
                lineRanges.forEachIndexed { index, (from, to) ->
                    val slice = lineText.subSequence(from, to)
                    Text(
                        if (index == 0) {
                            slice
                        } else {
                            AnnotatedString(" ".repeat(HANGING_INDENT_CHARS) + slice.text, slice.spanStyles, slice.paragraphStyles)
                        },
                        color = Dsh.labelPrimary,
                        style = style,
                        softWrap = false,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}

/** 行文本：tab 展开为 4 空格；行内变化片段叠一层更深的同色底（区间按原串计，逐段展开以免错位）。 */
private fun diffLineText(row: DiffRow, emphasis: Color): AnnotatedString = buildAnnotatedString {
    fun put(from: Int, to: Int) = append(row.text.substring(from, to).replace("\t", "    "))
    var cursor = 0
    for (r in row.emphasis) {
        val start = r.first.coerceIn(cursor, row.text.length)
        val end = (r.last + 1).coerceIn(start, row.text.length)
        put(cursor, start)
        withStyle(SpanStyle(background = emphasis)) { put(start, end) }
        cursor = end
    }
    put(cursor, row.text.length)
}

/**
 * 稿 04 的底部提问条：贴着差异区底部的输入形状按钮。
 * 只负责「看起来像输入框、点一下带着这个文件的上下文回对话页」，真正的输入在对话页完成
 * ——那里已经有草稿、附件、模型与发送链路，重做一套不划算。
 */
@Composable
private fun AskAboutFileBar(file: ChangedFile, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpace.s12, vertical = DshSpace.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DshTouch.min)
                .clip(RoundedCornerShape(DshRadius.composer))
                .background(Dsh.bgInput)
                .clickable(interactionSource = null, indication = dshRipple(), onClick = onClick)
                .semantics { role = Role.Button; contentDescription = ChangesL.askAboutFile }
                .padding(horizontal = DshSpace.s16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MessageOutline16, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(DshIconSize.sm))
            Spacer(Modifier.width(DshSpace.s8))
            Text(
                ChangesL.askAboutFile,
                color = Dsh.labelTertiary,
                style = DshType.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
