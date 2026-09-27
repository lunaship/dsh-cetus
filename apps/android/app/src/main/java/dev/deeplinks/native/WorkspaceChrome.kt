package dev.deeplinks.native

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import dev.deeplinks.native.ui.DshBrandMark
import dev.deeplinks.native.ui.DshEmptyState
import dev.deeplinks.core.tabularNums
import dev.deeplinks.native.ui.DshTag
import dev.deeplinks.core.DshType
import dev.deeplinks.core.DshS
import dev.deeplinks.native.ui.DshListHeader
import dev.deeplinks.native.ui.DshListRow
import dev.deeplinks.native.ui.DshListSection
import dev.deeplinks.native.ui.DshSheetHeader
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import dev.deeplinks.native.ui.ChatLoadingSkeleton
import dev.deeplinks.native.util.ChatCanvasKind
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deeplinks.core.Dsh
import androidx.compose.foundation.layout.Arrangement
import dev.deeplinks.native.util.MessageGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.deeplinks.core.dshRipple
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.stateDescription
import dev.deeplinks.native.util.compactTokens
import dev.deeplinks.native.util.exactTokens
import dev.deeplinks.core.L
import dev.deeplinks.native.ui.DshBanner
import dev.deeplinks.native.ui.DshBannerTone
import dev.deeplinks.native.ui.DshTopSegment
import dev.deeplinks.native.util.StreamBannerKind
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.draw.shadow

/**
 * Workspace 主界面抽出的独立 chrome（COM-001 拆解）。
 * 与 WorkspaceScreen 同包，通过 internal 复用；不持有业务状态。
 */

/**
 * 工具调用查找条（从 WorkspaceScreen 抽出，COM-001 拆解）。
 * 客户端过滤当前会话工具消息，瞬态状态不持久化。
 */
