package dev.deeplinks.native

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import dev.deeplinks.core.DshType

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.deeplinks.core.dshRipple
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.L
import dev.deeplinks.native.util.SessionListKind
import dev.deeplinks.native.util.visibleUserWorkspaces

// ---------- 输入卡（InputBar 1:1） ----------

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
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = COMPOSER_SIDE_CLEARANCE, end = COMPOSER_SIDE_CLEARANCE),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 输入卡主体：底部悬浮卡——6dp 阴影浮在消息流之上；
        // 浅色灰底 + 发丝描边，聚焦加深描边（不用蓝色 focus ring）。
        var composerFocused by remember { mutableStateOf(false) }
        // 录音只在设备真的有语音识别服务时出现（不可用的能力不占位）
        val voiceContext = androidx.compose.ui.platform.LocalContext.current
        val voiceAvailable = remember(voiceContext) {
            runCatching { android.speech.SpeechRecognizer.isRecognitionAvailable(voiceContext) }
                .getOrDefault(false)
        }
        val composerBg by animateColorAsState(
            targetValue = if (Dsh.isDark) Dsh.bgInput else Dsh.bgSubtle,
            animationSpec = tween(motionDuration(DshDuration.normal)),
            label = "composerBg"
        )
        val composerBorder by animateColorAsState(
            targetValue = if (composerFocused) Dsh.borderStrong else Dsh.borderSubtle,
            animationSpec = tween(motionDuration(DshDuration.normal)),
            label = "composerBorder"
        )
        val composerShape = RoundedCornerShape(DshRadius.composer)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 2.dp, shape = composerShape, ambientColor = Dsh.shadowCard, spotColor = Dsh.shadowCard)
                .clip(composerShape)
                .background(composerBg)
                .border(0.5.dp, composerBorder, composerShape)
                .padding(top = DshSpace.s4)
                .onFocusChanged { composerFocused = it.hasFocus }
        ) {
            // 待发送图片缩略图（DSH 待发送图片行）
            if (pendingImages.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = DshSpace.s16, end = DshSpace.s12, top = DshSpace.s8),
                    horizontalArrangement = Arrangement.spacedBy(DshSpace.s8)
                ) {
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
                                    .clip(RoundedCornerShape(DshRadius.container))
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 10.dp, y = (-10).dp)
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
                                        .background(Dsh.bgSubtle),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        CloseOutline16,
                                        contentDescription = null,
                                        tint = Dsh.labelSecondary,
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // 原生 EditText：保住中文输入法 composition / 语音转写的 InputConnection。
            // Compose BasicTextField 在 canSend 切换 imeAction 或双状态同步时易断连。
            val composerHint = composerPlaceholder(isListening, running)
            ComposerEditField(
                value = inputText,
                onValueChange = onInputChange,
                hint = composerHint,
                textColor = Dsh.labelPrimary,
                hintColor = Dsh.labelTertiary,
                cursorColor = Dsh.brand400,
                fontSize = 16.sp,
                lineHeight = 25.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp, max = 200.dp)
                    .padding(start = DshSpace.s16, end = DshSpace.s12, top = DshSpace.s2)
                    .let { base ->
                        if (composerFocusRequester != null) base.focusRequester(composerFocusRequester) else base
                    },
            )

            // 底部工具行：附件、模型选择与发送
            val composerIdle = inputText.isNullOrBlank() && pendingImages.isEmpty()
            Box(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = DshSpace.s8, end = DshSpace.s8, top = 0.dp, bottom = DshSpace.s4),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DshSpace.s4),
                ) {
                    // 左侧：+ 按钮（DSH input add：图片/附件）
                    if (!running) {
                        var attachOpen by remember { mutableStateOf(false) }
                        Box {
                            RoundIconButton(
                                icon = PlusOutline16,
                                tint = Dsh.labelPrimary,
                                contentDescription = L.addAttachment,
                                onClick = { attachOpen = true }
                            )
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

                        // 模型胶囊标签（轻量次级文字，低干扰）
                        val modelLabel = if (!modelName.isNullOrBlank()) modelName else L.selectModel
                        val effortLabel = if (!modelEffort.isNullOrBlank() && !modelEffort.equals("null", ignoreCase = true)) formatEffortLabel(modelEffort) else null
                        val modelAria = when {
                            modelName.isNullOrBlank() -> L.selectModel
                            effortLabel == null -> L.modelSeatAria.format(modelName)
                            else -> L.modelSeatAriaEffort.format(modelName, modelEffort)
                        }
                        val modelPillInteraction = remember { MutableInteractionSource() }
                        val modelPillPressed by modelPillInteraction.collectIsPressedAsState()
                        Row(
                            modifier = Modifier
                                .heightIn(min = DshTouch.min)
                                .clip(RoundedCornerShape(DshRadius.control))
                                .background(if (modelPillPressed) Dsh.pressed else Color.Transparent)
                                .clickable(
                                    interactionSource = modelPillInteraction,
                                    indication = dshRipple(),
                                    onClick = onOpenModelPicker,
                                )
                                .semantics {
                                    role = Role.Button
                                    contentDescription = modelAria
                                }
                                .padding(horizontal = DshSpace.s6, vertical = DshSpace.s4),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                modelLabel,
                                color = Dsh.labelSecondary,
                                style = DshType.captionMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (effortLabel != null) {
                                Spacer(Modifier.width(DshSpace.s4))
                                Text(
                                    effortLabel,
                                    color = Dsh.labelTertiary,
                                    style = DshType.microRelaxed,
                                    maxLines = 1,
                                )
                            }
                            Spacer(Modifier.width(3.dp))
                            Icon(
                                ChevronDownOutline14,
                                contentDescription = null,
                                tint = Dsh.labelTertiary,
                                modifier = Modifier.size(11.dp),
                            )
                        }
                    }
                }

                // 上下文占用（DSH 输入卡下方的环 + 百分比按钮）：从模型座里搬出来，
                // 模型座只管模型；窄档收起，避免把发送键挤出这一行。
                val meterStats = sessionStats
                if (!compact && meterStats != null && meterStats.contextWindow > 0) {
                    ContextMeterButton(stats = meterStats, running = running)
                }

                // 发送键：固定在行尾。执行中且无输入 → 呼吸停止圆；有输入 → 仍可发送（排队/打断）
                val sendInteraction = remember { MutableInteractionSource() }
                val sendPressed by sendInteraction.collectIsPressedAsState()
                val haptic = rememberDshHaptic()
                val showStopAtSend = running && !canSend && !isSending
                val showMic = composerIdle && !running && !isSending && !isListening && voiceAvailable
                val sendBg by animateColorAsState(
                    targetValue = when {
                        // 空态语音：与左侧 + / 同一规格的 bgTrack 圆钮；实心蓝只给「需要你动手」的
                        // 批准与发送，停止（打断过程）按 2026-09-28 重设计改用墨色实心
                        showMic -> composerRoundButtonBg(sendPressed)
                        actionError != null && (showStopAtSend || canSend) -> Dsh.error
                        showStopAtSend -> Dsh.labelPrimary
                        isListening -> Dsh.brand500
                        !canSend && !isSending -> Dsh.brand500.copy(alpha = 0.55f)
                        sendPressed -> Dsh.brand400
                        else -> Dsh.brand500
                    },
                    animationSpec = tween(motionDuration(120)),
                    label = "sendBg"
                )
                val sendScale = animateFloatAsState(
                    targetValue = if (sendPressed && !showStopAtSend) 0.88f else 1f,
                    animationSpec = tween(motionDuration(DshDuration.fast)),
                    label = "sendScale"
                )
                val reduceMotion = isReduceMotionEnabled()
                val breathScale: State<Float>?
                val breathAlpha: State<Float>?
                if (showStopAtSend && !reduceMotion) {
                    val breath = rememberInfiniteTransition(label = "stopBreath")
                    breathScale = breath.animateFloat(
                        initialValue = 0.88f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1100, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "stopBreathScale"
                    )
                    breathAlpha = breath.animateFloat(
                        initialValue = 0.62f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1100, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "stopBreathAlpha"
                    )
                } else {
                    breathScale = null
                    breathAlpha = null
                }
                Box(
                    modifier = Modifier
                        .padding(start = DshSpace.s4)
                        .size(48.dp)
                        .dshPressScale(sendInteraction)
                        .semantics {
                            role = Role.Button
                            contentDescription = when {
                                actionError != null && (showStopAtSend || canSend) -> actionError
                                showStopAtSend -> L.stopGenerating
                                isListening -> L.listening
                                canSend -> L.sendMessage
                                // 无语音能力时这里是禁用的发送键，不能读成「语音输入」
                                else -> if (voiceAvailable) L.voiceInput else L.sendMessage
                            }
                        }
                        .clickable(
                            interactionSource = sendInteraction,
                            indication = dshRipple(),
                            enabled = showStopAtSend || isListening || showMic || (canSend && !isSending),
                            onClick = {
                                // 语义触觉：发送/停止是轻点确认，进入语音是开关态
                                haptic(
                                    when {
                                        showStopAtSend -> DshHaptic.Tick
                                        isListening || showMic -> DshHaptic.ToggleOn
                                        else -> DshHaptic.Tick
                                    }
                                )
                                when {
                                    showStopAtSend -> onStop()
                                    isListening || showMic -> onToggleVoice()
                                    else -> onSend()
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .graphicsLayer {
                                val scale = breathScale?.value ?: sendScale.value
                                scaleX = scale
                                scaleY = scale
                                alpha = breathAlpha?.value ?: 1f
                            }
                            .clip(CircleShape)
                            .background(sendBg),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            showStopAtSend -> {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(RoundedCornerShape(DshRadius.micro))
                                        .background(Dsh.bgCard)
                                )
                            }
                            isListening || isSending -> {
                                val angle = rememberMotionSpin(750, label = "spin")
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .rotate(angle ?: 0f)
                                        .border(1.5.dp, Color.White, CircleShape)
                                )
                            }
                            showMic -> {
                                // 与 + / 设置 同灰阶图标（圆钮比它们大一号，图标同步 16dp）
                                Icon(
                                    MicOutline16,
                                    contentDescription = null,
                                    tint = Dsh.labelPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            else -> {
                                Icon(
                                    SendOutline16,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
            }
            val shownActionError = actionError
            if (shownActionError != null && composerShowsActionError(shownActionError, isSending)) {
                Text(
                    shownActionError,
                    color = Dsh.error,
                    style = DshType.caption,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = DshSpace.s16, end = DshSpace.s12, bottom = DshSpace.s8)
                        .semantics { contentDescription = shownActionError },
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
        horizontalArrangement = Arrangement.spacedBy(DshSpace.s2),
    ) {
        ComposerModelSeat(
            name = modelName,
            effort = modelEffort,
            compact = compact,
            onClick = onOpenModelPicker,
        )
        ComposerAccessSeat(
            preset = permissionPreset,
            label = permissionLabel,
            compact = compact,
            onClick = onOpenPermissionPicker,
        )
    }
}

/** DSH 座位底：无边框无底色，按压才叠一层弱底，圆角胶囊。 */
@Composable
private fun composerSeatBackground(pressed: Boolean): Color =
    if (pressed) Dsh.pressed else Color.Transparent

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
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hasModel = !name.isNullOrBlank()
    val aria = when {
        !hasModel -> L.selectModel
        effort.isNullOrBlank() -> L.modelSeatAria.format(name)
        else -> L.modelSeatAriaEffort.format(name, effort)
    }
    // 视觉 28dp（DSH 规格），触摸区交给外层 48dp（与 + / 发送键同高，不改变行高）
    Box(
        modifier = Modifier
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
                .background(composerSeatBackground(pressed))
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
                    modifier = Modifier.size(14.dp),
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
                    Text(
                        text = formatEffortLabel(effort),
                        color = Dsh.labelTertiary,
                        style = DshType.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                ChevronDownOutline14,
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(12.dp),
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
    val pressed by interaction.collectIsPressedAsState()
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
                .background(composerSeatBackground(pressed))
                .padding(start = DshSpace.s8, end = DshSpace.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DshSpace.s4),
        ) {
            Icon(
                glyph,
                contentDescription = null,
                tint = contentTint,
                modifier = Modifier.size(14.dp),
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
 * 输入条圆钮的统一底色：静置 [Dsh.bgTrack]、按压品牌淡色。
 * 「+ / 空态 Mic」共用，保证一排按钮看起来是一套。
 */
@Composable
private fun composerRoundButtonBg(pressed: Boolean): Color =
    if (pressed) Dsh.pressed else Dsh.bgTrack

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
                .clip(CircleShape)
                .background(if (pressed) Dsh.pressed else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (pressed) Dsh.labelPrimary else tint, modifier = Modifier.size(16.dp))
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
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .heightIn(min = DshTouch.min)
            .clip(RoundedCornerShape(DshRadius.container))
            .background(if (pressed) Dsh.pressed else Color.Transparent)
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
        Icon(icon, contentDescription = null, tint = Dsh.labelSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
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
            Icon(ChevronDownOutline14, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(14.dp))
        }
    }
}
