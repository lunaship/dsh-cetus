package dev.deeplinks.native

import dev.deeplinks.native.ui.v4.DlStatusKind
import dev.deeplinks.native.util.StreamBannerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对话页状态槽（v4 4.1 / 4.5 / 4.8）：一次只显示一条，断线 > 目标 > 预览。 */
class SessionStatusSlotTest {

    private val goal = SessionGoal(SessionGoalRef("g1", 1), "把审批状态做成双向同步", "active", maxGoalRounds = 8, roundsStarted = 3)
    private val plan = listOf(
        MobileTodoItem("定位", "completed"),
        MobileTodoItem("补单测", "in_progress"),
        MobileTodoItem("真机验证", "pending"),
    )

    private fun status(
        stream: StreamBannerKind = StreamBannerKind.Hidden,
        host: String? = null,
        goal: SessionGoal? = null,
        summary: String? = null,
        plan: List<MobileTodoItem> = emptyList(),
        running: Boolean = false,
        ports: List<Int> = emptyList(),
    ) = sessionStatus(stream, host, goal, summary, plan, running, ports)

    @Test
    fun nothingToShowGivesNoSlot() {
        assertNull(status())
    }

    @Test
    fun reconnectBeatsGoalAndPreview() {
        val s = status(stream = StreamBannerKind.Retrying, goal = goal, plan = plan, ports = listOf(5173))
        assertEquals(DlStatusKind.Disconnected, s?.kind)
        assertTrue(!(s as SessionStatus.Offline).failed)
    }

    @Test
    fun failedReconnectIsMarkedFailed() {
        val s = status(stream = StreamBannerKind.Failed) as SessionStatus.Offline
        assertTrue(s.failed)
        assertEquals(1, s.actions.size)
    }

    @Test
    fun unreachableHostOffersDeviceAndRetry() {
        val s = status(host = "mac", goal = goal) as SessionStatus.Offline
        assertEquals(2, s.actions.size)
    }

    @Test
    fun goalAndPlanMergeIntoOneSlotAheadOfPreview() {
        val s = status(goal = goal, plan = plan, ports = listOf(5173)) as SessionStatus.Goal
        assertEquals(goal, s.goal)
        assertEquals(plan, s.plan)
    }

    @Test
    fun inferredGoalOnlyWhileRunning() {
        assertNull(status(summary = "修登录"))
        val s = status(summary = "修登录", running = true) as SessionStatus.Goal
        assertEquals("修登录", s.summary)
        // 有结构化目标时不再重复推断出的目标
        assertNull((status(goal = goal, summary = "修登录", running = true) as SessionStatus.Goal).summary)
    }

    @Test
    fun previewShowsWhenNothingElse() {
        assertEquals(DlStatusKind.Preview, status(ports = listOf(3000))?.kind)
    }

    @Test
    fun collapsedLineCountsRoundsAndPlan() {
        val title = goalSlotTitle(goal, null, plan)
        assertTrue(title, title.contains("1/3"))
        assertTrue(title, title.contains("3"))
    }

    @Test
    fun dockLineShowsCurrentStepThenObjective() {
        assertEquals("补单测", goalDockLine(goal, null, plan))
        assertEquals(goal.objective, goalDockLine(goal, null, emptyList()))
        assertEquals("修登录", goalDockLine(null, "修登录", emptyList()))
    }

    @Test
    fun completedGoalAndFinishedPlanAreHidden() {
        val done = goal.copy(phase = "complete")
        assertNull(goalStatus(done, null, emptyList(), running = false))
        val finished = plan.map { it.copy(status = "completed") }
        assertNull(goalStatus(done, null, finished, running = false))
        // 计划还没做完时继续显示计划，但不再显示已完成的目标
        val s = goalStatus(done, null, plan, running = false)
        assertNull(s?.goal)
        assertEquals(plan, s?.plan)
    }

    @Test
    fun goalStatusIsSeparateFromTopSlot() {
        assertNull(goalStatus(null, null, emptyList(), running = false))
        assertEquals(plan, goalStatus(null, null, plan, running = false)?.plan)
        assertNull(goalStatus(null, "修登录", emptyList(), running = false))
        assertEquals("修登录", goalStatus(null, "修登录", emptyList(), running = true)?.summary)
    }
}
