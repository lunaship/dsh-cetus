package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskProgressTest {
    private val labels = TaskProgressLabels(
        running = "执行中",
        awaitingApproval = "等你审批",
        awaitingInput = "等你回答",
        completed = "已完成",
        step = "第 %d 步",
        elapsed = "已用 %s",
        publicText = "cetus · 任务执行中",
    )

    @Test
    fun `running without todos uses an indeterminate bar`() {
        val content = taskProgressContent(
            TaskProgressSnapshot(title = "修登录", phase = TaskMonitorPhase.Running, step = 3, elapsedMs = 1_200),
            labels,
        )
        assertEquals("修登录", content.title)
        assertEquals("执行中 · 第 3 步 · 已用 1.2s", content.text)
        assertTrue(content.indeterminate)
        assertEquals(0, content.progress)
        assertEquals("cetus · 任务执行中", content.publicText)
    }

    @Test
    fun `awaiting approval and awaiting input keep their status`() {
        val approval = taskProgressContent(
            TaskProgressSnapshot(title = "修登录", phase = TaskMonitorPhase.AwaitingApproval),
            labels,
        )
        val input = taskProgressContent(
            TaskProgressSnapshot(title = "修登录", phase = TaskMonitorPhase.AwaitingInput),
            labels,
        )
        assertEquals("等你审批", approval.text)
        assertEquals("等你回答", input.text)
        assertTrue(approval.indeterminate)
        assertTrue(input.indeterminate)
    }

    @Test
    fun `completed status is its own line`() {
        val content = taskProgressContent(
            TaskProgressSnapshot(title = "修登录", phase = TaskMonitorPhase.Completed, elapsedMs = 90_000),
            labels,
        )
        assertEquals("已完成 · 已用 2m", content.text)
    }

    @Test
    fun `partial todos set a determinate bar`() {
        val content = taskProgressContent(
            TaskProgressSnapshot(
                title = "修登录",
                phase = TaskMonitorPhase.Running,
                todosDone = 2,
                todosTotal = 5,
            ),
            labels,
        )
        assertFalse(content.indeterminate)
        assertEquals(2, content.progress)
        assertEquals(5, content.progressMax)
        assertEquals("执行中 · 2/5", content.text)
    }

    @Test
    fun `finished todos fill the bar`() {
        val content = taskProgressContent(
            TaskProgressSnapshot(
                title = "修登录",
                phase = TaskMonitorPhase.Completed,
                todosDone = 3,
                todosTotal = 3,
            ),
            labels,
        )
        assertFalse(content.indeterminate)
        assertEquals(3, content.progress)
        assertEquals(3, content.progressMax)
        assertEquals("已完成 · 3/3", content.text)
    }

    @Test
    fun `streaming fragments do not change the notification`() {
        val state = TaskProgressState(title = "修登录", step = 2)
        val next = reduceTaskProgress(state, TaskProgressEvent.Ignored("assistant/chunk"))
        assertEquals(state, next)
        assertFalse(taskProgressEventChangesNotification(TaskProgressEvent.Ignored("assistant/chunk")))
        assertTrue(taskProgressEventChangesNotification(TaskProgressEvent.ApprovalAsked))
    }

    @Test
    fun `the same notification updates at most once every two seconds`() {
        assertFalse(shouldPostTaskProgress(1_000, 2_999, force = false))
        assertTrue(shouldPostTaskProgress(1_000, 3_000, force = false))
        assertTrue(shouldPostTaskProgress(1_000, 1_100, force = true))
    }

    @Test
    fun `approval outranks a pending question until it is decided`() {
        var state = TaskProgressState(title = "修登录")
        state = reduceTaskProgress(state, TaskProgressEvent.QuestionAsked)
        state = reduceTaskProgress(state, TaskProgressEvent.ApprovalAsked)
        assertEquals(TaskMonitorPhase.AwaitingApproval, state.snapshot(0).phase)
        state = reduceTaskProgress(state, TaskProgressEvent.ApprovalDecided)
        assertEquals(TaskMonitorPhase.AwaitingInput, state.snapshot(0).phase)
        state = reduceTaskProgress(state, TaskProgressEvent.Todos(done = 3, total = 3))
        assertEquals(3, state.snapshot(0).todosDone)
        assertEquals(3, state.snapshot(0).todosTotal)
    }
}
