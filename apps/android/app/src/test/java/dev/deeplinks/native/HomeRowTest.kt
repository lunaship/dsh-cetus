package dev.deeplinks.native

import dev.deeplinks.core.L
import dev.deeplinks.core.homeDone
import dev.deeplinks.core.homeQuestionPreview
import dev.deeplinks.core.homeWaitingAnswer
import dev.deeplinks.native.ui.v4.DlTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4 首页收件箱条目文字（2.1）：状态 · 工作区；进行中写当前步骤（不写状态词，靠转圈），
 * 结束写「完成」或怎么停的 + 结果一句话；当前会话的审批 / 提问给内联动作。
 */
class HomeRowTest {

    private fun session(
        running: Boolean = false,
        awaiting: Boolean = false,
        activity: MobileSessionActivity? = null,
        lastResult: MobileSessionResult? = null,
        stoppedReason: String? = null,
    ) = MobileSession(
        sessionId = "s1",
        title = "会话",
        updatedAt = 0L,
        running = running,
        blank = false,
        cwd = "/Users/me/project",
        agentPreset = null,
        awaitingInput = awaiting,
        activity = activity,
        lastResult = lastResult,
        stoppedReason = stoppedReason,
    )

    @Test
    fun runningRowShowsStepInPreviewWithoutStatusWord() {
        val texts = homeInboxTexts(
            session(running = true, activity = MobileSessionActivity(kind = "tool", label = "go test ./...", step = 12)),
            pending = null,
            goalSummary = null,
        )
        assertNull(texts.status)
        assertEquals("project", texts.workspace)
        assertEquals(L.homeRunningInline.format("go test ./...") + " · " + L.homeStepLabel.format(12), texts.preview)
        assertTrue(texts.running)
    }

    @Test
    fun runningWithoutActivityFallsBackToGoalThenRunningLabel() {
        assertEquals("修一下登录", homeInboxTexts(session(running = true), null, "修一下登录").preview)
        assertEquals(L.runningStatus, homeInboxTexts(session(running = true), null, null).preview)
    }

    @Test
    fun awaitingRowSaysWaitingApprovalInWaitTone() {
        val texts = homeInboxTexts(session(awaiting = true, running = true), null, null)
        assertEquals(L.homeChipWaitingApproval, texts.status)
        assertEquals(DlTone.Wait, texts.tone)
        assertEquals(HomePendingKind.None, texts.pending)
    }

    @Test
    fun phoneApprovalShowsCommandAndInlineActions() {
        val approval = MobileMessage(
            id = "a1", role = "approval", text = "", approvalId = "a-1",
            toolName = "bash", toolArgs = """{"command":"./gradlew test"}""",
        )
        val texts = homeInboxTexts(session(awaiting = true), approval, null)
        assertEquals(HomePendingKind.Approval, texts.pending)
        assertEquals("./gradlew test", texts.command)
        val noArgs = homeInboxTexts(session(awaiting = true), approval.copy(toolArgs = null), null)
        assertEquals("bash", noArgs.command)
    }

    @Test
    fun phoneQuestionShowsPromptAndAnswerAction() {
        val question = MobileMessage(
            id = "q1", role = "question", text = "", questionRpcId = "r1",
            questionPayloadJson = """[{"id":"x","question":"限流设成多少？","options":["60","120"]}]""",
        )
        val texts = homeInboxTexts(session(awaiting = true), question, null)
        assertEquals(L.homeWaitingAnswer, texts.status)
        assertEquals(HomePendingKind.Question, texts.pending)
        assertEquals(L.homeQuestionPreview.format("限流设成多少？"), texts.preview)
    }

    @Test
    fun doneRowSaysDoneAndShowsResult() {
        val texts = homeInboxTexts(session(lastResult = MobileSessionResult(text = "门禁全绿", files = 79)), null, null)
        assertEquals(L.homeDone, texts.status)
        assertEquals(DlTone.Ok, texts.tone)
        assertEquals("门禁全绿", texts.preview)
    }

    @Test
    fun interruptedRowSaysHowItStopped() {
        val texts = homeInboxTexts(
            session(stoppedReason = "interrupted", lastResult = MobileSessionResult(text = "跑到一半")),
            null,
            null,
        )
        assertEquals(stoppedReasonLabel("interrupted"), texts.status)
        assertEquals(DlTone.Off, texts.tone)
        assertEquals("跑到一半", texts.preview)
    }

    /** 2.3：离线时进行中行是缓存状态，加「最后看到：」前缀，不转圈。 */
    @Test
    fun offlineRunningRowIsPrefixedWithLastSeen() {
        val texts = homeInboxTexts(
            session(running = true, activity = MobileSessionActivity(kind = "tool", label = "go test ./...", step = 12)),
            pending = null,
            goalSummary = null,
            offline = true,
        )
        assertEquals(
            L.homeLastSeenPrefix + L.homeRunningInline.format("go test ./...") + " · " + L.homeStepLabel.format(12),
            texts.preview,
        )
        assertFalse(texts.running)
    }

    @Test
    fun offlineDoesNotPrefixRecentRows() {
        val texts = homeInboxTexts(session(lastResult = MobileSessionResult(text = "门禁全绿")), null, null, offline = true)
        assertEquals("门禁全绿", texts.preview)
    }
}
