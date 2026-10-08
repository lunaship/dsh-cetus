package dev.deeplinks.native

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.core.approveOfflineBlocked
import dev.deeplinks.core.decisionCustom
import dev.deeplinks.core.decisionNext
import dev.deeplinks.core.decisionPrev
import dev.deeplinks.core.decisionQuestionIndex
import dev.deeplinks.core.decisionRunCommand
import dev.deeplinks.core.decisionSkip
import dev.deeplinks.core.decisionUseTool
import dev.deeplinks.core.decisionWaitAnswer
import dev.deeplinks.core.decisionWaitApproval
import dev.deeplinks.native.ui.v4.DlAction
import dev.deeplinks.native.ui.v4.DlDecisionBar
import dev.deeplinks.native.ui.v4.DlDecisionOption
import org.json.JSONObject

/**
 * v4 4.3 / 4.4：需要你拍板的那一条（审批或提问）替换输入区，不进消息流。
 * 只挑手机能处理的：审批要已被手机接管，提问要有 rpc id、题型手机能答、之后你还没发过新消息。
 * 有多条时取最新的一条，处理完再轮到下一条。
 */
internal fun pendingDecision(messages: List<MobileMessage>): MobileMessage? {
    for (index in messages.indices.reversed()) {
        val msg = messages[index]
        when {
            msg.role == "approval" && actionableApproval(msg) -> return msg
            msg.role == "question" && actionableQuestion(msg) -> {
                val answeredLater = (index + 1 until messages.size).any { messages[it].role == "user" }
                if (!answeredLater) return msg
            }
        }
    }
    return null
}

internal fun actionableApproval(msg: MobileMessage): Boolean =
    !msg.approvalId.isNullOrBlank() && msg.requestStatus == REQUEST_PENDING && msg.takenOverByPhone

internal fun actionableQuestion(msg: MobileMessage): Boolean =
    !msg.questionRpcId.isNullOrBlank() &&
        !isTerminalRequestStatus(msg.requestStatus) &&
        displayQuestionsOf(msg).none { it.unsupported }

/** 结构化题目；老插件只给一段文字 + 选项时补成一题。 */
internal fun displayQuestionsOf(msg: MobileMessage): List<ClarifyingQuestion> =
    parseClarifyingQuestions(msg.questionPayloadJson).ifEmpty {
        listOf(
            ClarifyingQuestion(
                id = "q0",
                prompt = msg.text,
                options = msg.questionOptions.map { QuestionOption(it, it) },
            ),
        )
    }

/** 审批参数里的命令（bash 一类工具）；拿不到时返回 null。 */
internal fun approvalCommand(toolArgs: String?): String? {
    if (toolArgs.isNullOrBlank()) return null
    val obj = runCatching { JSONObject(toolArgs) }.getOrNull() ?: return null
    return listOf("command", "cmd", "script").firstNotNullOfOrNull { key -> obj.optString(key).takeIf { it.isNotBlank() } }
}

