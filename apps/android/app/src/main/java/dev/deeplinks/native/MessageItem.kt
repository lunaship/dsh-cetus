package dev.deeplinks.native

import dev.deeplinks.native.DshIconSize
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import dev.deeplinks.core.DshType

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.dshRipple
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.L
import dev.deeplinks.native.ui.DshTag
import dev.deeplinks.native.util.answerMetaSummary
import dev.deeplinks.native.util.buildAnswerMeta
import dev.deeplinks.native.util.formatClockTime
import dev.deeplinks.native.util.modelChangedFrom
import dev.deeplinks.native.util.loadOlderKind
import dev.deeplinks.native.util.LoadOlderKind
import dev.deeplinks.native.util.contextInjectionLabels
import dev.deeplinks.native.util.decodeHtmlEntities
import dev.deeplinks.native.util.goalRoundObjective
import dev.deeplinks.native.util.goalRoundProgress
import dev.deeplinks.native.util.MessageKind
import dev.deeplinks.native.util.resolvedMessageKind
import org.json.JSONObject

// ---------- 消息渲染 ----------

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MessageItem(
    msg: MobileMessage,
    running: Boolean = false,
    onAnswerApproval: ((String, String, (Boolean) -> Unit) -> Unit)? = null,
    onAnswerQuestion: ((String, org.json.JSONObject, (Boolean) -> Unit) -> Unit)? = null,
    onCopy: () -> Unit = {},
    onQuote: () -> Unit = {},
    onFork: () -> Unit = {},
    onRegenerate: (() -> Unit)? = null,
    onFeedback: (() -> Unit)? = null,
    feedbackRating: String? = null,
    onRate: ((String) -> Unit)? = null,
    onRetract: (() -> Unit)? = null,
    onFetchProducedFile: ((String) -> Pair<String, ByteArray>)? = null,
    onOpenChanges: ((seq: Long, fileIndex: Int?) -> Unit)? = null,
    /** 复制 / 赞踩 / 时间一行：只在一轮的最后一条回复上显示。 */
    showActions: Boolean = true,
    /** 本轮发生过模型切换时，轮尾元信息行前缀的模型名（不伪造逐条模型字段）。 */
    turnModelLabel: String? = null,
    /** 本条是否是一轮末尾：只控制操作行显示；长按菜单对所有已完成文本消息开放。 */
    isTurnEnd: Boolean = false,
    /** 长回复拆行后本行渲染的 Markdown 片段；null 表示整条。菜单 / 复制始终针对整条消息。 */
    textPart: String? = null,
    /** 拆行时只有最后一段带流式光标与操作行。 */
    isLastPart: Boolean = true,
    /** 本轮的改动摘要（v4 轮尾第一项）：由消息流挪到轮末回复下面画，不再单独成行。 */
    turnChanges: MobileMessage? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var selectOpen by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val dshHaptic = rememberDshHaptic()
    val context = androidx.compose.ui.platform.LocalContext.current
    // 已完成的文本消息：长按出菜单（复制 / 分支 / 选择文字）。操作行只挂在轮末，
    // 所以轮中的助手回复必须靠长按才能复制——这里不能再按 isTurnEnd 收窄。
    val canSelectText = (msg.role == "user" || msg.role == "assistant") &&
        msg.running != true && !running && msg.text.isNotBlank()
    val longPressModifier = if (canSelectText) {
        Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
            onLongClick = {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                menuOpen = true
            },
        )
    } else {
        Modifier
    }

    val kind = remember(msg.role, msg.kind, msg.text) { resolvedMessageKind(msg.role, msg.kind, msg.text) }
    Box {
        when {
            kind == MessageKind.GOAL_ROUND -> GoalRoundRow(msg)
            kind == MessageKind.INJECTION -> ContextInjectionRow(msg.text)
            // 模型切换提示：安静的居中一行（既不是用户气泡，也不是可展开的上下文注入）
            kind == MessageKind.MODEL_CHANGED -> SystemNoticeRow(msg.text)
            msg.role == "user" -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.End,
                ) {
                    UserBubble(msg.text, longPressModifier)
                }
            }
            msg.role == "reasoning" -> ReasoningRow(msg.text, running || msg.running == true, msg.durationMs)
            msg.role == "approval" -> ApprovalCard(
                msg = msg,
                onAnswer = { approvalId, outcome, onDone ->
                    onAnswerApproval?.invoke(approvalId, outcome, onDone) ?: onDone(false)
                }
            )
            msg.role == "question" -> QuestionCard(
                msg = msg,
                onAnswer = { rpcId, answer, onDone ->
                    onAnswerQuestion?.invoke(rpcId, answer, onDone) ?: onDone(false)
                }
            )
            msg.role == "tool_call" -> CommandCard(msg.toolName ?: L.toolCallRole, msg.toolArgs, running)
            msg.role == "tool_result" -> CommandCard(
                title = L.executionResultRole + (msg.durationMs?.let { " · ${formatTraceDuration(it)}" } ?: ""),
                body = msg.text.take(4000),
                running = running,
                runningLabel = L.executing
            )
            msg.role == "compaction" -> CompactionRow(msg.text, msg.running ?: false)
            msg.role == "todo" -> TodoPanel(msg.todos)
            msg.role == "goal" -> GoalPanel(msg.text, msg.goalSummary)
            msg.role == ROLE_WORKSPACE_CHANGES && msg.changes != null -> WorkspaceChangesCard(
                summary = msg.changes,
                onOpen = { fileIndex -> onOpenChanges?.invoke(msg.changes.seq, fileIndex) },
            )
            msg.role == "produced_files" -> ProducedFilesRow(
                files = msg.files.ifEmpty { msg.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList() },
                onFetchFile = onFetchProducedFile,
            )
            else -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (isRawFallback(msg)) {
                        // 降级中心：无文本内容的未知消息类型（多为 dsh 新增的结构化类型）
                        // 渲染为可折叠原始 JSON，避免空白/崩溃，便于排查与按需补洞。
                        RawMessageCard(msg)
                    } else {
                        AssistantMarkdown(
                            text = textPart ?: msg.text,
                            longPress = longPressModifier,
                            streaming = msg.running == true && isLastPart,
                        )
                    }
                    // v4 4.2 轮尾：改动卡 → 元信息灰字 → 文字按钮（复制 / 重新生成 / 分享 / ⋯）
                    if (msg.role == "assistant" && showActions && isTurnEnd && isLastPart) {
                        val changes = turnChanges?.changes
                        if (changes != null && msg.running != true) {
                            Box(Modifier.padding(top = DshSpace.s12)) {
                                WorkspaceChangesCard(summary = changes, onOpen = { i -> onOpenChanges?.invoke(changes.seq, i) })
                            }
                        }
                        AnimatedVisibility(
                            visible = msg.running != true,
                            enter = fadeIn(animationSpec = tween(motionDuration(400))),
                            exit = fadeOut(animationSpec = tween(motionDuration(150))),
                        ) {
                            TurnEndTail(
                                meta = turnEndMeta(msg, turnModelLabel),
                                onCopy = {
                                    dshHaptic(DshHaptic.Confirm)
                                    onCopy()
                                },
                                onRegenerate = onRegenerate,
                                onShare = { ShareIntents.shareText(context, msg.text, L.shareMessage) },
                                onMore = { menuOpen = true },
                            )
                        }
                    }
                }
            }
        }

        DshMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            items = buildList {
                add(DshMenuItem(CopyOutline16, L.copy) {
                    menuOpen = false
                    dshHaptic(DshHaptic.Confirm)
                    onCopy()
                })
                add(DshMenuItem(FontOutline16, L.selectText) {
                    menuOpen = false
                    selectOpen = true
                })
                add(DshMenuItem(ShareOutline16, L.shareMessage) {
                    menuOpen = false
                    ShareIntents.shareText(context, msg.text, L.shareMessage)
                })
                add(DshMenuItem(QuoteOutline16, L.quote) {
                    menuOpen = false
                    onQuote()
                })
                add(DshMenuItem(BranchOutline16, L.forkSession) {
                    menuOpen = false
                    onFork()
                })
                if (onRegenerate != null) {
                    add(DshMenuItem(RefreshOutline16, L.regenerate) {
                        menuOpen = false
                        onRegenerate()
                    })
                }
                if (onRate != null) {
                    val positive = feedbackRating == "positive"
                    add(DshMenuItem(
                        if (positive) LikeFill16 else LikeOutline16,
                        if (positive) L.feedbackRetract else L.feedbackLike,
                    ) {
                        menuOpen = false
                        if (positive) onRetract?.invoke() else onRate("positive")
                    })
                    val negative = feedbackRating == "negative"
                    add(DshMenuItem(
                        if (negative) DislikeFill16 else DislikeOutline16,
                        if (negative) L.feedbackRetract else L.feedbackDislike,
                    ) {
                        menuOpen = false
                        if (negative) onRetract?.invoke() else onRate("negative")
                    })
                }
                if (onFeedback != null) {
                    add(DshMenuItem(FeedbackOutline16, L.messageFeedback) {
                        menuOpen = false
                        onFeedback()
                    })
                }
            }
        )
        if (selectOpen) {
            SelectTextDialog(text = msg.text, onDismiss = { selectOpen = false })
        }
    }
}

