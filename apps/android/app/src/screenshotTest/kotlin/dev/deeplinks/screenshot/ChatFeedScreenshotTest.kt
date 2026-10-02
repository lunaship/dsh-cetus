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
import dev.deeplinks.core.DshStringsEn
import dev.deeplinks.core.DshStringsZh
import dev.deeplinks.core.LightDshColors
import dev.deeplinks.core.LocalDshColors
import dev.deeplinks.core.LocalDshStrings
import dev.deeplinks.core.LocaleManager
import dev.deeplinks.core.dshTypography
import dev.deeplinks.native.DshSpace
import dev.deeplinks.native.ChangedFile
import dev.deeplinks.native.WorkspaceChangesSummary
import dev.deeplinks.native.ROLE_WORKSPACE_CHANGES
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.deeplinks.native.ChatFeedActions
import dev.deeplinks.native.MobileApiClient
import dev.deeplinks.native.chatMessageItems
import dev.deeplinks.native.resolveSweepingId
import dev.deeplinks.native.util.groupMessages
import dev.deeplinks.native.util.MessageGroup
import dev.deeplinks.native.ToolGroupHeader
import dev.deeplinks.native.MobileMessage

/**
 * 对话流：一轮完整回复、流式进行中、审批卡、提问卡。
 * 只用确定性内容（无数学 / Mermaid：它们走 WebView 异步渲染，截图不稳定）。
 */

@Composable
private fun ChatFrame(dark: Boolean, english: Boolean = false, content: @Composable () -> Unit) {
    // E1：同一屏里直接读全局 LocaleManager.strings 的组件（TimeLabels 的相对时间、
    // ApprovalCard 的「允许一次」等）不会因为注入本地 LocalDshStrings 而变语言。
    // 渲染环境（layoutlib）没有可用的 SharedPreferences，走 setLanguageForPreview
    // 只切内存态，保证英文预览里不再混中文。
    LocaleManager.setLanguageForPreview(if (english) "en" else "zh")
    val typography = dshTypography()
    MaterialTheme(typography = typography) {
        CompositionLocalProvider(
            LocalDshColors provides if (dark) DarkDshColors else LightDshColors,
            LocalDshStrings provides if (english) DshStringsEn else DshStringsZh,
            LocalTextStyle provides typography.bodyMedium,
        ) {
            // v3：聊天画布是白底（bgCard），与生产 WorkspaceScreen 一致
            Box(modifier = Modifier.fillMaxSize().background(Dsh.bgCard)) {
                Column(
                    modifier = Modifier.padding(DshSpace.s16),
                    verticalArrangement = Arrangement.spacedBy(DshSpace.s12),
                ) { content() }
            }
        }
    }
}

/**
 * 预览用的消息流动作：不连真实主机，回调全部空实现。
 * 与 [ChatPage] 共用，保证逐条墙与整页墙走同一条生产渲染路径。
 */
@Composable
private fun rememberPreviewChatActions(messages: List<MobileMessage>, running: Boolean = false): ChatFeedActions {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, messages, running) {
        ChatFeedActions(
            client = MobileApiClient(PreviewHost),
            scope = scope,
            context = context,
            host = PreviewHost,
            currentSessionId = { null },
            messages = { messages },
            setMessages = {},
            olderMessages = { emptyList() },
            composerText = { "" },
            setComposerText = {},
            setComposerError = {},
            isRunning = { running },
            busyEnter = { "queue" },
            isFeedbackSupported = { false },
            feedbackFor = { null },
            updateFeedback = {},
            refreshSessions = {},
            fork = {},
        )
    }
}

/**
 * v3：逐条墙也走生产消息流（groupMessages → chatMessageItems：过程折叠、思考并入活动行、
 * 轮末元信息），不再逐条直接画 MessageItem——旧写法绕过折叠，基线与真机不一致。
 */