/** 审批决策栏：拒绝（左，容器色）/ 允许一次（右，品牌实心）。 */
@Composable
internal fun ApprovalDecisionBar(
    msg: MobileMessage,
    onAnswer: (approvalId: String, outcome: String, onDone: (Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    online: Boolean = true,
) {
    val haptic = rememberDshHaptic()
    var submitting by remember(msg.id) { mutableStateOf(false) }
    var error by remember(msg.id) { mutableStateOf<String?>(null) }
    val command = approvalCommand(msg.toolArgs)
    val tool = msg.toolName ?: L.toolFallbackName
    fun submit(outcome: String) {
        val id = msg.approvalId ?: return
        if (submitting) return
        // 方案 §18「离线」：审批是写操作，离线时不发请求，直接说明原因。
        if (!online) {
            error = L.approveOfflineBlocked
            return
        }
        haptic(if (outcome == "allowed-once") DshHaptic.Confirm else DshHaptic.Reject)
        submitting = true
        error = null
        onAnswer(id, outcome) { ok ->
            submitting = false
            if (!ok) error = L.approvalNotAccepted
        }
    }
    DlDecisionBar(
        status = L.decisionWaitApproval,
        question = when {
            msg.text.isNotBlank() -> msg.text
            command != null -> L.decisionRunCommand
            else -> L.decisionUseTool.format(tool)
        },
        command = command ?: tool,
        note = if (command == null) L.approvalArgsMissing else null,
        secondary = DlAction(L.reject, { submit("rejected") }, enabled = !submitting && online),
        primary = DlAction(L.allowOnce, { submit("allowed-once") }, enabled = !submitting && online),
        modifier = modifier,
        extra = error?.let { message -> { DecisionError(message) } },
    )
}

/** 提问决策栏：一题一题回答；最后一题的主按钮是「提交答案」。 */
@Composable
internal fun QuestionDecisionBar(
    msg: MobileMessage,
    onAnswer: (rpcId: String, answer: JSONObject, onDone: (Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    online: Boolean = true,
) {
    val questions = remember(msg.id, msg.questionPayloadJson) { displayQuestionsOf(msg) }
    val drafts = remember(msg.id) { mutableStateMapOf<String, QuestionDraft>() }
    var index by remember(msg.id) { mutableIntStateOf(0) }
    var customOpen by remember(msg.id) { mutableStateOf(setOf<String>()) }
    var submitting by remember(msg.id) { mutableStateOf(false) }
    var error by remember(msg.id) { mutableStateOf<String?>(null) }
    val question = questions[index.coerceIn(0, questions.lastIndex)]
    val draft = drafts[question.id] ?: QuestionDraft()
    val last = index >= questions.lastIndex
    val answered = draft.selected.isNotEmpty() || draft.custom.isNotBlank()
    val writing = question.id in customOpen || question.options.isEmpty()

    fun submit() {
        val answer = buildQuestionAnswers(questions, drafts) ?: return
        val rpcId = msg.questionRpcId ?: return
        // 与审批同一把锁：连点两次只产生一次提交（方案 §7 要求 2 / §18「问题」）。
        if (submitting) return
        // 方案 §18「离线」：作答是写操作，离线时不发请求，直接说明原因。
        if (!online) {
            error = L.approveOfflineBlocked
            return
        }
        submitting = true
        error = null
        onAnswer(rpcId, answer) { ok ->
            submitting = false
            if (!ok) error = L.approvalNotAccepted
        }
    }
    fun advance() {
        if (last) submit() else index += 1
    }

    val options = question.options.map { option ->
        val selected = option.id in draft.selected || option.label in draft.selected
        DlDecisionOption(option.label, selected, onClick = {
            error = null
            val next = when {
                question.multiple && selected -> draft.selected - option.id - option.label
                question.multiple -> draft.selected + option.id
                else -> listOf(option.id)
            }
            drafts[question.id] = draft.copy(selected = next, custom = if (question.multiple) draft.custom else "")
            if (!question.multiple) customOpen = customOpen - question.id
        })
    } + if (question.options.isNotEmpty()) {
        listOf(
            DlDecisionOption(L.decisionCustom, selected = question.id in customOpen, custom = true, onClick = {
                customOpen = customOpen + question.id
                if (!question.multiple) drafts[question.id] = draft.copy(selected = emptyList())
            }),
        )
    } else {
        emptyList()
    }

    val secondary = when {
        question.optional -> DlAction(L.decisionSkip, { drafts.remove(question.id); advance() }, enabled = !submitting && online)
        else -> DlAction(L.decisionPrev, { index -= 1 }, enabled = index > 0 && !submitting && online)
    }
    val primary = DlAction(
        if (last) DshS.questionSubmitAnswer else L.decisionNext,
        { advance() },
        enabled = !submitting && online && (answered || question.optional) && (!last || questionDraftComplete(questions, drafts)),
    )
    DlDecisionBar(
        status = L.decisionWaitAnswer,
        meta = if (questions.size > 1) L.decisionQuestionIndex.format(index + 1, questions.size) else null,
        question = question.prompt.ifBlank { msg.text.ifBlank { msg.questionHeader ?: DshS.questionClarify } },
        options = options,
        secondary = secondary,
        primary = primary,
        modifier = modifier,
        extra = if (writing || error != null) {
            {
                if (writing) {
                    DecisionAnswerField(draft.custom) { value ->
                        error = null
                        drafts[question.id] = draft.copy(
                            custom = value.take(MAX_QUESTION_CUSTOM_CHARS),
                            selected = if (value.isNotBlank() && !question.multiple) emptyList() else draft.selected,
                        )
                    }
                }
                error?.let { DecisionError(it) }
            }
        } else {
            null
        },
    )
}

@Composable
private fun DecisionAnswerField(value: String, onChange: (String) -> Unit) {
    val hint = DshS.questionAnswerHint
    val description = DshS.questionCustomAnswerDescription
    BasicTextField(
        value = value,
        onValueChange = onChange,
        maxLines = 5,
        textStyle = DshType.body.copy(color = Dsh.labelPrimary),
        cursorBrush = SolidColor(Dsh.brand400),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(Dsh.surface1, RoundedCornerShape(DshRadius.container))
                    .padding(DshSpace.s12),
            ) {
                if (value.isEmpty()) Text(hint, color = Dsh.tertiaryText, style = DshType.body)
                inner()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
    )
}

@Composable
private fun DecisionError(message: String) {
    Text(
        message,
        color = Dsh.err,
        style = DshType.supporting,
        modifier = Modifier.semantics { contentDescription = message },
    )
}

/** 对话页底部：审批走 [ApprovalDecisionBar]，提问走 [QuestionDecisionBar]。 */
@Composable
internal fun DecisionBarHost(
    decision: MobileMessage,
    onAnswerApproval: (approvalId: String, outcome: String, onDone: (Boolean) -> Unit) -> Unit,
    onAnswerQuestion: (rpcId: String, answer: JSONObject, onDone: (Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    /** 方案 §18「离线」：审批 / 作答也是写操作，离线时禁用主次动作并解释。 */
    online: Boolean = true,
) {
    if (decision.role == "approval") {
        ApprovalDecisionBar(decision, onAnswerApproval, modifier, online)
    } else {
        QuestionDecisionBar(decision, onAnswerQuestion, modifier, online)
    }
}
