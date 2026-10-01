package dev.deeplinks.native

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import dev.deeplinks.core.DshType

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.native.ui.DshPillButton
import dev.deeplinks.native.ui.DshPillTone
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * ask_user_question 澄清卡：逐题独立选择或自由文本；提交后等服务端 ack。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QuestionCard(
    msg: MobileMessage,
    onAnswer: (rpcId: String, answer: JSONObject, onDone: (Boolean) -> Unit) -> Unit,
) {
    val strings = DshS
    val cardScope = rememberCoroutineScope()
    val questions = remember(msg.id, msg.questionPayloadJson) { parseClarifyingQuestions(msg.questionPayloadJson) }
    val drafts = remember(msg.id) { mutableStateMapOf<String, QuestionDraft>() }
    var sent by remember(msg.id, msg.requestStatus) { mutableStateOf(isTerminalRequestStatus(msg.requestStatus)) }
    var submitting by remember(msg.id) { mutableStateOf(false) }
    var submitError by remember(msg.id) { mutableStateOf<String?>(null) }
    val rpcId = msg.questionRpcId.orEmpty()
    val locked = sent || isTerminalRequestStatus(msg.requestStatus)

    if (locked) {
        Text(
            strings.questionSubmitted,
            color = Dsh.labelSecondary,
            style = DshType.body,
            modifier = Modifier
                .clip(RoundedCornerShape(DshRadius.control))
                .background(Dsh.bgSubtle)
                .padding(horizontal = DshSpace.s12, vertical = DshSpace.s8),
        )
        return
    }

    fun draftOf(id: String) = drafts[id] ?: QuestionDraft()
    fun updateDraft(id: String, transform: (QuestionDraft) -> QuestionDraft) {
        drafts[id] = transform(draftOf(id))
    }

    val displayQuestions = questions.ifEmpty {
        listOf(
            ClarifyingQuestion(
                id = "q0",
                prompt = msg.text,
                options = msg.questionOptions.map { QuestionOption(it, it) },
            ),
        )
    }

    // 默认 tonal 容器（bgSubtle + container 圆角）：问题卡是行内卡片，不浮起，去掉阴影
    Column(
        modifier = Modifier
            .widthIn(max = 340.dp)
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .clip(RoundedCornerShape(DshRadius.container))
            .background(Dsh.bgSubtle)
            .padding(horizontal = 14.dp, vertical = DshSpace.s12),
    ) {
        Text(
            msg.questionHeader?.takeIf { it.isNotBlank() } ?: strings.questionClarify,
            color = Dsh.labelTertiary,
            style = DshType.microMedium,
            fontWeight = FontWeight(500),
        )
        if (displayQuestions.any { it.unsupported }) {
            Spacer(Modifier.height(DshSpace.s8))
            Text(
                strings.questionUnsupportedOnPhone,
                color = Dsh.labelSecondary,
                style = DshType.titleSmall,
            )
            return@Column
        }
        displayQuestions.forEachIndexed { questionIndex, question ->
            if (questionIndex > 0) Spacer(Modifier.height(DshSpace.s12))
            else Spacer(Modifier.height(DshSpace.s4))
            if (displayQuestions.size > 1) {
                Text(
                    strings.questionIndex.format(questionIndex + 1),
                    color = Dsh.labelTertiary,
                    style = DshType.microMedium,
                    fontWeight = FontWeight(500),
                )
                Spacer(Modifier.height(DshSpace.s2))
            }
            Text(
                question.prompt.ifBlank { msg.text },
                color = Dsh.labelPrimary,
                style = DshType.titleSmall,
                fontWeight = FontWeight(500),
                lineHeight = 18.sp,
            )
            val draft = draftOf(question.id)
            val bringIntoView = remember(question.id) { BringIntoViewRequester() }
            if (question.options.isNotEmpty()) {
                Spacer(Modifier.height(DshSpace.s8))
                question.options.forEachIndexed { index, option ->
                    val isSelected = option.id in draft.selected || option.label in draft.selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(DshRadius.control))
                            .selectable(
                                selected = isSelected,
                                onClick = {
                                    updateDraft(question.id) { current ->
                                        val nextSelected = if (question.multiple) {
                                            if (isSelected) current.selected - option.id else current.selected + option.id
                                        } else {
                                            listOf(option.id)
                                        }
                                        current.copy(selected = nextSelected, custom = if (question.multiple) current.custom else "")
                                    }
                                },
                                role = Role.RadioButton,
                            )
                            .semantics { contentDescription = option.label }
                            .heightIn(min = 48.dp)
                            .padding(vertical = DshSpace.s6, horizontal = DshSpace.s4),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}. ${option.label}",
                            color = if (isSelected) Dsh.labelPrimary else Dsh.labelSecondary,
                            style = DshType.body,
                            fontWeight = if (isSelected) FontWeight(600) else FontWeight.Normal,
                        )
                    }
                }
            }
            Spacer(Modifier.height(DshSpace.s8))
            BasicTextField(
                value = draft.custom,
                onValueChange = { value ->
                    updateDraft(question.id) { current ->
                        current.copy(
                            custom = value.take(MAX_QUESTION_CUSTOM_CHARS),
                            selected = if (value.isNotBlank() && !question.multiple) emptyList() else current.selected,
                        )
                    }
                },
                maxLines = 5,
                textStyle = DshType.body.copy(color = Dsh.labelPrimary),
                cursorBrush = SolidColor(Dsh.labelPrimary),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp, max = 140.dp)
                            .clip(RoundedCornerShape(DshRadius.control))
                            .background(Dsh.bgSubtle)
                            .padding(horizontal = 10.dp, vertical = DshSpace.s8),
                    ) {
                        if (draft.custom.isEmpty()) {
                            Text(strings.questionAnswerHint, color = Dsh.labelTertiary, style = DshType.body)
                        }
                        inner()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp, max = 140.dp)
                    .bringIntoViewRequester(bringIntoView)
                    .onFocusChanged { focus ->
                        if (focus.isFocused) cardScope.launch { bringIntoView.bringIntoView() }
                    }
                    .semantics { contentDescription = strings.questionCustomAnswerDescription },
            )
        }
        Spacer(Modifier.height(10.dp))
        val shownError = submitError
        if (shownError != null) {
            Text(
                shownError,
                color = Dsh.error,
                style = DshType.caption,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = shownError },
            )
            Spacer(Modifier.height(DshSpace.s8))
        }
        // C5.4：提交按钮从「^」图标改为带文字按钮——图标更像「收起」而非「提交」，
        // 与输入框发送按钮的向上箭头也容易混淆。无障碍描述与可见文字保持一致。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val canSend = !submitting && rpcId.isNotBlank() && questionDraftComplete(displayQuestions, drafts)
            DshPillButton(
                label = strings.questionSubmitAnswer,
                onClick = {
                    val answer = buildQuestionAnswers(displayQuestions, drafts) ?: return@DshPillButton
                    submitting = true
                    submitError = null
                    onAnswer(rpcId, answer) { ok ->
                        submitting = false
                        if (ok) sent = true
                        else submitError = strings.approvalNotAccepted
                    }
                },
                enabled = canSend,
                tone = DshPillTone.Tonal,
            )
        }
    }
}