@Composable
internal fun ToolSearchBar(
    visible: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(animationSpec = tween(motionDuration(180))) + fadeIn(animationSpec = tween(motionDuration(150))),
        exit = shrinkVertically(animationSpec = tween(motionDuration(160))) + fadeOut(animationSpec = tween(motionDuration(120)))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = COMPOSER_SIDE_CLEARANCE, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(DshRadius.md))
                    .background(Dsh.bgInput)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(SearchOutline16, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = DshType.body.copy(color = Dsh.labelPrimary),
                    cursorBrush = SolidColor(Dsh.brand400),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(L.toolSearchPlaceholder, color = Dsh.labelTertiary, style = DshType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            inner()
                        }
                    }
                )
                if (query.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = L.clearSearch
                            }
                            .clickable { onQueryChange("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            CloseOutline16,
                            contentDescription = null,
                            tint = Dsh.labelTertiary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 断线重连横幅（从 WorkspaceScreen 抽出，COM-001 拆解）。
 * SSE 断开时提示，客户端自动退避重连；复用 [DshBanner]。
 */
@Composable
internal fun StreamReconnectBanner(
    kind: StreamBannerKind,
    onRetry: () -> Unit,
) {
    AnimatedVisibility(
        visible = kind != StreamBannerKind.Hidden,
        enter = expandVertically(animationSpec = tween(motionDuration(200))) + fadeIn(animationSpec = tween(motionDuration(200))),
        exit = shrinkVertically(animationSpec = tween(motionDuration(180))) + fadeOut(animationSpec = tween(motionDuration(180)))
    ) {
        DshBanner(
            text = when (kind) {
                StreamBannerKind.Connecting -> L.connecting
                StreamBannerKind.Failed -> L.connectionFailedReconnecting
                else -> L.disconnectedReconnecting
            },
            tone = if (kind == StreamBannerKind.Failed)
                DshBannerTone.Error else DshBannerTone.Info,
            actionLabel = L.retry,
            onAction = onRetry,
            leading = {
                val reconnRotation = rememberMotionSpin(900, label = "reconnRot")
                Icon(
                    RefreshOutline16,
                    contentDescription = null,
                    tint = Dsh.labelTertiary,
                    modifier = Modifier
                        .size(12.dp)
                        .rotate(reconnRotation ?: 0f)
                )
            },
            contentDescription = when (kind) {
                StreamBannerKind.Connecting -> L.connecting
                StreamBannerKind.Failed -> L.connectionFailedReconnecting
                else -> L.disconnectedReconnectingContentDescription
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * 设备不可达横幅（方案 §4.6）：最近设备离线时不强制跳回设备页，
 * Workspace 仍然打开，内容区顶部给出「重试 / 设备与配对」两个明确下一步。
 */
@Composable
internal fun DeviceUnreachableBanner(
    hostName: String,
    visible: Boolean,
    onRetry: () -> Unit,
    onOpenDevice: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(animationSpec = tween(motionDuration(200))) +
            fadeIn(animationSpec = tween(motionDuration(200))),
        exit = shrinkVertically(animationSpec = tween(motionDuration(180))) +
            fadeOut(animationSpec = tween(motionDuration(180))),
    ) {
        val message = L.cannotConnectHost.format(hostName)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(DshRadius.md))
                .background(Dsh.bgCard)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { contentDescription = message },
        ) {
            Text(
                message,
                color = Dsh.labelSecondary,
                style = DshType.captionRelaxed,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BannerAction(label = L.deviceAndPairing, onClick = onOpenDevice)
                Spacer(Modifier.width(4.dp))
                BannerAction(label = L.retry, primary = true, onClick = onRetry)
            }
        }
    }
}

@Composable
private fun BannerAction(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(DshRadius.full))
            // 实心主按钮统一 brand500（brand400 底配白色小字在暗色下不足 AA）
            .background(if (primary) Dsh.brand500 else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (primary) Dsh.onBrand else Dsh.labelPrimary,
            style = DshType.microRelaxed,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun HeroShell() {
    DshEmptyState(
        title = DshS.heroSlogan,
        message = DshS.heroHint,
        graphic = { DshBrandMark() },
    )
}

@Composable
internal fun ChatHistoryError(
    message: String?,
    onRetry: () -> Unit,
    title: String = DshS.loadConversationFailed,
    hint: String? = null,
    compact: Boolean = true,
) {
    // 兜底文案常与标题相同（「加载失败 / 加载失败」），这种不算细节
    val detail = message?.takeUnless { it.isBlank() || it == title }
    DshEmptyState(
        title = title,
        // 有提示语时它是主说明，原始错误（常是 timeout 这类技术文本）降为脚注
        message = hint ?: detail,
        footnote = detail.takeIf { hint != null },
        actionLabel = DshS.retry,
        onAction = onRetry,
        compact = compact,
    )
}

@Composable
internal fun SearchStatusBanner(message: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.md))
            .background(Dsh.bgCard)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            color = Dsh.labelSecondary,
            style = DshType.captionRelaxed,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(DshRadius.full))
                .background(Dsh.brand400)
                .semantics {
                    role = Role.Button
                    contentDescription = L.retry
                }
                .clickable(onClick = onRetry)
                .padding(horizontal = 12.dp)
                .wrapContentHeight(Alignment.CenterVertically),
        ) {
            Text(L.retry, color = Dsh.onBrand, style = DshType.label, fontWeight = FontWeight(500))
        }
    }
}