@Composable
private fun turnEndMeta(msg: MobileMessage, turnModelLabel: String?): String {
    val meta = remember(msg.durationMs) { buildAnswerMeta(msg.durationMs) }
    return listOfNotNull(
        turnModelLabel?.takeIf { it.isNotBlank() },
        formatClockTime(msg.time).takeIf { it.isNotBlank() },
        meta?.let { answerMetaSummary(it) }?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
}

/** 轮尾元信息 + 文字按钮（4.2）：灰字一行，下面是复制 / 重新生成 / 分享，其余收进 ⋯。 */
@Composable
private fun TurnEndTail(
    meta: String,
    onCopy: () -> Unit,
    onRegenerate: (() -> Unit)?,
    onShare: () -> Unit,
    onMore: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = DshSpace.s8)) {
        if (meta.isNotBlank()) {
            Text(
                meta,
                color = Dsh.labelSecondary,
                style = DshType.supporting,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TurnTextButton(CopyOutline16, L.copy, onCopy)
            if (onRegenerate != null) TurnTextButton(RefreshOutline16, L.regenerate, onRegenerate)
            TurnTextButton(ShareOutline16, L.shareMessage, onShare)
            Spacer(Modifier.weight(1f))
            TurnTextButton(EllipsisOutline16, null, onMore, contentDescription = L.moreActions)
        }
    }
}

