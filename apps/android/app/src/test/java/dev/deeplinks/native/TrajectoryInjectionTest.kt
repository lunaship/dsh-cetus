package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 轨迹里 DSH 注入的上下文不冒充用户 / 助手，也不另起回合。 */
class TrajectoryInjectionTest {

    @Test
    fun `runtime context is labelled as context injection`() {
        val msg = MobileMessage(id = "a", role = "assistant", text = "Current runtime context: cwd=/tmp")
        assertEquals(TRACE_ROLE_CONTEXT_INJECTION, traceDisplayRole(msg))
    }

    @Test
    fun `system reminder sent as user does not start a turn`() {
        val injected = MobileMessage(id = "b", role = "user", text = "<system-reminder>x</system-reminder>")
        assertFalse(isTraceTurnStart(injected))
        assertTrue(isTraceTurnStart(MobileMessage(id = "c", role = "user", text = "帮我修个 bug")))
    }

    @Test
    fun `goal round stays visible with its own role`() {
        val goal = MobileMessage(id = "d", role = "user", text = "<goal_round>继续</goal_round>")
        assertEquals("user", traceDisplayRole(goal))
    }
}