@Composable
internal fun ContextMeterButton(
    stats: MobileSessionStats,
    running: Boolean = false,
    showPercent: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val used = stats.contextPressureTokens
    val window = stats.contextWindow
    if (window <= 0) return
    val percent = ((used * 100) / window).toFloat().coerceIn(0f, 100f)
    // 明细占比（系统/工具/对话消息，按 breakdown 分段）
    val breakdownTotal = stats.systemTokens + stats.toolsTokens + stats.messageTokens
    val hasBreakdown = breakdownTotal > 0
    val systemRatio = if (hasBreakdown) stats.systemTokens.toFloat() / breakdownTotal else 0f
    val toolsRatio = if (hasBreakdown) stats.toolsTokens.toFloat() / breakdownTotal else 0f
    val messagesRatio = if (hasBreakdown) stats.messageTokens.toFloat() / breakdownTotal else 0f

    Box {
        // 环形按钮（DSH：28px trigger，14px viewBox 圆环，2px stroke，后面跟百分比）
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val borderL3Color = Dsh.borderStrong
        val fillColor = if (running) Dsh.labelTertiary.copy(alpha = 0.55f) else Dsh.labelTertiary
        Row(
            modifier = Modifier
                .height(48.dp)
                .clip(RoundedCornerShape(DshRadius.full))
                .background(if (pressed) Dsh.pressed else Color.Transparent)
                .semantics {
                    role = Role.Button
                    contentDescription = L.contextUsed
                    stateDescription = "${percent.toInt()}%"
                }
                .clickable(interactionSource = interaction, indication = dshRipple()) { expanded = true }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Canvas(modifier = Modifier.size(14.dp)) {
                val stroke = 2.dp.toPx()
                val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = borderL3Color,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = stroke),
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = arcSize
                )
                if (used > 0) {
                    drawArc(
                        color = fillColor,
                        startAngle = -90f,
                        sweepAngle = 360f * percent / 100f,
                        useCenter = false,
                        style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                        topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                        size = arcSize
                    )
                }
            }
            if (showPercent) {
                Text(
                    text = "${percent.toInt()}%",
                    color = Dsh.labelTertiary,
                    style = DshType.caption,
                    lineHeight = 20.sp,
                    maxLines = 1,
                )
            }
        }

        // 用量面板（DSH：240dp 宽、radius 12、上方弹出）
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = Dsh.bgSubtle,
            shape = RoundedCornerShape(DshRadius.lg)
        ) {
            Column(modifier = Modifier.width(240.dp).padding(12.dp)) {
                // header：上下文已用 + 百分比 + 用量数字
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L.contextUsed, color = Dsh.labelTertiary, style = DshType.caption)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${percent.toInt()}%",
                        color = Dsh.labelPrimary,
                        style = DshType.label,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight(500)
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "~${compactTokens(used)} / ${compactTokens(window)} ${L.tokenUnitShort}",
                        color = Dsh.labelPrimary,
                        style = DshType.label.tabularNums(),
                        lineHeight = 20.sp,
                        fontWeight = FontWeight(500),
                    )
                }
                Spacer(Modifier.height(10.dp))
                // 分段条（DSH：4px 高、系统/工具/消息按占比分段）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(DshRadius.full))
                        .background(Dsh.pressed)
                ) {
                    if (hasBreakdown) {
                        // 段色与下方明细行的色块一一对应（systemAccent / toolsAccent / brand400）
                        val segments = listOf(
                            systemRatio to Dsh.systemAccent,
                            toolsRatio to Dsh.toolsAccent,
                            messagesRatio to Dsh.brand400,
                        )
                        segments.forEach { (ratio, color) ->
                            if (ratio > 0f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(ratio)
                                        .background(color)
                                )
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(percent / 100f)
                                .background(Dsh.labelTertiary)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                // 明细行（系统提示词/工具/对话消息 + 色块 + tok 数）
                ContextMeterRow(L.systemPrompt, compactTokens(stats.systemTokens), Dsh.systemAccent)
                Spacer(Modifier.height(4.dp))
                ContextMeterRow(L.tools, compactTokens(stats.toolsTokens), Dsh.toolsAccent)
                Spacer(Modifier.height(4.dp))
                ContextMeterRow(L.chatMessages, compactTokens(stats.messageTokens), Dsh.brand400)
            }
        }
    }
}

@Composable
internal fun ContextMeterRow(label: String, value: String, swatchColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(DshRadius.xs))
                .background(swatchColor)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = Dsh.labelSecondary, style = DshType.captionRelaxed, modifier = Modifier.weight(1f))
        Text(value, color = Dsh.labelPrimary, style = DshType.captionRelaxed.tabularNums(),)
    }
}