@Composable
private fun TurnTextButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String?,
    onClick: () -> Unit,
    contentDescription: String? = null,
) {
    Row(
        modifier = Modifier
            .heightIn(min = DshTouch.min)
            .clip(RoundedCornerShape(DshRadius.control))
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .padding(horizontal = DshSpace.s8),
        horizontalArrangement = Arrangement.spacedBy(DshSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Dsh.labelSecondary, modifier = Modifier.size(DshIconSize.sm))
        if (label != null) Text(label, color = Dsh.labelSecondary, style = DshType.supporting, maxLines = 1)
    }
}

/** 降级判定：无文本内容的消息（多为 dsh 新增的结构化类型）渲染为可折叠原始 JSON。 */
fun isRawFallback(msg: MobileMessage): Boolean =
    msg.role != "produced_files" && msg.role != ROLE_WORKSPACE_CHANGES && msg.text.isBlank()

// 降级渲染：未知/未支持的消息类型 → 可折叠原始 JSON（不崩、不空白、可排查）
@Composable
private fun RawMessageCard(msg: MobileMessage) {
    var expanded by remember { mutableStateOf(false) }
    val detail = remember(msg) {
        buildString {
            append("role: ").append(msg.role).append('\n')
            append("type: ").append(msg.type).append('\n')
            append("id: ").append(msg.id).append('\n')
            if (msg.toolName != null) append("toolName: ").append(msg.toolName).append('\n')
            append("text: ").append(msg.text.take(2000))
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.container))
            .background(Dsh.surface1)
            .padding(DshSpace.s12)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = L.unsupportedMessageType.format(msg.role)
                }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = dshRipple()) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (expanded) ChevronDownOutline16 else ChevronRightOutline16,
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(DshIconSize.xs)
            )
            Spacer(Modifier.width(DshSpace.s8))
            Text(
                L.unsupportedMessageType.format(msg.role),
                color = Dsh.labelSecondary,
                style = DshType.body,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(DshSpace.s8))
            Text(
                detail,
                fontFamily = FontFamily.Monospace,
                style = DshType.caption,
                color = Dsh.labelTertiary,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// 上下文压缩行（DSH CompactionRow：折叠标题 + 可展开摘要）
@Composable
private fun CompactionRow(summary: String, running: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(DshRadius.control))
            .semantics {
                role = Role.Button
                stateDescription = if (expanded) L.collapse else L.expand
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = dshRipple()) { expanded = !expanded }
            .padding(vertical = DshSpace.s4),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 16px leading 图标（上下文图标）
        Box(
            modifier = Modifier
                .size(16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (running) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Dsh.labelTertiary)
                )
            } else {
                Icon(
                    CompressOutline16,
                    contentDescription = null,
                    tint = Dsh.labelSecondary,
                    modifier = Modifier.size(DshIconSize.xs)
                )
            }
        }
        Spacer(Modifier.width(DshSpace.s8))
        Text(
            if (running) L.compressing else L.contextCompressed,
            color = Dsh.labelSecondary,
            style = DshType.supporting,
        )
        if (!running) {
            // 分隔点：padding 在 size 外面（原来写在固定 2dp 盒内不生效，只剩一粒贴字的小点）
            Box(
                modifier = Modifier
                    .padding(horizontal = DshSpace.s8)
                    .size(3.dp)
                    .clip(CircleShape)
                    .background(Dsh.labelTertiary)
            )
            Text(
                summary.lineSequence().firstOrNull().orEmpty(),
                color = Dsh.labelSecondary,
                style = DshType.supporting,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        if (!running) {
            Icon(
                if (expanded) ChevronUpOutline16 else ChevronDownOutline16,
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(DshIconSize.sm)
            )
        }
    }
    AnimatedVisibility(
        visible = expanded && summary.isNotEmpty(),
        enter = expandVertically(animationSpec = tween(motionDuration(200), easing = FastOutSlowInEasing)),
        exit = shrinkVertically(animationSpec = tween(motionDuration(150), easing = FastOutSlowInEasing))
    ) {
        Text(
            summary,
            color = Dsh.labelSecondary,
            style = DshType.supporting,
            modifier = Modifier.padding(start = DshSpace.s24, top = DshSpace.s4, bottom = DshSpace.s4)
        )
    }
}

