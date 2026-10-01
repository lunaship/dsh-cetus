package dev.deeplinks.native

import dev.deeplinks.native.DshIconSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import dev.deeplinks.native.ui.DshErrorState
import dev.deeplinks.native.ui.DshPageChromeDensity
import dev.deeplinks.core.tabularNums
import dev.deeplinks.core.DshType
import dev.deeplinks.core.DshS
import dev.deeplinks.native.ui.DshSheetHeader

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.deeplinks.core.dshRipple
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.stateDescription
import dev.deeplinks.native.util.compactTokens
import dev.deeplinks.core.L
import dev.deeplinks.native.ui.DshTag
import dev.deeplinks.native.ui.HostStatusDot
import dev.deeplinks.native.ui.DshSegmentedToggle
import dev.deeplinks.native.util.StreamBannerKind
import dev.deeplinks.native.NewChatOutline16
import dev.deeplinks.native.ListPenOutline16
import dev.deeplinks.native.AgentPresetOutline16
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.draw.shadow

/**
 * Workspace 主界面抽出的独立 chrome（COM-001 拆解）。
 * 与 WorkspaceScreen 同包，通过 internal 复用；不持有业务状态。
 */

/**
 * 断线重连：顶栏下面一行字。失败时字变红，「重试」是文字按钮。
 */
@Composable
internal fun StreamReconnectBanner(
    kind: StreamBannerKind,
    onRetry: () -> Unit,
) {
    val text = when (kind) {
        StreamBannerKind.Connecting -> L.connecting
        StreamBannerKind.Failed -> L.connectionFailedReconnecting
        else -> L.disconnectedReconnecting
    }
    val description = when (kind) {
        StreamBannerKind.Connecting -> L.connecting
        StreamBannerKind.Failed -> L.connectionFailedReconnecting
        else -> L.disconnectedReconnectingContentDescription
    }
    AnimatedVisibility(
        visible = kind != StreamBannerKind.Hidden,
        enter = expandVertically(animationSpec = tween(motionDuration(200))) + fadeIn(animationSpec = tween(motionDuration(200))),
        exit = shrinkVertically(animationSpec = tween(motionDuration(180))) + fadeOut(animationSpec = tween(motionDuration(180)))
    ) {
        QuietStatusLine(
            text = text,
            alert = kind == StreamBannerKind.Failed,
            contentDescription = description,
        ) {
            QuietStatusAction(L.retry, onRetry)
        }
    }
}

/**
 * 设备不可达：一行字加两个文字动作。离线时不强制跳回设备页。
 */
@Composable
internal fun DeviceUnreachableBanner(
    hostName: String,
    visible: Boolean,
    onRetry: () -> Unit,
    onOpenDevice: () -> Unit,
) {
    val message = L.cannotConnectHost.format(hostName)
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(animationSpec = tween(motionDuration(200))) +
            fadeIn(animationSpec = tween(motionDuration(200))),
        exit = shrinkVertically(animationSpec = tween(motionDuration(180))) +
            fadeOut(animationSpec = tween(motionDuration(180))),
    ) {
        QuietStatusLine(text = message, alert = false, contentDescription = message) {
            QuietStatusAction(L.deviceAndPairing, onOpenDevice)
            QuietStatusAction(L.retry, onRetry)
        }
    }
}

@Composable
private fun QuietStatusLine(
    text: String,
    alert: Boolean,
    contentDescription: String,
    actions: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = COMPOSER_SIDE_CLEARANCE, vertical = DshSpace.s2)
            .semantics { this.contentDescription = contentDescription },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = if (alert) Dsh.error else Dsh.labelSecondary,
            style = DshType.captionRelaxed,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
private fun QuietStatusAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = DshSpace.s8)
            .semantics {
                role = Role.Button
                this.contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Dsh.labelPrimary,
            style = DshType.captionRelaxed,
            fontWeight = FontWeight.Medium,
        )
    }
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
    // 错误态：错误图标 + 简短错误 + 重试，不挂欢迎插画（docs/visual-rules.md 第五节）
    DshErrorState(
        title = title,
        // 有提示语时它是主说明，原始错误（常是 timeout 这类技术文本）降为脚注
        message = hint ?: detail,
        footnote = detail.takeIf { hint != null },
        actionLabel = DshS.retry,
        onAction = onRetry,
        compact = compact,
    )
}