@Composable
internal fun CommandSuggestions(
    query: String,
    onPick: (PaletteCommand) -> Unit,
) {
    val grouped = remember(query) { filterPalette(DSH_PALETTE, query) }
    // 兜底：query 与输入文本同步；只有至少一组有结果才显示 picker
    if (grouped.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = COMPOSER_SIDE_CLEARANCE, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DshRadius.lg))
                .background(Dsh.bgCard)
                .padding(vertical = 6.dp)
        ) {
            grouped.forEach { (group, entries) ->
                Text(
                    group.displayName,
                    color = Dsh.labelTertiary,
                    style = DshType.captionRelaxed,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                )
                entries.forEach { entry ->
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(DshRadius.sm))
                            .background(if (pressed) Dsh.pressed else Color.Transparent)
                            .clickable(interactionSource = interaction, indication = dshRipple()) { onPick(entry.command) }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            entry.command.trigger,
                            color = Dsh.labelPrimary,
                            style = DshType.body,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // 触发词与描述分列：固定 220dp 会把 412dp 上的描述压到两三个字
                            modifier = Modifier
                                .widthIn(max = 160.dp)
                                .weight(0.45f, fill = false)
                        )
                        Text(
                            entry.command.description,
                            color = Dsh.labelTertiary,
                            style = DshType.captionRelaxed,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/** 导出会话为纯文本（分享用；无独立 log 下载 API 时的替代方案）。 */
internal fun loadSessionMessagesForExport(client: MobileApiClient, sessionId: String): List<MobileMessage> {
    val pages = mutableListOf<MobileMessage>()
    var before: Long? = null
    repeat(20) {
        val page = client.getSessionHistory(sessionId, beforeSeq = before, maxMessages = 80)
        pages.addAll(0, page.messages)
        before = page.nextBeforeSeq
        if (!page.hasMore || before == null) return@repeat
    }
    return pages
}

internal fun exportSessionTranscript(client: MobileApiClient, sessionId: String, title: String): String {
    val body = loadSessionMessagesForExport(client, sessionId).mapNotNull { msg ->
        val role = when (msg.role) {
            "user" -> L.userRole
            "assistant" -> L.assistantRole
            "reasoning" -> L.reasoningRole
            "tool_call" -> "${L.tools}:${msg.toolName ?: "?"}"
            "tool_result" -> L.resultRole
            else -> msg.role
        }
        val text = msg.text.ifBlank { msg.toolArgs.orEmpty() }.trim()
        if (text.isBlank()) null else "## $role\n$text"
    }.joinToString("\n\n")
    return "# $title\n\n$body"
}

/** 聚合 header：轻量行（对齐思考条），默认收起；展开后左侧细轨 + 明细。 */
@Composable
internal fun ToolGroupHeader(
    group: MessageGroup.ToolGroup,
    sweepingId: String?,
) {
    var expanded by remember(group.groupKey) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val first = group.items.first()
    val last = group.items.last()
    val totalDuration = if (first.time > 0 && last.time >= first.time) last.time - first.time else null
    val groupRunning = sweepingId != null && group.items.any { it.id == sweepingId }
    val pressTint = Dsh.pressed
    val rail = Dsh.borderStrong
    val summaryTitle = dev.deeplinks.native.util.formatToolGroupSummary(group.items) { L.toolCallCount.format(it) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .heightIn(min = 36.dp)
                .clip(RoundedCornerShape(DshRadius.sm))
                .clickable(interactionSource = interaction, indication = dshRipple()) { expanded = !expanded }
                .semantics {
                    role = Role.Button
                    contentDescription = summaryTitle
                    stateDescription = if (expanded) L.collapse else L.expand
                }
                .then(if (pressed) Modifier.drawBehind { drawRect(pressTint) } else Modifier)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (groupRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    color = Dsh.brand500,
                    strokeWidth = 1.5.dp,
                )
                Spacer(Modifier.width(10.dp))
            } else {
                Icon(
                    CodeOutline16,
                    contentDescription = null,
                    tint = Dsh.labelTertiary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                summaryTitle,
                color = if (groupRunning) Dsh.labelSecondary else Dsh.labelTertiary,
                style = DshType.title,
                fontWeight = FontWeight(500),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            if (groupRunning) {
                ShimmerLabel(text = L.executing.trimEnd('…', '.'), working = true)
                Spacer(Modifier.width(8.dp))
            } else if (totalDuration != null) {
                Text(
                    formatTraceDuration(totalDuration),
                    color = Dsh.labelTertiary,
                    style = DshType.microRelaxed.tabularNums(),
                )
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                if (expanded) ChevronUpOutline14 else ChevronDownOutline14,
                // 展开状态已在行的 stateDescription 里播报，图标不再重复报一遍
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(motionDuration(200), easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(motionDuration(150))),
            exit = shrinkVertically(animationSpec = tween(motionDuration(180), easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(motionDuration(150))),
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 7.dp, top = 2.dp)
                    .drawBehind {
                        val x = 3.5.dp.toPx()
                        drawLine(rail, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                    }
                    .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    group.items.forEach { item ->
                        MessageItem(
                            msg = item,
                            running = sweepingId != null && item.id == sweepingId,
                            onCopy = {},
                            onQuote = {},
                            onFork = {},
                        )
                    }
                }
            }
        }
    }
}

/**
 * 会话顶栏（单行）：导航、会话名、对话/轨迹胶囊分段和溢出菜单。
 * 菜单项由 [workspaceHeaderMenuItems] 构建后传入；设备入口在菜单与侧栏底部。
 */
@Composable
internal fun WorkspaceTopBar(
    running: Boolean,
    title: String,
    showBack: Boolean,
    onNavigate: () -> Unit,
    viewMode: String,
    showViewModeTabs: Boolean,
    onSelectViewMode: (String) -> Unit,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    menuItems: List<DshMenuItem>,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val navInteraction = remember { MutableInteractionSource() }
            val navPressed by navInteraction.collectIsPressedAsState()
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (navPressed) Dsh.pressed else Color.Transparent)
                    .clickable(interactionSource = navInteraction, indication = dshRipple()) { onNavigate() }
                    .semantics {
                        role = Role.Button
                        contentDescription = if (showBack) L.back else L.sessionList
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (showBack) ArrowLeftOutline16 else PanelLeftOutline16,
                    contentDescription = null,
                    tint = Dsh.labelSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (running) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Dsh.brand400),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    title,
                    color = Dsh.labelPrimary,
                    style = DshType.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            if (showViewModeTabs) {
                DshTopSegment(
                    labels = listOf(L.tabChat, L.tabTrace),
                    selectedIndex = if (viewMode == "trace") 1 else 0,
                    onSelect = { index -> onSelectViewMode(if (index == 1) "trace" else "chat") },
                )
            }

            Box {
                val moreInteraction = remember { MutableInteractionSource() }
                val morePressed by moreInteraction.collectIsPressedAsState()
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (morePressed) Dsh.pressed else Color.Transparent)
                        .clickable(interactionSource = moreInteraction, indication = dshRipple()) { onMenuExpandedChange(true) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        EllipsisOutline16,
                        contentDescription = L.moreActions,
                        tint = Dsh.labelSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DshMenu(
                    expanded = menuExpanded,
                    onDismiss = { onMenuExpandedChange(false) },
                    items = menuItems,
                )
            }
        }
    }
}

