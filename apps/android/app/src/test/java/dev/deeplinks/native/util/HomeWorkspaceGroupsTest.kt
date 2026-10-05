package dev.deeplinks.native.util

import dev.deeplinks.native.MobileSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWorkspaceGroupsTest {
    private fun session(id: String, cwd: String? = "/projects/a", updated: Long = 100) = MobileSession(
        sessionId = id, title = id, updatedAt = updated, running = false,
        blank = false, cwd = cwd, agentPreset = null,
    )

    @Test
    fun `registered membership wins over cwd and each session appears once`() {
        val groups = homeWorkspaceGroups(
            listOf(session("owned"), session("loose")), listOf("/projects/a", "/projects/b"),
            listOf(WorkspaceAccount("/projects/b", listOf("owned"))), registryReady = true,
        )
        assertTrue(groups[0].sessions.isEmpty())
        assertEquals(listOf("owned"), groups[1].sessions.map { it.sessionId })
        assertEquals(listOf("loose"), groups[2].sessions.map { it.sessionId })
        assertEquals(null, groups[2].path)
    }

    @Test
    fun `empty registered folders remain visible without an ungrouped placeholder`() {
        val groups = homeWorkspaceGroups(emptyList(), listOf("/projects/a/", "/projects/a"), emptyList(), true)
        assertEquals(listOf("/projects/a"), groups.map { it.path })
        assertTrue(groups.single().sessions.isEmpty())
    }

    @Test
    fun `cwd fallback only applies until the registry arrives`() {
        val sessions = listOf(session("a"))
        assertEquals("a", homeWorkspaceGroups(sessions, listOf("/projects/a"), emptyList(), false)[0].sessions.single().sessionId)
        assertTrue(homeWorkspaceGroups(sessions, listOf("/projects/a"), emptyList(), true)[0].sessions.isEmpty())
    }

    @Test
    fun `latest duplicate wins and second timestamps sort with millisecond timestamps`() {
        val groups = homeWorkspaceGroups(
            listOf(session("dup", updated = 1_700_000_000), session("new", updated = 1_700_000_002_000),
                session("dup", updated = 1_700_000_003_000)),
            listOf("/projects/a"), emptyList(), false,
        )
        assertEquals(listOf("dup", "new"), groups.single().sessions.map { it.sessionId })
        assertEquals(1_700_000_003_000, groups.single().sessions.first().updatedAt)
    }

    @Test
    fun `unknown owner does not disappear and a waiting session is not counted twice`() {
        val groups = homeWorkspaceGroups(
            listOf(session("unknown").copy(running = true, awaitingInput = true)),
            listOf("/projects/a"), listOf(WorkspaceAccount("/missing", listOf("unknown"))), true,
        )
        val ungrouped = groups.last()
        assertEquals(null, ungrouped.path)
        assertEquals(1, ungrouped.awaitingCount)
        assertEquals(0, ungrouped.runningCount)
    }
}
