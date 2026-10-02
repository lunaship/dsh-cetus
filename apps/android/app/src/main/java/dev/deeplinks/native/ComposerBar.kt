package dev.deeplinks.native

import dev.deeplinks.native.DshIconSize
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import dev.deeplinks.core.DshType

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.deeplinks.core.dshRipple
import dev.deeplinks.native.ui.DshFilterChip
import dev.deeplinks.native.ui.v4.DlComposer
import dev.deeplinks.native.ui.v4.DlSendState
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.L

// ---------- 输入卡（InputBar 1:1） ----------

/**
 * K3 聚焦令牌：只有输入框所在的 [InputBar] 在组合里时才会执行——从结构上消除
 * 「FocusRequester is not initialized」崩溃。`awaitFrame` 等这一帧布局完成、`FocusRequester`
 * 已挂上；没挂上就静默放过，不抛异常。
 */
@Composable
private fun ComposerFocusEffect(
    focusToken: Int,
    focusRequester: androidx.compose.ui.focus.FocusRequester?,
) {
    if (focusRequester == null) return
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    androidx.compose.runtime.LaunchedEffect(focusToken) {
        if (focusToken == 0) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        if (runCatching { focusRequester.requestFocus() }.isSuccess) keyboard?.show()
    }
}

@Composable
internal fun InputBar(
    modifier: Modifier = Modifier,
    inputText: String,
    onInputChange: (String) -> Unit,
    pendingImages: List<Pair<String, String>> = emptyList(),
    onRemoveImage: (Int) -> Unit = {},
    onPickImage: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    isListening: Boolean,
    isSending: Boolean,
    canSend: Boolean,
    running: Boolean,
    modelName: String?,
    modelEffort: String?,
    sessionStats: MobileSessionStats?,
    onOpenUsage: (() -> Unit)? = null,
    permissionPreset: String,
    permissionLabel: String,
    compact: Boolean = false,
    onOpenModelPicker: () -> Unit,
    onOpenPermissionPicker: () -> Unit,
    onToggleVoice: () -> Unit,
    onStop: () -> Unit,
    onSend: () -> Unit,
    actionError: String? = null,
    composerFocusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    focusToken: Int = 0,
) {
    ComposerFocusEffect(focusToken, composerFocusRequester)
    var composerFocused by remember { mutableStateOf(false) }
    // 录音只在设备真的有语音识别服务时出现（不可用的能力不占位）
    val voiceContext = androidx.compose.ui.platform.LocalContext.current
    val voiceAvailable = remember(voiceContext) {
        runCatching { android.speech.SpeechRecognizer.isRecognitionAvailable(voiceContext) }
            .getOrDefault(false)
    }
    val composerIdle = inputText.isNullOrBlank() && pendingImages.isEmpty()
    // v4 4.1：输入区是一整块容器色（DlComposer）：输入框在上，下方 + / 模型 / 权限 / 发送
    DlComposer(
        text = inputText,
        onTextChange = onInputChange,
        placeholder = composerPlaceholder(isListening, running),
        sendState = DlSendState.Send,
        onSend = onSend,
        modifier = modifier.onFocusChanged { composerFocused = it.hasFocus },
        attachments = if (pendingImages.isEmpty()) null else {
            { ComposerPendingImages(pendingImages, onRemoveImage) }
        },
        field = {
            // 原生 EditText：保住中文输入法 composition / 语音转写的 InputConnection。
            ComposerEditField(
                value = inputText,
                onValueChange = onInputChange,
                hint = composerPlaceholder(isListening, running),
                textColor = Dsh.labelPrimary,
                hintColor = Dsh.tertiaryText,
                cursorColor = Dsh.brand400,
                fontSize = DshType.body.fontSize,
                lineHeight = DshType.body.lineHeight,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = DshSpace.s24, max = 200.dp)
                    .padding(start = DshSpace.s8, end = DshSpace.s8, bottom = DshSpace.s4)
                    .let { base ->
                        if (composerFocusRequester != null) base.focusRequester(composerFocusRequester) else base
                    },
            )
        },
        controls = {
            if (!running) ComposerAttachButton(permissionPreset, permissionLabel, onPickImage, onTakePhoto, onOpenPermissionPicker)
            Box(Modifier.weight(1f, fill = false)) {
                ComposerSeatsRow(
                    modelName = modelName,
                    modelEffort = modelEffort,
                    permissionPreset = permissionPreset,
                    permissionLabel = permissionLabel,
                    compact = compact,
                    onOpenModelPicker = onOpenModelPicker,
                    onOpenPermissionPicker = onOpenPermissionPicker,
                )
            }
            // v4：上下文占用不放在输入区，进 ⋯ → 用量（5.7）
        },
        sendButton = {
            ComposerSendButton(
                running = running,
                canSend = canSend,
                isSending = isSending,
                isListening = isListening,
                showMic = composerIdle && !running && !isSending && !isListening && voiceAvailable,
                voiceAvailable = voiceAvailable,
                actionError = actionError,
                onStop = onStop,
                onToggleVoice = onToggleVoice,
                onSend = onSend,
            )
        },
        footer = {
            val shownActionError = actionError
            if (shownActionError != null && composerShowsActionError(shownActionError, isSending)) {
                Text(
                    shownActionError,
                    color = Dsh.err,
                    style = DshType.supporting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DshSpace.s8, vertical = DshSpace.s4)
                        .semantics { contentDescription = shownActionError },
                )
            }
        },
    )
}