// goal 模式每轮注入的续跑提示：折叠为一行「目标轮次」，展开看 Objective 与轮次，不铺开整段系统指令
@Composable
private fun GoalRoundRow(msg: MobileMessage) {
    val text = msg.text
    var expanded by remember { mutableStateOf(false) }
    val objective = remember(msg) { msg.goalObjective ?: goalRoundObjective(text) }
    val progress = remember(msg) {
        msg.goalRound?.let { r -> msg.goalMaxRounds?.let { "$r/$it" } ?: "$r" } ?: goalRoundProgress(text)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.container))
            .semantics {
                role = Role.Button
                stateDescription = if (expanded) L.collapse else L.expand
            }
            .clickable { expanded = !expanded }
            .padding(horizontal = DshSpace.s4, vertical = DshSpace.s4)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                GoalOutline16,
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(DshIconSize.xs)
            )
            Spacer(Modifier.width(DshSpace.s8))
            Text(
                L.goalInjection,
                color = Dsh.labelTertiary,
                style = DshType.captionMedium,
                fontWeight = FontWeight(500),
            )
            progress?.let {
                Text(
                    " · " + L.goalRoundLabel.format(it),
                    color = Dsh.labelTertiary,
                    style = DshType.captionRelaxed,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) ChevronUpOutline16 else ChevronDownOutline16,
                contentDescription = if (expanded) L.collapseInjectionContent else L.expandInjectionContent,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(DshIconSize.sm)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(motionDuration(200), easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(motionDuration(150))),
            exit = shrinkVertically(animationSpec = tween(motionDuration(180), easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(motionDuration(150)))
        ) {
            Text(
                objective ?: text.trim(),
                color = Dsh.labelTertiary,
                style = DshType.captionRelaxed,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = DshSpace.s8)
                    .clip(RoundedCornerShape(DshRadius.container))
                    .background(Dsh.surface1)
                    .padding(horizontal = DshSpace.s12, vertical = DshSpace.s8)
            )
        }
    }
}

/**
 * 系统提示行（模型切换等）：居中、次要色、不可点——它只是告知，不需要用户回应，
 * 也不该长得像用户说过的话。文字是 DSH 自己生成的英文标记，原样显示。
 */
