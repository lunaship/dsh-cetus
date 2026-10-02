package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentTreeTest {
    private fun session(
        id: String,
        origin: String? = null,
        parent: String? = null,
        running: Boolean = false,
        updatedAt: Long = 0L,
    ) = MobileSession(
        sessionId = id,
        title = id,
        updatedAt = updatedAt,
        running = running,
        blank = false,
        cwd = null,
        agentPreset = null,
        origin = origin,
        parentSessionId = parent,
    )

    @Test
    fun treeNestsByParentAndSkipsWhenTheFieldIsAbsent() {
        val sessions = listOf(
            session("root"),
            session("a", origin = "subagent", parent = "root", updatedAt = 2),
            session("b", origin = "subagent", parent = "root", running = true, updatedAt = 1),
            session("a1", origin = "subagent", parent = "a", running = true),
            session("other", origin = "subagent", parent = "else"),
        )
        val tree = buildSubagentTree(sessions, "root")
        assertEquals(listOf("a", "b"), tree.map { it.sessionId })
        assertEquals(listOf("a1"), tree.first().children.map { it.sessionId })
        assertEquals(2, runningSubagentCount(sessions, "root"))
        assertTrue(hostExposesSubagentParent(sessions))
        assertFalse(hostExposesSubagentParent(listOf(session("root"), session("plain"))))
        assertTrue(hostExposesSubagentParent(emptyList()))
        assertEquals(emptyList<SubagentNode>(), buildSubagentTree(listOf(session("root")), "root"))
    }
}