/** 进行中的目标：一行次要文字，贴在输入区上方，不进顶栏。 */
@Composable
internal fun ChatGoalLine(text: String) {
    Text(
        text,
        color = Dsh.labelSecondary,
        style = DshType.body,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp),
    )
}

/**
 * 空会话画布（从 WorkspaceScreen 的 LazyColumn 抽出，COM-001 拆解）：
 * 加载 / 运行 / 失败 / 空态四选一，属于 LazyListScope 所以做成扩展。
 */
internal fun LazyListScope.chatEmptyCanvas(
    kind: ChatCanvasKind,
    elapsedSec: Long,
    historyLoadError: String?,
    onRetry: () -> Unit,
) {
    when (kind) {
        ChatCanvasKind.Loading -> item(key = "chat-loading-skeleton") {
            ChatLoadingSkeleton()
        }
        ChatCanvasKind.Working -> item(key = "turn-status") {
            ThinkingStatusRow(elapsedSec)
        }
        ChatCanvasKind.Error -> item(key = "chat-load-error") {
            // 与空态 hero 同一骨架：整屏居中，而不是贴在顶部
            Box(
                modifier = Modifier
                    .fillParentMaxSize()
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                ChatHistoryError(
                    message = historyLoadError,
                    hint = DshS.loadConversationFailedHint,
                    onRetry = onRetry,
                    compact = false,
                )
            }
        }
        ChatCanvasKind.Empty, ChatCanvasKind.Content -> item(key = "empty-hero") {
            Box(
                modifier = Modifier
                    .fillParentMaxSize()
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                HeroShell()
            }
        }
    }
}

/**
 * 悬浮「回到底部」：40dp 圆形 + 阴影，盖在消息流右下角，不占布局高度。
 * 位置与原生聊天 App 一致（右下角），出现/消失不会让消息流串位；
 * 用户停在历史时新到达的消息条数挂在右上角。
 */
