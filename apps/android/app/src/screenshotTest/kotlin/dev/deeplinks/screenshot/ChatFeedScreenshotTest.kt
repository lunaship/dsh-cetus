package dev.deeplinks.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.deeplinks.core.DarkDshColors
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshFontFamily
import dev.deeplinks.core.DshStringsEn
import dev.deeplinks.core.DshStringsZh
import dev.deeplinks.core.LightDshColors
import dev.deeplinks.core.LocalDshColors
import dev.deeplinks.core.LocalDshFontFamily
import dev.deeplinks.core.LocalDshStrings
import dev.deeplinks.core.dshTypography
import dev.deeplinks.native.DshSpace
import dev.deeplinks.native.MessageItem
import dev.deeplinks.native.MobileMessage

/**
 * 对话流：一轮完整回复、流式进行中、审批卡、提问卡。
 * 只用确定性内容（无数学 / Mermaid：它们走 WebView 异步渲染，截图不稳定）。
 */

@Composable
private fun ChatFrame(dark: Boolean, english: Boolean = false, content: @Composable () -> Unit) {
    val typography = dshTypography(DshFontFamily)
    MaterialTheme(typography = typography) {
        CompositionLocalProvider(
            LocalDshColors provides if (dark) DarkDshColors else LightDshColors,
            LocalDshStrings provides if (english) DshStringsEn else DshStringsZh,
            LocalDshFontFamily provides DshFontFamily,
            LocalTextStyle provides typography.bodyMedium,
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Dsh.bgBase)) {
                Column(
                    modifier = Modifier.padding(DshSpace.s16),
                    verticalArrangement = Arrangement.spacedBy(DshSpace.s12),
                ) { content() }
            }
        }
    }
}

@Composable
private fun Messages(messages: List<MobileMessage>, running: Boolean = false) {
    messages.forEachIndexed { index, msg ->
        MessageItem(
            msg = msg,
            running = running && index == messages.lastIndex,
            onAnswerApproval = { _, _, done -> done(true) },
            onAnswerQuestion = { _, _, done -> done(true) },
            onRate = {},
            showActions = index == messages.lastIndex,
        )
    }
}

private val completedTurn = listOf(
    MobileMessage(id = "u1", role = "user", text = "把会话草稿落盘，进程被杀后要能恢复"),
    MobileMessage(
        id = "r1",
        role = "reasoning",
        text = "草稿现在只在 ViewModel 里；需要按主机落到 SharedPreferences，附件太大不落盘。",
        durationMs = 8_400,
    ),
    MobileMessage(
        id = "t1",
        role = "tool_call",
        text = "",
        toolName = "read_file",
        toolArgs = """{"path":"native/util/ComposerDraft.kt"}""",
    ),
    MobileMessage(
        id = "t2",
        role = "tool_result",
        text = "data class ComposerDraft(\n    val text: String = \"\",\n    val images: List<Pair<String, String>> = emptyList(),\n)",
        durationMs = 120,
    ),
    MobileMessage(
        id = "a1",
        role = "assistant",
        text = """
            |### 改动
            |
            |- 正文按主机落盘，**附件不落盘**
            |- 14 天过期，最多保留 30 条
            |
            |```kotlin
            |fun pruneStoredDrafts(stored: Map<String, StoredDraft>, now: Long)
            |```
        """.trimMargin(),
        durationMs = 42_000,
    ),
)

private val streamingTurn = listOf(
    MobileMessage(id = "u2", role = "user", text = "Explain the retry loop"),
    MobileMessage(id = "r2", role = "reasoning", text = "Looking at SessionStreamClient reconnect…", running = true),
    MobileMessage(
        id = "a2",
        role = "assistant",
        text = "The client reconnects within 30s and resumes from the last cursor:\n\n```kotlin\nwhile (active) {\n    connect(cursor)",
        running = true,
    ),
)

private val approvals = listOf(
    MobileMessage(
        id = "ap1",
        role = "approval",
        text = "",
        toolName = "bash",
        toolArgs = """{"command":"rm -rf build/"}""",
        approvalId = "appr-1",
        requestStatus = "pending",
    ),
    MobileMessage(
        id = "ap2",
        role = "approval",
        text = "",
        toolName = "write_file",
        approvalId = "appr-2",
        requestStatus = "resolved",
        outcome = "allowed-once",
    ),
    MobileMessage(
        id = "ap3",
        role = "approval",
        text = "",
        toolName = "bash",
        approvalId = "appr-3",
        requestStatus = "unknown",
    ),
)

private val question = MobileMessage(
    id = "q1",
    role = "question",
    text = "",
    questionRpcId = "rpc-1",
    questionHeader = "Before I start",
    questionPayloadJson = """
        [
          {"id":"scope","question":"Which files may I touch?","options":["Android only","Android + plugin"]},
          {"id":"extras","question":"Also add tests for:","multiple":true,"optional":true,
           "options":[{"id":"unit","label":"Unit tests"},{"id":"shot","label":"Screenshot tests"}]}
        ]
    """.trimIndent(),
    requestStatus = "pending",
)

@PreviewTest
@Preview(name = "chat turn light zh", showBackground = true, widthDp = 412, heightDp = 900)
@Composable
internal fun ChatTurnLightZh() {
    ChatFrame(dark = false) { Messages(completedTurn) }
}

@PreviewTest
@Preview(name = "chat turn dark en", showBackground = true, widthDp = 412, heightDp = 900)
@Composable
internal fun ChatTurnDarkEn() {
    ChatFrame(dark = true, english = true) { Messages(completedTurn) }
}

@PreviewTest
@Preview(name = "chat turn light large", showBackground = true, widthDp = 412, heightDp = 1100, fontScale = 1.3f)
@Composable
internal fun ChatTurnLightLarge() {
    ChatFrame(dark = false) { Messages(completedTurn) }
}

@PreviewTest
@Preview(name = "chat streaming dark", showBackground = true, widthDp = 412, heightDp = 520)
@Composable
internal fun ChatStreamingDark() {
    ChatFrame(dark = true, english = true) { Messages(streamingTurn, running = true) }
}

@PreviewTest
@Preview(name = "chat approvals light zh", showBackground = true, widthDp = 412, heightDp = 760)
@Composable
internal fun ChatApprovalsLightZh() {
    ChatFrame(dark = false) { Messages(approvals) }
}

@PreviewTest
@Preview(name = "chat approvals dark en", showBackground = true, widthDp = 412, heightDp = 760)
@Composable
internal fun ChatApprovalsDarkEn() {
    ChatFrame(dark = true, english = true) { Messages(approvals) }
}

@PreviewTest
@Preview(name = "chat question light en", showBackground = true, widthDp = 412, heightDp = 640)
@Composable
internal fun ChatQuestionLightEn() {
    ChatFrame(dark = false, english = true) { Messages(listOf(question)) }
}

@PreviewTest
@Preview(name = "chat question dark zh", showBackground = true, widthDp = 412, heightDp = 640)
@Composable
internal fun ChatQuestionDarkZh() {
    ChatFrame(dark = true) { Messages(listOf(question)) }
}