@Composable
private fun SystemNoticeRow(text: String) {
    // E2：模型切换提示原先原样显示 DSH 注入的英文系统文本
    // （`[model changed: … generated by step-3.7-flash]`），用户读不懂。
    // 改成本地化分隔线：左右两段 1dp 细线，中间写「以上回复由 <model> 生成」。
    // 取不到模型名时退回「模型已切换」，仍不暴露英文原文。
    val model = remember(text) { modelChangedFrom(text) }
    val label = model?.let { L.modelChangedFrom.format(it) } ?: L.modelChanged
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpace.s24, vertical = DshSpace.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(Dsh.borderSubtle),
        )
        Text(
            text = label,
            color = Dsh.labelTertiary,
            style = DshType.supporting,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = DshSpace.s8),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(Dsh.borderSubtle),
        )
    }
}

@Composable
private fun ContextInjectionRow(text: String) {
    var expanded by remember { mutableStateOf(false) }
    val sources = remember(text) { contextInjectionLabels(text) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.container))
            .semantics {
                role = Role.Button
                stateDescription = if (expanded) L.collapse else L.expand
            }
            .clickable { expanded = !expanded }
            .padding(horizontal = DshSpace.s4, vertical = DshSpace.s4)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                FileOutline16,
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(DshIconSize.xs)
            )
            Spacer(Modifier.width(DshSpace.s8))
            Text(
                L.contextInjection,
                color = Dsh.labelTertiary,
                style = DshType.captionMedium,
                fontWeight = FontWeight(500),
            )
            Text(
                " · ",
                color = Dsh.labelTertiary,
                style = DshType.captionRelaxed,
            )
            Text(
                sources.joinToString(", "),
                color = Dsh.labelTertiary,
                style = DshType.captionRelaxed,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) ChevronUpOutline16 else ChevronDownOutline16,
                contentDescription = if (expanded) L.collapseInjectionContent else L.expandInjectionContent,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(DshIconSize.sm)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(motionDuration(200), easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(motionDuration(150))),
            exit = shrinkVertically(animationSpec = tween(motionDuration(180), easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(motionDuration(150)))
        ) {
            Text(
                text.replace("<system-reminder>", "", ignoreCase = true)
                    .replace("</system-reminder>", "", ignoreCase = true)
                    .replace("&lt;system-reminder&gt;", "", ignoreCase = true)
                    .replace("&lt;/system-reminder&gt;", "", ignoreCase = true)
                    .trim(),
                color = Dsh.labelTertiary,
                style = DshType.captionRelaxed,
                maxLines = 16,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = DshSpace.s8)
                    .clip(RoundedCornerShape(DshRadius.container))
                    .background(Dsh.surface1)
                    .padding(horizontal = DshSpace.s12, vertical = DshSpace.s8)
            )
        }
    }
}