/** 工具搜索失败：一行字加文字重试，不另做色块按钮。 */
@Composable
internal fun SearchStatusBanner(message: String, onRetry: () -> Unit) {
    QuietStatusLine(text = message, alert = false, contentDescription = message) {
        QuietStatusAction(L.retry, onRetry)
    }
}

@Composable
internal fun ContextMeterButton(
    stats: MobileSessionStats,
    running: Boolean = false,
    showPercent: Boolean = true,
) {
    var statsOpen by remember { mutableStateOf(false) }
    val used = stats.contextPressureTokens
    val window = stats.contextWindow
    if (window <= 0) return
    val percent = ((used * 100) / window).toFloat().coerceIn(0f, 100f)

    // 环形按钮（DSH：28px trigger，14px viewBox 圆环，2px stroke，后面跟百分比）。
    // E8：百分比此前没有任何说明，TalkBack 只读到「上下文已用」不知道数值；现在
    // contentDescription 直接把数值带上（「上下文已用 46%」），点按打开「会话用量」
    // 面板（面板里的「上下文占用」与此环同一口径：contextPressureTokens/contextWindow，
    // 两处都改成同一份样例数据后截图不会再出现 46% vs 19% 的矛盾）。
    val interaction = remember { MutableInteractionSource() }
    val borderL3Color = Dsh.borderStrong
    val fillColor = if (running) Dsh.labelTertiary.copy(alpha = 0.55f) else Dsh.labelTertiary
    Row(
        modifier = Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(DshRadius.control))
            .semantics {
                role = Role.Button
                contentDescription = L.contextUsedPercent.format(percent.toInt())
            }
            .clickable(interactionSource = interaction, indication = dshRipple()) { statsOpen = true }
            .padding(horizontal = DshSpace.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpace.s4),
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

    if (statsOpen) {
        SessionStatsDetailDialog(stats = stats, onDismiss = { statsOpen = false })
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
                .clip(RoundedCornerShape(DshRadius.micro))
                .background(swatchColor)
        )
        Spacer(Modifier.width(DshSpace.s6))
        Text(label, color = Dsh.labelSecondary, style = DshType.captionRelaxed, modifier = Modifier.weight(1f))
        Text(value, color = Dsh.labelPrimary, style = DshType.captionRelaxed.tabularNums(),)
    }
}

/** 命令图标：按 trigger 映射到自有图标集（模型层保持纯数据，便于 JVM 单测）。 */
private fun paletteIcon(command: PaletteCommand): ImageVector = when (command.trigger) {
    "/plan" -> ListPenOutline16
    "/goal" -> GoalOutline16
    "/subagent" -> BranchOutline16
    "/skills" -> SkillOutline16
    "/pause" -> PauseOutline16
    "/resume" -> PlayOutline16
    "/clear" -> EraserOutline16
    "/feedback" -> FeedbackOutline16
    "/new-session" -> NewChatOutline16
    "/search" -> SearchOutline16
    "/model" -> SparkleOutline16
    "/permission" -> ShieldOutline16
    "/chat" -> MessageOutline16
    "/trace" -> ChecklistOutline16
    "/settings" -> SettingsOutline16
    else -> CodeOutline16
}

/**
 * 斜杠命令面板：从输入框上方浮起的卡片，最多约半屏，对话仍然看得见。
 * 每行「图标块 · 中文名 + 灰色 trigger · 一句说明」；没输入时按分组展示，
 * 输入后只留匹配项并高亮第一条（它就是点「发送」时最可能想要的那条）。
 */
