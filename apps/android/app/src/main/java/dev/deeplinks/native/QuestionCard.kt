package dev.deeplinks.native

import androidx.compose.runtime.Composable
import dev.deeplinks.core.DshS
import dev.deeplinks.core.L
import dev.deeplinks.core.decisionInBar
import dev.deeplinks.core.decisionWaitAnswer
import dev.deeplinks.native.ui.DshChipTone
import dev.deeplinks.native.ui.DshStatusChip

/**
 * 消息流里的提问（v4 4.4）：只留一行状态，作答在底部决策栏（[QuestionDecisionBar]）。
 * 手机答不了的题型提示回电脑上回答。
 */
@Composable
internal fun QuestionCard(msg: MobileMessage) {
    when {
        isTerminalRequestStatus(msg.requestStatus) -> DshStatusChip(
            text = DshS.questionSubmitted,
            tone = DshChipTone.Remote,
            leading = CheckOutline16,
        )
        displayQuestionsOf(msg).any { it.unsupported } ->
            DshStatusChip(text = DshS.questionUnsupportedOnPhone, tone = DshChipTone.Remote)
        else -> DshStatusChip(
            text = listOf(L.decisionWaitAnswer, L.decisionInBar).joinToString(" · "),
            tone = DshChipTone.Answer,
        )
    }
}