@Composable
internal fun ScrollToBottomButton(unread: Int, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(interactionSource = interaction, indication = dshRipple(), onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = L.scrollToBottom
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(if (pressed) Dsh.bgPressed else Dsh.bgCard),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                ChevronDownOutline14,
                contentDescription = null,
                tint = Dsh.labelPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
        if (unread > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .defaultMinSize(minWidth = 18.dp)
                    .clip(CircleShape)
                    .background(Dsh.brand500)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (unread > 99) "99+" else unread.toString(),
                    color = Dsh.onBrand,
                    style = DshType.microRelaxed,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * 会话级统计（输入框下方）：累计信息在此处紧凑显示，避免挂在某一条历史回复上。
 * 居中胶囊（轮次·步骤 / Token / 速率，分段圆点分隔），点按展开 Paseo 式用量与性能看板。
 */
@Composable
internal fun SessionStatsLine(stats: MobileSessionStats?) {
    val s = stats ?: return
    var detailOpen by remember { mutableStateOf(false) }

    val inputTokens = s.uncachedInputTokens + s.cacheReadTokens
    val totalTokens = inputTokens + s.outputTokens
    val parts = buildList {
        if (s.turns > 0 || s.steps > 0) add(L.statsTurnsSteps.format(s.turns, s.steps))
        if (totalTokens > 0) {
            add("${compactTokens(totalTokens)} ${L.tokenUnitShort}")
        } else if (inputTokens > 0 || s.outputTokens > 0) {
            add(L.inputOutputTokens.format(compactTokens(inputTokens), compactTokens(s.outputTokens)))
        }
        if (s.decodeMs > 0 && s.decodeTokens > 0) {
            add(String.format(java.util.Locale.US, "%.0f %s", s.decodeTokens * 1000.0 / s.decodeMs, L.tokenRateUnit))
        }
    }
    if (parts.isEmpty()) return

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = COMPOSER_SIDE_CLEARANCE, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(DshRadius.full))
                .background(if (pressed) Dsh.pressed else Dsh.bgTrack.copy(alpha = 0.6f))
                .semantics {
                    role = Role.Button
                    contentDescription = L.statsViewDetails
                }
                .clickable(
                    interactionSource = interaction,
                    indication = dshRipple(),
                    onClick = { detailOpen = true },
                )
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            parts.forEachIndexed { index, part ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 7.dp)
                            .size(2.5.dp)
                            .clip(CircleShape)
                            .background(Dsh.labelTertiary.copy(alpha = 0.45f)),
                    )
                }
                Text(
                    text = part,
                    color = Dsh.labelTertiary,
                    style = DshType.captionRelaxed,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                InfoOutline16,
                contentDescription = null,
                tint = Dsh.labelTertiary.copy(alpha = 0.6f),
                modifier = Modifier.size(10.dp),
            )
        }
    }

    if (detailOpen) {
        SessionStatsDetailDialog(
            stats = s,
            onDismiss = { detailOpen = false },
        )
    }
}

/**
 * 会话用量与执行性能看板：点按后用单屏对话框展示完整数据，避免用户在底部面板中上下翻找。
 */
@Composable
internal fun SessionStatsDetailDialog(
    stats: MobileSessionStats,
    onDismiss: () -> Unit,
) {
    val strings = DshS
    val s = stats
    val inputTokens = s.uncachedInputTokens + s.cacheReadTokens
    val totalTokens = inputTokens + s.outputTokens
    val cacheHitPercent = if (inputTokens > 0) ((s.cacheReadTokens * 100) / inputTokens).toInt() else 0
    val speedToks = if (s.decodeMs > 0 && s.decodeTokens > 0) {
        s.decodeTokens * 1000.0 / s.decodeMs
    } else 0.0

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(DshRadius.dialog))
                .background(Dsh.bgGrouped)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 16.dp),
        ) {
            DshSheetHeader(
                title = strings.sessionStatsSheetTitle,
                subtitle = strings.statsTurnsCount.format(s.turns) + " · " + strings.statsStepsCount.format(s.steps),
                onClose = onDismiss,
            )
            Column(Modifier.padding(end = 8.dp)) {
                StatsDetailSections(s, inputTokens, totalTokens, cacheHitPercent, speedToks)
            }
        }
    }
}