@Composable
private fun ComposerPendingImages(pendingImages: List<Pair<String, String>>, onRemoveImage: (Int) -> Unit) {
    pendingImages.forEachIndexed { index, (_, data) ->
        val preview = remember(data) { android.util.Base64.decode(data, android.util.Base64.DEFAULT) }
        Box {
            coil3.compose.AsyncImage(
                model = coil3.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(preview)
                    .build(),
                contentDescription = L.pendingImage,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(DshRadius.container)),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 12.dp, y = (-12).dp)
                    .size(48.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = L.removeImage
                    }
                    .clickable { onRemoveImage(index) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Dsh.surface2),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(CloseOutline16, contentDescription = null, tint = Dsh.labelSecondary, modifier = Modifier.size(DshIconSize.xs))
                }
            }
        }
    }
}

@Composable
private fun ComposerAttachButton(
    permissionPreset: String,
    permissionLabel: String,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onOpenPermissionPicker: () -> Unit,
) {
    var attachOpen by remember { mutableStateOf(false) }
    Box {
        RoundIconButton(icon = PlusOutline16, tint = Dsh.labelSecondary, contentDescription = L.addAttachment, onClick = { attachOpen = true })
        DshMenu(
            expanded = attachOpen,
            onDismiss = { attachOpen = false },
            items = listOf(
                DshMenuItem(ImageOutline16, L.choosePhoto) {
                    attachOpen = false
                    onPickImage()
                },
                DshMenuItem(CameraOutline16, L.takePhoto) {
                    attachOpen = false
                    onTakePhoto()
                },
                DshMenuItem(composerPermissionGlyph(permissionPreset), permissionLabel) {
                    attachOpen = false
                    onOpenPermissionPicker()
                },
            ),
        )
    }
}