// 任务清单（DSH TodoPanel：标题 + 进度 + 可展开列表）
@Composable
private fun TodoPanel(todos: List<MobileTodoItem>) {
    var expanded by remember { mutableStateOf(true) }
    val done = todos.count { it.status == "completed" }
    val active = todos.count { it.status == "active" || it.status == "progress" || it.status == "in_progress" }
    val pending = todos.count { it.status == "pending" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.block))
            .background(Dsh.surface1)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics {
                    role = Role.Button
                    stateDescription = if (expanded) L.collapse else L.expand
                }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = dshRipple()) { expanded = !expanded }
                .padding(horizontal = DshSpace.s12, vertical = DshSpace.s8),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                ChecklistOutline16,
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(DshIconSize.sm)
            )
            Spacer(Modifier.width(DshSpace.s12))
            Text(
                L.tasks,
                color = Dsh.labelPrimary,
                style = DshType.bodyStrong,
            )
            Spacer(Modifier.width(DshSpace.s12))
            Text(
                L.todoCompleted.format(done, todos.size),
                color = Dsh.labelTertiary,
                style = DshType.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) ChevronUpOutline16 else ChevronDownOutline16,
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(DshIconSize.sm)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(motionDuration(220), easing = FastOutSlowInEasing)),
            exit = shrinkVertically(animationSpec = tween(motionDuration(180), easing = FastOutSlowInEasing))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = DshSpace.s12, vertical = DshSpace.s4),
                verticalArrangement = Arrangement.spacedBy(DshSpace.s8)
            ) {
                todos.forEach { todo ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TodoGlyph(todo.status)
                        Spacer(Modifier.width(DshSpace.s12))
                        Text(
                            todo.content,
                            color = Dsh.labelSecondary,
                            style = DshType.body,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoGlyph(status: String) {
    Box(
        modifier = Modifier.size(16.dp),
        contentAlignment = Alignment.Center
    ) {
        when (status) {
            "completed", "done" -> Icon(
                CheckOutline16,
                contentDescription = null,
                tint = Dsh.success,
                modifier = Modifier.size(DshIconSize.sm)
            )
            "active", "progress", "in_progress", "running" -> {
                val angle = rememberMotionSpin(750, label = "todo-spin")
                Icon(
                    RefreshOutline16,
                    contentDescription = null,
                    tint = Dsh.labelSecondary,
                    modifier = Modifier.size(DshIconSize.sm).rotate(angle ?: 0f)
                )
            }
            else -> Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, Dsh.labelTertiary, CircleShape)
            )
        }
    }
}

// ─── Goal 面板（紧凑 inline 卡片，DSH goal/write 事件的展示层） ─────────────

/**
 * Goal 面板：紧凑行式卡片，展示 agent 正在执行的目标文本。
 * 生产打磨参考：hermes-mobile #943 TaskProgressChip 的同源设计（小而持续可见）。
 */
@Composable
private fun GoalPanel(text: String, goalSummary: String? = null) {
    val displayText = goalSummary?.ifBlank { text } ?: text
    if (displayText.isBlank()) return
    val expanded = remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.block))
            .background(Dsh.surface1)
            .padding(horizontal = DshSpace.s12, vertical = DshSpace.s8),
        verticalArrangement = Arrangement.spacedBy(DshSpace.s4),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DshSpace.s8),
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
            Spacer(Modifier.width(DshSpace.s4))
            Text(
                text = if (expanded.value || displayText.length <= 60) displayText
                else displayText.take(57) + "…",
                color = Dsh.labelPrimary,
                style = DshType.body,
                maxLines = if (expanded.value) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 展开/折叠按钮
            Text(
                text = if (expanded.value) L.collapse else L.expand,
                color = Dsh.labelSecondary,
                style = DshType.microMedium,
                modifier = Modifier.clickable { expanded.value = !expanded.value },
            )
        }
    }
}

// 加载更早（DSH chat.loadOlder）：居中的一行灰字按钮，不另铺色块
@Composable
internal fun LoadOlderRow(
    loading: Boolean,
    failed: Boolean = false,
    failedMessage: String? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val kind = loadOlderKind(loading, failed)
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(DshRadius.container))
                .clickable(enabled = !loading, interactionSource = interaction, indication = dshRipple(), onClick = onClick)
                .padding(horizontal = DshSpace.s12),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when (kind) {
                    LoadOlderKind.Loading -> L.loadHistory
                    LoadOlderKind.Failed -> failedMessage?.takeIf { it.isNotBlank() } ?: L.loadOlderRetry
                    LoadOlderKind.Idle -> L.loadOlder
                },
                color = if (kind == LoadOlderKind.Failed) Dsh.error else Dsh.labelSecondary,
                style = DshType.captionRelaxed,
            )
        }
    }
}

// 已停止标记（按 turn/end reason 显示具体原因）
internal fun stoppedReasonLabel(reason: String): String = when (reason.lowercase()) {
    "interrupted" -> L.interrupted
    "stopped" -> L.stopped
    "error" -> L.errorStopped
    "maxtokens", "max_tokens" -> L.maxTokensReached
    "aborted" -> L.cancelled
    "timeout" -> L.timeoutStopped
    else -> if (reason.isBlank()) L.stopped else reason
}

@Composable
internal fun StoppedBadge(reason: String) {
    val label = stoppedReasonLabel(reason)
    if (label.isBlank() || label.equals("null", ignoreCase = true)) return
    Row(modifier = Modifier.fillMaxWidth()) {
        DshTag(
            text = label,
            color = Dsh.pressed,
            contentColor = Dsh.labelTertiary,
            contentDescription = label,
        )
    }
}

// 思考行：四角星 + 扫光标题，正文默认收起，仅用户点击后展开