/** 看板内容：概览 2×2 指标块 → Token 明细分组 → 上下文窗口卡片。 */
@Composable
private fun StatsDetailSections(
    s: MobileSessionStats,
    inputTokens: Long,
    totalTokens: Long,
    cacheHitPercent: Int,
    speedToks: Double,
) {
    val strings = DshS
    Column {
            Spacer(Modifier.height(8.dp))
            DshListHeader(strings.statsOverview)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatsMetricCard(
                    title = "${s.turns} / ${s.steps}",
                    label = strings.statsTurnsStepsLabel,
                    sub = if (s.llmMs > 0 || s.toolMs > 0) {
                        strings.statsTotalTimeSeconds.format((s.llmMs + s.toolMs) / 1000.0)
                    } else strings.statsInteractionTotal,
                    modifier = Modifier.weight(1f),
                )
                StatsMetricCard(
                    title = if (cacheHitPercent > 0) "$cacheHitPercent%" else "--",
                    label = strings.statsCacheHitLabel,
                    sub = if (s.cacheReadTokens > 0) strings.statsCacheHitSub.format(compactTokens(s.cacheReadTokens)) else strings.statsCacheMiss,
                    accent = cacheHitPercent > 0,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatsMetricCard(
                    title = if (speedToks > 0) String.format(java.util.Locale.US, "%.0f %s", speedToks, strings.tokenRateUnit) else "--",
                    label = strings.statsDecodeSpeedLabel,
                    sub = if (s.outputTokens > 0) strings.statsOutputSub.format(compactTokens(s.outputTokens)) else strings.statsDecodeSpeedHint,
                    modifier = Modifier.weight(1f),
                )
                StatsMetricCard(
                    title = compactTokens(totalTokens),
                    label = strings.statsTotalTokensLabel,
                    sub = strings.statsInputPlusOutput,
                    modifier = Modifier.weight(1f),
                )
            }

            DshListSection(header = strings.statsTokensBreakdown) {
                StatsDetailRow(
                    label = strings.statsUncachedInput,
                    value = "${exactTokens(s.uncachedInputTokens)} ${strings.tokenUnit}",
                )
                StatsDetailRow(
                    label = strings.statsCachedInput,
                    value = "${exactTokens(s.cacheReadTokens)} ${strings.tokenUnit}",
                    tag = if (cacheHitPercent > 0) "$cacheHitPercent%" else null,
                )
                StatsDetailRow(
                    label = strings.statsOutputTokens,
                    value = "${exactTokens(s.outputTokens)} ${strings.tokenUnit}",
                )
                StatsDetailRow(
                    label = strings.statsTotalTokens,
                    value = "${exactTokens(totalTokens)} ${strings.tokenUnit}",
                    highlight = true,
                )
            }

            if (s.contextWindow > 0) {
                val used = s.contextPressureTokens
                val window = s.contextWindow
                val windowPercent = ((used * 100) / window).toInt().coerceIn(0, 100)
                val breakdownTotal = s.systemTokens + s.toolsTokens + s.messageTokens
                DshListSection(
                    header = strings.statsContextWindow,
                    footer = if (breakdownTotal > 0) {
                        strings.statsContextBreakdownLine.format(
                            compactTokens(s.systemTokens),
                            compactTokens(s.toolsTokens),
                            compactTokens(s.messageTokens),
                        )
                    } else {
                        null
                    },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "${compactTokens(used)} / ${compactTokens(window)} ${strings.tokenUnit} · $windowPercent%",
                            style = DshType.body,
                            color = if (windowPercent > 80) Dsh.warn else Dsh.labelPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(DshRadius.full))
                                .background(Dsh.bgTrack)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth((windowPercent / 100f).coerceIn(0f, 1f))
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(DshRadius.full))
                                    .background(if (windowPercent > 80) Dsh.warn else Dsh.brand500)
                            )
                        }
                    }
                }
            }
    }
}


@Composable
private fun StatsMetricCard(
    title: String,
    label: String,
    sub: String,
    accent: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(DshRadius.group))
            .background(Dsh.bgGroupedCard)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = DshType.microRelaxed,
            color = Dsh.labelSecondary,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = title,
            style = DshType.title,
            color = if (accent) Dsh.brand500 else Dsh.labelPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = sub,
            style = DshType.microRelaxed,
            color = Dsh.labelTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatsDetailRow(
    label: String,
    value: String,
    tag: String? = null,
    highlight: Boolean = false,
) {
    DshListRow(
        title = label,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (tag != null) {
                    DshTag(text = tag, color = Dsh.brandTint, contentColor = Dsh.brand500)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = value,
                    style = (if (highlight) DshType.bodyStrong else DshType.body).tabularNums(),
                    color = if (highlight) Dsh.brand500 else Dsh.labelSecondary,
                )
            }
        },
    )
}