@Composable
private fun Messages(messages: List<MobileMessage>, running: Boolean = false) {
    val actions = rememberPreviewChatActions(messages, running)
    LazyColumn(
        userScrollEnabled = false,
        verticalArrangement = Arrangement.spacedBy(DshSpace.s12),
    ) {
        chatMessageItems(
            visibleGroups = groupMessages(messages),
            sweepingId = resolveSweepingId(messages, running),
            actions = actions,
            isRunning = running,
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
    // 系统提示（模型切换）：它挂在 user/message 里但不是用户说的话，应渲染成安静的居中一行
    MobileMessage(
        id = "n1",
        role = "system_notice",
        text = "[model changed: assistant turns above this point were generated by step-3.7-flash]",
    ),
    // 整页墙要展示改动卡（稿 03 里正文之后就是它），所以这一轮补一条 workspace_changes
    MobileMessage(
        id = "c1",
        role = ROLE_WORKSPACE_CHANGES,
        text = "",
        changes = WorkspaceChangesSummary(
            seq = 120,
            turn = 7,
            total = 6,
            added = 148,
            deleted = 37,
            files = listOf(
                // 注意 ChangedFile.name 取的是 display 的最后一段，所以 display 必须是**完整文件路径**
                // （第一版我传了目录，卡片就把目录名当成了文件名——样例的坑，不是产品的坑）
                ChangedFile("src/workspace-changes.js", "../dsh-links/src/workspace-changes.js", added = 118),
                ChangedFile(
                    "WorkspaceChangesPanel.kt",
                    "app/src/main/java/dev/deeplinks/native/WorkspaceChangesPanel.kt",
                    added = 22,
                    deleted = 9,
                ),
            ),
        ),
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
        // 墙上画的是「手机接管」态（稿 07 的可交互审批卡）；未接管会让卡片降级成一行只读说明
        takenOverByPhone = true,
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
@Preview(name = "chat streaming light zh", showBackground = true, widthDp = 412, heightDp = 520)
@Composable
internal fun ChatStreamingLightZh() {
    // 方案阶段 10：执行中也要有浅色一张——原先只有深色，"执行中"这一态的浅色配色没有基线。
    ChatFrame(dark = false) { Messages(streamingTurn, running = true) }
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

/**
 * 过程折叠行（方案 5.2）：一轮里的工具调用收成一行，已结束写「已完成工作 · 摘要」，
 * 执行中写「◌ 正在运行 …」。这一行此前不在任何截图墙里，所以它的改动没法目检——
 * 这两张墙就是为了把它纳入基线。
 */
@Composable
private fun ProcessRows(dark: Boolean, english: Boolean) {
    ChatFrame(dark = dark, english = english) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Dsh.bgCard)
                .padding(DshSpace.s16),
            verticalArrangement = Arrangement.spacedBy(DshSpace.s8),
        ) {
            ToolGroupHeader(
                group = MessageGroup.ToolGroup(
                    listOf(
                        MobileMessage(id = "p1", role = "tool_call", text = "", toolName = "Read"),
                        MobileMessage(id = "p2", role = "tool_call", text = "", toolName = "Read"),
                    ),
                ),
                sweepingId = null,
            )
            ToolGroupHeader(
                group = MessageGroup.ToolGroup(
                    listOf(MobileMessage(id = "p3", role = "tool_call", text = "", toolName = "bash")),
                ),
                sweepingId = "p3",
            )
            // 有改动的组：折叠行写「已完成工作 · 已编辑 N 个文件」（稿 03 的口吻），
            // 而不是工具名与次数——两行并排才看得出这条分支真的生效了
            ToolGroupHeader(
                group = MessageGroup.ToolGroup(
                    listOf(
                        MobileMessage(
                            id = "p4", role = "tool_call", text = "", toolName = "edit",
                            toolArgs = """{"file_path":"/a/HomeHub.kt","old_str":"x","new_str":"y"}""",
                        ),
                        MobileMessage(
                            id = "p5", role = "tool_call", text = "", toolName = "write",
                            toolArgs = """{"file_path":"/a/DshTheme.kt","content":"...","file_path2":""}""",
                        ),
                    ),
                ),
                sweepingId = null,
            )
        }
    }
}

@PreviewTest
@Preview(name = "process row light zh", showBackground = true, widthDp = 412, heightDp = 220)
@Composable
internal fun ProcessRowLightZh() {
    ProcessRows(dark = false, english = false)
}

@PreviewTest
@Preview(name = "process row dark en", showBackground = true, widthDp = 412, heightDp = 220)
@Composable
internal fun ProcessRowDarkEn() {
    ProcessRows(dark = true, english = true)
}

/**
 * 整屏对话页（方案阶段 10 第 4 项「与设计稿并排」要的那张图）：
 * 分组后的消息流（含过程折叠行、改动卡、轮末行）+ 底部区，与稿 03 同构。
 *
 * 之前只有逐条消息的墙，所以对话页没法与整页稿并排——README 里把这条记成覆盖边界。
 */
@Composable
private fun ChatPage(dark: Boolean, english: Boolean) {
    ChatFrame(dark = dark, english = english) {
        val actions = rememberPreviewChatActions(completedTurn)
        Column(modifier = Modifier.fillMaxSize().background(Dsh.bgCard)) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(DshSpace.s12),
            ) {
                chatMessageItems(
                    visibleGroups = groupMessages(completedTurn),
                    sweepingId = null,
                    actions = actions,
                )
            }
            ChatBottomWall()
        }
    }
}

@PreviewTest
@Preview(name = "chat page light zh", showBackground = true, widthDp = 412, heightDp = 900)
@Composable
internal fun ChatPageLightZh() {
    ChatPage(dark = false, english = false)
}