/** 发送 / 停止 / 语音：容器右下角 36dp 圆键。发送品牌色、停止墨色、语音与禁用容器色。 */
@Composable
private fun ComposerSendButton(
    running: Boolean,
    canSend: Boolean,
    isSending: Boolean,
    isListening: Boolean,
    showMic: Boolean,
    voiceAvailable: Boolean,
    actionError: String?,
    onStop: () -> Unit,
    onToggleVoice: () -> Unit,
    onSend: () -> Unit,
) {
    val haptic = rememberDshHaptic()
    val showStop = running && !canSend && !isSending
    val sendBg by animateColorAsState(
        targetValue = when {
            showMic -> Dsh.surface2
            actionError != null && (showStop || canSend) -> Dsh.err
            showStop -> Dsh.inkFill
            isListening || canSend || isSending -> Dsh.brand400
            else -> Dsh.surface2
        },
        animationSpec = tween(motionDuration(DshDuration.fast)),
        label = "sendBg",
    )
    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics {
                role = Role.Button
                contentDescription = when {
                    actionError != null && (showStop || canSend) -> actionError
                    showStop -> L.stopGenerating
                    isListening -> L.listening
                    canSend -> L.sendMessage
                    else -> if (voiceAvailable) L.voiceInput else L.sendMessage
                }
            }
            .clickable(
                enabled = showStop || isListening || showMic || (canSend && !isSending),
                onClick = {
                    haptic(if (isListening || showMic) DshHaptic.ToggleOn else DshHaptic.Tick)
                    when {
                        showStop -> onStop()
                        isListening || showMic -> onToggleVoice()
                        else -> onSend()
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(sendBg),
            contentAlignment = Alignment.Center,
        ) {
            when {
                showStop -> Box(Modifier.size(12.dp).background(Dsh.onInk))
                isListening || isSending -> CircularProgressIndicator(
                    modifier = Modifier.size(DshIconSize.xs),
                    color = Dsh.onBrand,
                    strokeWidth = 1.5.dp,
                )
                showMic -> Icon(MicOutline16, contentDescription = null, tint = Dsh.labelSecondary, modifier = Modifier.size(DshIconSize.sm))
                else -> Icon(
                    SendOutline16,
                    contentDescription = null,
                    tint = if (canSend) Dsh.onBrand else Dsh.tertiaryText,
                    modifier = Modifier.size(DshIconSize.sm),
                )
            }
        }
    }
}

/**
 * 输入条座位行（DSH `conversation.input.model` + `conversation.input.permission`）：
 *
 * - 两个座位都是 28dp 高、无边框、无静态底色的安静文本控件（DSH `ModelSelect` /
 *   `PermissionSelect` 同规格：hover/press 才出现底色、radius 24、13sp/500、gap 4dp）。
 * - 模型座：名称 + 推理等级（等级颜色更浅、先被挤掉）；窄档只留图标（DSH 把
 *   `--dsh-composer-model-text-display` 关掉、只开图标）。
 * - 访问模式座：预设图标 + 名称；窄档只留图标，语义描述仍读完整「访问模式，当前：X」。
 *
 * plan / goal 在 DSH 里是 `/plan` `/goal` 命令，不是座位，所以这里不再有「工作模式」。
 */
private fun composerPermissionGlyph(preset: String) = when (canonicalComposerPermission(preset)) {
    "read-only" -> BrowseOutline16
    "danger-full-access" -> WarningOutline16
    else -> FolderOpenOutline16
}

@Composable
internal fun ComposerSeatsRow(
    modelName: String?,
    modelEffort: String?,
    permissionPreset: String,
    permissionLabel: String,
    compact: Boolean = false,
    onOpenModelPicker: () -> Unit = {},
    onOpenPermissionPicker: () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpace.s4),
    ) {
        // v3：窄屏时模型座先让位（weight + fill=false：不撑满，只在放不下时收缩），
        // 访问模式标签保持完整，不再出现「工作…」
        ComposerModelSeat(
            name = modelName,
            effort = modelEffort,
            compact = compact,
            onClick = onOpenModelPicker,
            modifier = Modifier.weight(1f, fill = false),
        )
        ComposerAccessSeat(
            preset = permissionPreset,
            label = permissionLabel,
            compact = compact,
            onClick = onOpenPermissionPicker,
        )
    }
}

/**
 * 模型座：DSH `ModelSelect` 触发器。名称主文，推理等级是次级文本；
 * 两者都省略号截断，等级先被挤掉；窄档只留模型图标。
 */
@Composable
private fun ComposerModelSeat(
    name: String?,
    effort: String?,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hasModel = !name.isNullOrBlank()
    val aria = when {
        !hasModel -> L.selectModel
        effort.isNullOrBlank() -> L.modelSeatAria.format(name)
        else -> L.modelSeatAriaEffort.format(name, effort)
    }
    // 视觉 28dp（DSH 规格），触摸区交给外层 48dp（与 + / 发送键同高，不改变行高）
    Box(
        modifier = modifier
            .height(48.dp)
            .semantics {
                role = Role.Button
                contentDescription = aria
            }
            .clickable(interactionSource = interaction, indication = dshRipple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(28.dp)
                // composer 内入口统一 control 形状（8dp 圆角矩形），不用 pill
                .clip(RoundedCornerShape(DshRadius.control))
                .padding(start = DshSpace.s8, end = DshSpace.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DshSpace.s4),
        ) {
            // DSH 默认藏图标、窄档才只显示图标
            if (compact) {
                Icon(
                    Sparkle16,
                    contentDescription = null,
                    tint = if (hasModel) Dsh.labelSecondary else Dsh.labelTertiary,
                    modifier = Modifier.size(DshIconSize.sm),
                )
            } else {
                Text(
                    text = name ?: L.selectModel,
                    color = Dsh.labelSecondary,
                    style = DshType.title,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = COMPOSER_MODEL_MAX_WIDTH),
                )
                if (!effort.isNullOrBlank()) {
                    // 等级先被挤掉：weight 让它排在下拉箭头之后测量
                    Text(
                        text = formatEffortLabel(effort),
                        color = Dsh.labelTertiary,
                        style = DshType.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            Icon(
                ChevronDownOutline16,
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(DshIconSize.xs),
            )
        }
    }
}

/**
 * 访问模式座：DSH `PermissionSelect` 触发器（`/permission <preset>` 的图形入口）。
 * 只读 / 工作区内修改安静显示；完全权限用风险色，让危险档在输入条上一直看得见。
 */
@Composable
private fun ComposerAccessSeat(
    preset: String,
    label: String,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val canonical = canonicalComposerPermission(preset)
    val danger = composerPermissionIsDanger(canonical)
    val glyph = when (canonical) {
        "read-only" -> BrowseOutline16
        "danger-full-access" -> WarningOutline16
        else -> FolderOpenOutline16
    }
    val contentTint = if (danger) Dsh.warn else Dsh.labelSecondary
    Box(
        modifier = Modifier
            .height(48.dp)
            .semantics {
                role = Role.Button
                contentDescription = L.accessModeAria.format(label)
            }
            .clickable(interactionSource = interaction, indication = dshRipple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(28.dp)
                // composer 内入口统一 control 形状（8dp 圆角矩形），不用 pill
                .clip(RoundedCornerShape(DshRadius.control))
                .padding(start = DshSpace.s8, end = DshSpace.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DshSpace.s4),
        ) {
            Icon(
                glyph,
                contentDescription = null,
                tint = contentTint,
                modifier = Modifier.size(DshIconSize.sm),
            )
            if (!compact) {
                Text(
                    text = label,
                    color = contentTint,
                    style = DshType.title,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = COMPOSER_ACCESS_MAX_WIDTH),
                )
            }
        }
    }
}

/**
 * 输入条圆钮的统一底色：静置 [Dsh.bgTrack]；按压反馈只留水波纹（P1）。
 */
@Composable
internal fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics {
                role = Role.Button
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .clickable(interactionSource = interaction, indication = dshRipple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (pressed) Dsh.labelPrimary else tint, modifier = Modifier.size(DshIconSize.sm))
        }
    }
}