@Composable
internal fun CommandSuggestions(
    query: String,
    onPick: (PaletteCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filtering = query.length > 1
    // 过滤时不再分组：拍平后「trigger 以输入开头」的排前面（/c → 清除上下文、对话视图，再到 /feedback）
    val grouped = remember(query) {
        val raw = filterPalette(DSH_PALETTE, query)
        if (!filtering) raw else {
            val needle = query.removePrefix("/").lowercase()
            val flat = raw.flatMap { it.second }.sortedByDescending { it.command.trigger.removePrefix("/").lowercase().startsWith(needle) }
            if (flat.isEmpty()) emptyList() else listOf(flat.first().group to flat)
        }
    }
    if (grouped.isEmpty()) return
    val first = grouped.first().second.first().command
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = COMPOSER_SIDE_CLEARANCE, vertical = DshSpace.s6),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 340.dp)
                .shadow(4.dp, RoundedCornerShape(DshRadius.container), clip = false)
                .clip(RoundedCornerShape(DshRadius.container))
                .background(Dsh.bgCard)
                .verticalScroll(rememberScrollState())
                .padding(DshSpace.s6),
        ) {
            grouped.forEach { (group, entries) ->
                if (!filtering) {
                    Text(
                        group.displayName,
                        color = Dsh.labelTertiary,
                        style = DshType.microMedium,
                        modifier = Modifier.padding(start = 10.dp, top = DshSpace.s8, bottom = DshSpace.s4),
                    )
                }
                entries.forEach { entry ->
                    PaletteRow(
                        command = entry.command,
                        highlighted = filtering && entry.command == first,
                        onClick = { onPick(entry.command) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PaletteRow(command: PaletteCommand, highlighted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(DshRadius.container))
            .background(if (highlighted) Dsh.bgSubtle else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = DshSpace.s8, vertical = DshSpace.s6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(DshRadius.control))
                .background(if (highlighted) Dsh.brandTint else Dsh.bgSubtle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                paletteIcon(command),
                contentDescription = null,
                tint = if (highlighted) Dsh.brand500 else Dsh.labelSecondary,
                modifier = Modifier.size(DshIconSize.sm),
            )
        }
        Spacer(Modifier.width(DshSpace.s12))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(command.title, color = Dsh.labelPrimary, style = DshType.body, maxLines = 1)
                Spacer(Modifier.width(DshSpace.s6))
                Text(
                    command.trigger,
                    color = Dsh.labelTertiary,
                    style = DshType.captionRelaxed,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                command.description,
                color = Dsh.labelTertiary,
                style = DshType.captionRelaxed,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
    showActions: Boolean = false,
) {
    var expanded by remember(group.groupKey) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val first = group.items.first()
    val last = group.items.last()
    val totalDuration = if (first.time > 0 && last.time >= first.time) last.time - first.time else null
    val groupRunning = sweepingId != null && group.items.any { it.id == sweepingId }
    val rail = Dsh.borderStrong
    val summaryTitle = dev.deeplinks.native.util.toolGroupRowLabel(
        items = group.items,
        running = groupRunning,
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .heightIn(min = DshTouch.min)
                // C2：去掉灰底胶囊，只留一行小字；热区仍 48dp，按压显示极淡中性色
                .clickable(interactionSource = interaction, indication = dshRipple()) { expanded = !expanded }
                .semantics {
                    role = Role.Button
                    contentDescription = summaryTitle.ifBlank { L.executing }
                    stateDescription = if (expanded) L.collapse else L.expand
                }
                .padding(horizontal = DshSpace.s6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (groupRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(DshIconSize.xs),
                    // 进行中转圈用墨色（2026-09-28 重设计：品牌蓝只给需要你动手的动作）
                    color = Dsh.labelPrimary,
                    strokeWidth = 1.5.dp,
                )
                Spacer(Modifier.width(DshSpace.s8))
            } else {
                // 已结束用「带勾文档」（阶段 1 补的图标），与首页「最近」分组的完成态同一符号；
                // 原先的 CodeOutline16 只表示「这是代码」，不带状态语义
                Icon(
                    DocumentCheckOutline16,
                    contentDescription = null,
                    tint = Dsh.labelTertiary,
                    modifier = Modifier.size(DshIconSize.xs),
                )
                Spacer(Modifier.width(DshSpace.s8))
            }
            Text(
                summaryTitle,
                color = if (groupRunning) Dsh.labelSecondary else Dsh.labelTertiary,
                style = DshType.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(DshSpace.s8))
            if (groupRunning) {
                ShimmerLabel(text = L.executing.trimEnd('…', '.'), working = true)
                Spacer(Modifier.width(DshSpace.s8))
            } else if (totalDuration != null) {
                Text(
                    formatTraceDuration(totalDuration),
                    color = Dsh.labelTertiary,
                    style = DshType.caption.tabularNums(),
                )
                Spacer(Modifier.width(DshSpace.s6))
            }
            Icon(
                if (expanded) ChevronUpOutline16 else ChevronDownOutline16,
                // 展开状态已在行的 stateDescription 里播报，图标不再重复报一遍
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(DshIconSize.xs),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(motionDuration(200), easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(motionDuration(150))),
            exit = shrinkVertically(animationSpec = tween(motionDuration(180), easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(motionDuration(150))),
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 7.dp, top = DshSpace.s2)
                    .drawBehind {
                        val x = 3.5.dp.toPx()
                        drawLine(rail, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                    }
                    .padding(start = DshSpace.s16, top = DshSpace.s4, bottom = DshSpace.s4),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(DshSpace.s6)) {
                    group.items.forEach { item ->
                        MessageItem(
                            msg = item,
                            running = sweepingId != null && item.id == sweepingId,
                            onCopy = {},
                            onQuote = {},
                            onFork = {},
                            showActions = false,
                            isTurnEnd = showActions,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 会话顶栏：导航、会话名、对话/轨迹切换和溢出菜单。
 * 项目与连接状态在输入卡上方的上下文条（[ComposerContextStrip]），顶栏只放标题。
 * 菜单项由 [workspaceHeaderMenuItems] 构建后传入；设备入口在菜单与侧栏底部。
 */
@Composable
internal fun WorkspaceTopBar(
    running: Boolean,
    title: String,
    /** 第二行：工作区 · 电脑名，或执行中的「◌ 正在执行 · 第 12 步 · 3 分钟」（稿 03/10）。 */
    subtitle: String? = null,
    showBack: Boolean,
    onNavigate: () -> Unit,
    viewMode: String,
    showViewModeTabs: Boolean,
    onSelectViewMode: (String) -> Unit,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    menuItems: List<DshMenuItem>,
    /** 子智能体入口：>0 时在「⋯」按钮右上角显示品牌色圆点，菜单第一项也显示数量。 */
    subagentCount: Int = 0,
    onOpenSubagents: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 紧凑档 56dp（2026-10-01 R2）：高度与标题字阶收敛到 DshPageChrome 规格
                .heightIn(min = DshPageChromeDensity.Compact.minHeight)
                .padding(start = DshSpace.s4, end = DshSpace.s4, top = DshSpace.s2, bottom = DshSpace.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val navInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
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
                    modifier = Modifier.size(DshIconSize.md),
                )
            }

            // 两行标题：会话名（粗）+ 工作区·电脑名 / 执行中状态（稿 03/10）。
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = DshSpace.s8, vertical = DshSpace.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (running && subtitle.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Dsh.labelPrimary),
                    )
                    Spacer(Modifier.width(DshSpace.s6))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        color = Dsh.labelPrimary,
                        // 紧凑档标题 17/24（2026-10-01 R2）：DshType.titleLarge 自带 SemiBold
                        style = DshType.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            subtitle,
                            color = Dsh.labelSecondary,
                            style = DshType.captionRelaxed,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (showViewModeTabs) {
                val narrow = LocalConfiguration.current.screenWidthDp < 360
                DshSegmentedToggle(
                    labels = listOf(L.tabChat, L.tabTrace),
                    selectedIndex = if (viewMode == "trace") 1 else 0,
                    onSelect = { onSelectViewMode(if (it == 1) "trace" else "chat") },
                    icons = if (narrow) listOf(NewChatOutline16, ListPenOutline16) else null,
                    modifier = Modifier.padding(end = DshSpace.s4),
                )
            }

            if (menuItems.isNotEmpty()) {
                Box {
                    val moreInteraction = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(interactionSource = moreInteraction, indication = dshRipple()) { onMenuExpandedChange(true) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            EllipsisOutline16,
                            contentDescription = L.moreActions,
                            tint = Dsh.labelSecondary,
                            modifier = Modifier.size(DshIconSize.md),
                        )
                        if (subagentCount > 0) {
                            // 圆点贴在 20dp 图标右上角，而不是 48dp 触控区的角上
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .offset(x = DshSpace.s8, y = -DshSpace.s8)
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Dsh.brand400),
                            )
                        }
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
                    .padding(horizontal = DshSpace.s8),
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
        // 空会话只留白：起点是输入框占位句，不放标语和品牌标志
        ChatCanvasKind.Empty, ChatCanvasKind.Content -> item(key = "empty-hero") {
            Box(Modifier.fillParentMaxSize())
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
                .background(Dsh.bgCard),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                ChevronDownOutline16,
                contentDescription = null,
                tint = Dsh.labelPrimary,
                modifier = Modifier.size(DshIconSize.md),
            )
        }
        if (unread > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .defaultMinSize(minWidth = 18.dp)
                    .clip(CircleShape)
                    .background(Dsh.labelPrimary)
                    .padding(horizontal = 5.dp, vertical = DshSpace.s2),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (unread > 99) "99+" else unread.toString(),
                    color = Dsh.bgBase,
                    style = DshType.microRelaxed,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** 会话累计用量的一行摘要「7 轮 · 223 步 · 23.5M 令牌」；没有可显示的数时返回 null。 */
@Composable
internal fun sessionStatsSummary(stats: MobileSessionStats?): String? {
    val s = stats ?: return null
    val strings = DshS
    val totalTokens = s.uncachedInputTokens + s.cacheReadTokens + s.outputTokens
    return buildList {
        if (s.turns > 0 || s.steps > 0) add(strings.statsTurnsSteps.format(s.turns, s.steps))
        if (totalTokens > 0) add("${compactTokens(totalTokens)} ${strings.tokenUnitShort}")
    }.joinToString(" · ").ifEmpty { null }
}

/**
 * 输入卡上方的上下文条：离线提示 + 最近改动入口。
 *
 * - 离线时显示「电脑离线」；
 * - 有改动时显示改动数量标签。
 * - 原先的会话用量统计、工作区名、浏览文件入口均已移除。
 */
@Composable
internal fun ComposerContextStrip(
    online: Boolean,
    changes: WorkspaceChangesSummary?,
    onOpenChanges: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showOffline = !online
    val showChanges = changes != null && changes.total > 0
    if (!showOffline && !showChanges) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpace.s16, vertical = DshSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showOffline) {
            HostStatusDot(false)
            Spacer(Modifier.width(DshSpace.s6))
            Text(
                text = L.hostOffline,
                color = Dsh.labelSecondary,
                style = DshType.label,
                maxLines = 1,
            )
        }
        Spacer(Modifier.weight(1f))
        if (showChanges) {
            DshTag(
                text = ChangesL.viewChanges.format(changes.total),
                modifier = Modifier.clickable(role = Role.Button, onClick = onOpenChanges),
            )
        }
    }
}

/**
 * 会话用量看板：紧凑对话框——三个关键数一行、上下文一条进度、令牌构成一行小字。
 * 精确到个位的明细不再逐行列出（概览已经够用，整屏看板反而难读）。
 */
@Composable
internal fun SessionStatsDetailDialog(
    stats: MobileSessionStats,
    onDismiss: () -> Unit,
) {
    val strings = DshS
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 与 DshDialogFrame 同一套弹层外壳：modal 28dp + bgCard + 阴影（真浮层）
        Column(
            modifier = Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth(0.9f)
                .shadow(16.dp, RoundedCornerShape(DshRadius.modal), ambientColor = Dsh.shadowCard, spotColor = Dsh.shadowCard)
                .clip(RoundedCornerShape(DshRadius.modal))
                .background(Dsh.bgCard)
                .padding(start = DshSpace.s20, end = DshSpace.s8, top = DshSpace.s8, bottom = DshSpace.s20),
        ) {
            DshSheetHeader(
                title = strings.sessionStatsSheetTitle,
                subtitle = strings.statsTurnsSteps.format(stats.turns, stats.steps),
                onClose = onDismiss,
            )
            Column(Modifier.padding(end = DshSpace.s12)) {
                StatsDetailSections(stats)
            }
        }
    }
}

@Composable
private fun StatsDetailSections(s: MobileSessionStats) {
    val strings = DshS
    val inputTokens = s.uncachedInputTokens + s.cacheReadTokens
    val totalTokens = inputTokens + s.outputTokens
    val cacheHitPercent = if (inputTokens > 0) ((s.cacheReadTokens * 100) / inputTokens).toInt() else null
    val speed = if (s.decodeMs > 0 && s.decodeTokens > 0) s.decodeTokens * 1000.0 / s.decodeMs else null

    Spacer(Modifier.height(DshSpace.s12))
    // 三个关键数：一张 tonal 卡片里三等分，不再各占一张卡
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.container))
            .background(Dsh.bgSubtle)
            .padding(vertical = 14.dp),
    ) {
        StatsFigure(compactTokens(totalTokens), strings.statsTotalTokens, Modifier.weight(1f))
        StatsFigure(cacheHitPercent?.let { "$it%" } ?: "—", strings.statsCacheHitLabel, Modifier.weight(1f))
        StatsFigure(
            speed?.let { String.format(java.util.Locale.US, "%.0f", it) } ?: "—",
            strings.tokenRateUnit,
            Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(10.dp))
    Text(
        strings.statsCompositionLine.format(
            compactTokens(s.uncachedInputTokens),
            compactTokens(s.cacheReadTokens),
            compactTokens(s.outputTokens),
        ),
        color = Dsh.labelTertiary,
        style = DshType.captionRelaxed.tabularNums(),
        modifier = Modifier.padding(horizontal = DshSpace.s4),
    )

    if (s.contextWindow > 0) {
        val used = s.contextPressureTokens
        val percent = ((used * 100) / s.contextWindow).toInt().coerceIn(0, 100)
        val tight = percent > 80
        Spacer(Modifier.height(DshSpace.s20))
        Row(Modifier.padding(horizontal = DshSpace.s4), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.statsContextWindow, color = Dsh.labelSecondary, style = DshType.titleSmall, modifier = Modifier.weight(1f))
            Text(
                "${compactTokens(used)} / ${compactTokens(s.contextWindow)} · $percent%",
                color = if (tight) Dsh.warn else Dsh.labelSecondary,
                style = DshType.captionRelaxed.tabularNums(),
            )
        }
        Spacer(Modifier.height(DshSpace.s8))
        Box(
            modifier = Modifier
                .padding(horizontal = DshSpace.s4)
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(DshRadius.full))
                .background(Dsh.bgTrack),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percent / 100f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(DshRadius.full))
                    .background(if (tight) Dsh.warn else Dsh.brand500),
            )
        }
        if (s.systemTokens + s.toolsTokens + s.messageTokens > 0) {
            Spacer(Modifier.height(DshSpace.s8))
            Text(
                strings.statsContextBreakdownLine.format(
                    compactTokens(s.systemTokens),
                    compactTokens(s.toolsTokens),
                    compactTokens(s.messageTokens),
                ),
                color = Dsh.labelTertiary,
                style = DshType.captionRelaxed.tabularNums(),
                modifier = Modifier.padding(horizontal = DshSpace.s4),
            )
        }
    }
}

/** 看板里的一个关键数：数值在上、标签在下，居中。 */
@Composable
private fun StatsFigure(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Dsh.labelPrimary, style = DshType.titleLarge.tabularNums(), maxLines = 1)
        Spacer(Modifier.height(DshSpace.s2))
        Text(label, color = Dsh.labelTertiary, style = DshType.microRelaxed, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
