package dev.deeplinks.native.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceListsTest {
    @Test
    fun isUserWorkspace_rejectsSystemPaths() {
        assertFalse(isUserWorkspace("/Users/me/Library/Caches"))
        assertFalse(isUserWorkspace("/tmp/foo"))
        assertFalse(isUserWorkspace("/Users/me/proj/node_modules/pkg"))
        assertTrue(isUserWorkspace("/Volumes/Space/Dev/dsh-links"))
        assertTrue(isUserWorkspace(null))
    }

    @Test
    fun visibleUserWorkspaces_mergesRegistryAndSessions_whenNotRequireRegistered() {
        val out = visibleUserWorkspaces(
            sessionCwds = listOf("/Volumes/Space/Dev/DeepHarness", "/Volumes/Space/Dev/DeepHarness"),
            deletedWorkspaces = emptySet(),
            registeredPaths = listOf(
                "/Volumes/Space/Dev/dsh-links",
                "/Volumes/Space/Dev/dsh-chat",
                "/Volumes/Space/Dev/DeepHarness",
            ),
        )
        assertEquals(
            listOf(
                "/Volumes/Space/Dev/DeepHarness",
                "/Volumes/Space/Dev/dsh-chat",
                "/Volumes/Space/Dev/dsh-links",
            ),
            out,
        )
    }

    @Test
    fun visibleUserWorkspaces_requireRegistered_ignoresOrphanSessionCwds() {
        val out = visibleUserWorkspaces(
            sessionCwds = listOf(
                "/Volumes/Space/Dev/DeepHarness",
                "/Volumes/Space/Dev/Kept",
                "/Users/me",
            ),
            deletedWorkspaces = emptySet(),
            registeredPaths = listOf(
                "/Volumes/Space/Dev/dsh-links",
                "/Volumes/Space/Dev/dsh-links/relay",
            ),
            requireRegistered = true,
        )
        assertEquals(
            listOf(
                "/Volumes/Space/Dev/dsh-links",
                "/Volumes/Space/Dev/dsh-links/relay",
            ),
            out,
        )
    }

    @Test
    fun visibleUserWorkspaces_excludesDeleted() {
        val out = visibleUserWorkspaces(
            sessionCwds = listOf("/a/gone", "/a/keep"),
            deletedWorkspaces = setOf("/a/gone"),
            registeredPaths = listOf("/a/gone", "/a/keep", "/a/empty"),
            requireRegistered = true,
        )
        assertEquals(listOf("/a/empty", "/a/keep"), out)
    }

    @Test
    fun visibleSidebarWorkspaces_keepsNewRegisteredWorkspaceWithoutSessions() {
        val out = visibleSidebarWorkspaces(
            knownWorkspaces = listOf("/a/existing", "/a/new"),
            workspacesWithVisibleSessions = setOf("/a/existing"),
            searchQuery = "",
            sessionFilterActive = false,
        )

        assertEquals(listOf("/a/existing", "/a/new"), out)
    }

    @Test
    fun visibleSidebarWorkspaces_filtersEmptyWorkspaceOnlyForActiveSessionFilter() {
        val out = visibleSidebarWorkspaces(
            knownWorkspaces = listOf("/a/existing", "/a/new"),
            workspacesWithVisibleSessions = setOf("/a/existing"),
            searchQuery = "",
            sessionFilterActive = true,
        )

        assertEquals(listOf("/a/existing"), out)
    }

    @Test
    fun isSessionWorkspaceVisible_hidesUnregisteredWhenReady() {
        assertFalse(
            isSessionWorkspaceVisible(
                cwd = "/Volumes/Space/Dev/Kept",
                deletedWorkspaces = emptySet(),
                registeredPaths = listOf("/Volumes/Space/Dev/dsh-links"),
                registryReady = true,
            ),
        )
        assertTrue(
            isSessionWorkspaceVisible(
                cwd = "/Volumes/Space/Dev/dsh-links",
                deletedWorkspaces = emptySet(),
                registeredPaths = listOf("/Volumes/Space/Dev/dsh-links"),
                registryReady = true,
            ),
        )
        // 注册表未就绪时，仍允许会话 cwd 临时显示
        assertTrue(
            isSessionWorkspaceVisible(
                cwd = "/Volumes/Space/Dev/Kept",
                deletedWorkspaces = emptySet(),
                registeredPaths = emptyList(),
                registryReady = false,
            ),
        )
    }

    @Test
    fun workspaceGroupKey_usesMembershipNotCwd() {
        val accounts = listOf(
            WorkspaceAccount("/Volumes/Space/Dev/dsh-links/relay", listOf("in-folder")),
        )
        assertEquals(
            "/Volumes/Space/Dev/dsh-links/relay",
            workspaceGroupKey("in-folder", accounts),
        )
        assertEquals(null, workspaceGroupKey("cwd-only", accounts))
    }

    @Test
    fun workspaceGroupKey_deletedPathBecomesUngrouped() {
        val accounts = listOf(WorkspaceAccount("/a/relay", listOf("s1")))
        assertEquals(
            null,
            workspaceGroupKey("s1", accounts, deletedWorkspaces = setOf("/a/relay")),
        )
    }

    @Test
    fun reconcileDeletedWorkspaces_unhidesWhenReregistered() {
        val out = reconcileDeletedWorkspaces(
            nextRegistered = listOf("/a/keep", "/a/back"),
            deletedWorkspaces = setOf("/a/gone", "/a/back"),
        )
        assertEquals(setOf("/a/gone"), out)
    }

    // ----- workspaceDisplayName（W1） -----

    @Test
    fun workspaceDisplayName_usesLastSegmentAndFallsBackToFullPath() {
        assertEquals("perch", workspaceDisplayName("/Volumes/Space/Dev/perch"))
        assertEquals("perch", workspaceDisplayName("/Volumes/Space/Dev/perch/"))
        assertEquals("/", workspaceDisplayName("/"))
    }

    // ----- sessionsInWorkspace（W3） -----

    private fun session(id: String, cwd: String?): dev.deeplinks.native.MobileSession =
        dev.deeplinks.native.MobileSession(
            sessionId = id,
            title = id,
            updatedAt = 0,
            running = false,
            blank = false,
            cwd = cwd,
            agentPreset = null,
        )

    @Test
    fun sessionsInWorkspace_matchesSessionIds() {
        val accounts = listOf(WorkspaceAccount("/a/perch", listOf("s1")))
        val out = sessionsInWorkspace(listOf(session("s1", null), session("s2", "/a/other")), "/a/perch", accounts, emptySet())
        assertEquals(listOf("s1"), out.map { it.sessionId })
    }

    @Test
    fun sessionsInWorkspace_fallsBackToCwdIncludingTrailingSlash() {
        val out = sessionsInWorkspace(
            listOf(session("s1", "/a/perch/"), session("s2", "/a/other")),
            "/a/perch",
            emptyList(),
            emptySet(),
        )
        assertEquals(listOf("s1"), out.map { it.sessionId })
    }

    @Test
    fun sessionsInWorkspace_cwdClaimedByAnotherWorkspaceBelongsToThatOneOnly() {
        val accounts = listOf(WorkspaceAccount("/b/other", listOf("s1")))
        val sessions = listOf(session("s1", "/a/perch"))
        assertEquals(emptyList<dev.deeplinks.native.MobileSession>(), sessionsInWorkspace(sessions, "/a/perch", accounts, emptySet()))
        assertEquals(listOf("s1"), sessionsInWorkspace(sessions, "/b/other", accounts, emptySet()).map { it.sessionId })
    }

    @Test
    fun sessionsInWorkspace_deletedWorkspaceHasNoSessions() {
        val out = sessionsInWorkspace(
            listOf(session("s1", "/a/perch")),
            "/a/perch",
            emptyList(),
            deletedWorkspaces = setOf("/a/perch"),
        )
        assertEquals(emptyList<dev.deeplinks.native.MobileSession>(), out)
    }

    @Test
    fun sessionsInWorkspace_ignoresNonUserDirectory() {
        val out = sessionsInWorkspace(
            listOf(session("s1", "/tmp/foo")),
            "/tmp/foo",
            emptyList(),
            emptySet(),
        )
        assertEquals(emptyList<dev.deeplinks.native.MobileSession>(), out)
    }
}

/** S2：冷启动去重的判定。 */
class ColdStartRefreshTest {

    @Test
    fun `同步进行中时跳过`() {
        assertTrue(shouldSkipResumeRefresh(now = 1_000, coldStartSyncAt = 0, syncInFlight = true))
    }

    @Test
    fun `刚同步完成 10 秒内跳过`() {
        assertTrue(shouldSkipResumeRefresh(now = 5_000, coldStartSyncAt = 4_000, syncInFlight = false))
    }

    @Test
    fun `超过 10 秒不再跳过`() {
        assertFalse(shouldSkipResumeRefresh(now = 20_000, coldStartSyncAt = 4_000, syncInFlight = false))
    }

    @Test
    fun `从未冷启动同步过就不跳过`() {
        assertFalse(shouldSkipResumeRefresh(now = 1_000, coldStartSyncAt = 0, syncInFlight = false))
    }
}