@Composable
private fun ReasoningRow(text: String, running: Boolean = false, durationMs: Long? = null) {
    var expanded by remember { mutableStateOf(false) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var settledSec by remember { mutableStateOf<Long?>(null) }
    var lastRunning by remember { mutableStateOf(false) }
    LaunchedEffect(running, durationMs) {
        // 复用的行会跨 turn 存活：running 上升沿（新 turn 开始）必须重置计时，
        // 否则「思考中」从首个 turn 起累计。
        if (running && !lastRunning) {
            startedAt = System.currentTimeMillis()
            settledSec = null
        }
        lastRunning = running
        if (durationMs != null && durationMs > 0) {
            settledSec = (durationMs / 1000L).coerceAtLeast(1L)
        } else if (!running && settledSec == null && startedAt > 0L) {
            settledSec = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(1L)
        }
    }
    ThinkingTrace(
        working = running,
        activeLabel = L.thinkingActive,
        doneLabel = thoughtDoneLabel(durationMs, settledSec),
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        Text(text, color = Dsh.labelSecondary, style = DshType.supporting)
    }
}

// 用户消息：右对齐气泡（max-width min(525px,82%), 宽度随内容收缩，勿 fillMaxWidth）
@Composable
private fun UserBubble(text: String, longPress: Modifier = Modifier) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxBubble = minOf(maxWidth * 0.82f, 525.dp)
        Box(
            modifier = longPress
                .align(Alignment.CenterEnd)
                .widthIn(max = maxBubble)
                .clip(RoundedCornerShape(DshRadius.block))
                // v4 4.1：用户消息右侧浅灰气泡（容器色）
                .background(Dsh.surface1)
                .padding(horizontal = DshSpace.s16, vertical = DshSpace.s12)
        ) {
            Text(
                text.trimEnd(),
                color = Dsh.labelPrimary,
                style = DshType.body,
            )
        }
    }
}

@Composable
private fun AssistantMarkdown(text: String, longPress: Modifier = Modifier, streaming: Boolean = false) {
    Column(
        modifier = longPress.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DshSpace.s12)
    ) {
        MarkdownContent(decodeHtmlEntities(text), streaming = streaming)
    }
}

// 命令行（tool call / result）：轻量可展开行，去掉厚底卡片
@Composable
private fun CommandCard(title: String, body: String?, running: Boolean = false, runningLabel: String = L.executing) {
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val rail = Dsh.borderStrong
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .heightIn(min = DshTouch.min)
                .clip(RoundedCornerShape(DshRadius.control))
                .clickable(interactionSource = interaction, indication = dshRipple()) { expanded = !expanded }
                .padding(horizontal = DshSpace.s8, vertical = DshSpace.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(DshIconSize.xs),
                    color = Dsh.brand400,
                    trackColor = Dsh.primarySoft,
                    strokeWidth = 1.5.dp,
                )
                Spacer(Modifier.width(DshSpace.s8))
            } else {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(Dsh.labelTertiary),
                )
                Spacer(Modifier.width(DshSpace.s8))
            }
            Text(
                title,
                color = Dsh.labelSecondary,
                style = DshType.supporting,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (running) {
                Spacer(Modifier.width(DshSpace.s8))
                ShimmerLabel(text = runningLabel.trimEnd('…', '.', '。'), working = true)
                Spacer(Modifier.width(DshSpace.s8))
            }
            if (!body.isNullOrBlank()) {
                Icon(
                    if (expanded) ChevronUpOutline16 else ChevronDownOutline16,
                    contentDescription = if (expanded) L.collapse else L.expand,
                    tint = Dsh.labelTertiary,
                    modifier = Modifier.size(DshIconSize.sm),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded && !body.isNullOrBlank(),
            enter = expandVertically(animationSpec = tween(motionDuration(200), easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(motionDuration(150))),
            exit = shrinkVertically(animationSpec = tween(motionDuration(180), easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(motionDuration(150))),
        ) {
            Box(
                modifier = Modifier
                    .padding(start = DshSpace.s8, top = DshSpace.s4)
                    .drawBehind {
                        val x = 3.5.dp.toPx()
                        drawLine(rail, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                    }
                    .padding(start = DshSpace.s16, top = DshSpace.s4, bottom = DshSpace.s4),
            ) {
                Text(
                    body.orEmpty(),
                    color = Dsh.labelPrimary,
                    fontFamily = FontFamily.Monospace,
                    style = DshType.captionRelaxed,
                    maxLines = 16,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(DshRadius.container))
                        .background(Dsh.surface1)
                        .padding(horizontal = DshSpace.s12, vertical = DshSpace.s8),
                )
            }
        }
    }
}