@Composable
/** 新会话起始区的一行：图标 + 当前取值 + 下拉箭头；不可改时只显示取值。 */
private fun ComposerSetupRow(
    icon: ImageVector,
    label: String,
    onClick: (() -> Unit)?,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .heightIn(min = DshTouch.min)
            .clip(RoundedCornerShape(DshRadius.container))
            .then(
                if (onClick != null) {
                    Modifier
                        .semantics {
                            role = Role.Button
                            contentDescription = label
                        }
                        .clickable(interactionSource = interaction, indication = dshRipple(), onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = DshSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Dsh.labelSecondary, modifier = Modifier.size(DshIconSize.sm))
        Spacer(Modifier.width(DshSpace.s12))
        Text(
            label,
            color = if (onClick != null) Dsh.labelPrimary else Dsh.labelSecondary,
            style = DshType.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onClick != null) {
            Spacer(Modifier.width(DshSpace.s4))
            Icon(ChevronDownOutline16, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(DshIconSize.sm))
        }
    }
}

/**
 * 建议行（4.3，自下而上第三层）：「继续 / 复核 / 查看改动 (N)」中性胶囊（DshFilterChip，
 * 不用品牌蓝），点按只预填、不发送（L8）；「查看改动」有改动就常显。
 * 离线时这一行显示「电脑离线」灰字 + 状态点，不显示继续 / 复核。
 */
@Composable
internal fun ComposerSuggestionsRow(
    online: Boolean,
    suggestionsVisible: Boolean,
    changesCount: Int?,
    onSuggestion: (String) -> Unit,
    onOpenChanges: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showChanges = changesCount != null && changesCount > 0
    if (!online) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = DshSpace.s16, vertical = DshSpace.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Dsh.labelTertiary),
            )
            Spacer(Modifier.width(DshSpace.s8))
            Text(
                L.hostOffline,
                color = Dsh.labelTertiary,
                style = DshType.captionRelaxed,
                maxLines = 1,
            )
        }
        return
    }
    if (!suggestionsVisible && !showChanges) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpace.s8, vertical = DshSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpace.s8),
    ) {
        // v3 入场揭示：一轮结束、建议出现时逐个错峰 70ms 淡入上移（DshMotion.dshReveal）
        if (suggestionsVisible) {
            DshFilterChip(label = L.suggestContinue, selected = false, onClick = { onSuggestion(L.suggestContinueText) }, tonal = true, modifier = Modifier.dshReveal(0))
            DshFilterChip(label = L.suggestReview, selected = false, onClick = { onSuggestion(L.suggestReviewText) }, tonal = true, modifier = Modifier.dshReveal(1))
        }
        if (showChanges) {
            DshFilterChip(
                label = L.viewChangesCount.format(changesCount),
                selected = false,
                onClick = onOpenChanges,
                tonal = true,
                modifier = Modifier.dshReveal(if (suggestionsVisible) 2 else 0),
            )
        }
    }
}
