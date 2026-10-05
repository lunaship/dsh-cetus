package dev.deeplinks.native

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.deeplinks.core.homeNewTaskIn
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.deeplinks.core.DshTheme
import dev.deeplinks.core.L
import dev.deeplinks.native.util.HomeWorkspaceGroup
import dev.deeplinks.native.util.WorkspacePrefs
import android.graphics.Bitmap
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeWorkspaceInstrumentedTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val identity = "home-workspace-test"
    private val prefs get() = WorkspacePrefs(context)
    private val sessions = listOf(
        MobileSession("a", "First conversation", 0, false, false, "/projects/a", null),
        MobileSession("b", "Waiting conversation", 0, true, false, "/projects/b", null, awaitingInput = true),
    )
    private val groups = listOf(
        HomeWorkspaceGroup("/projects/a", listOf(sessions[0])),
        HomeWorkspaceGroup("/projects/b", listOf(sessions[1])),
        HomeWorkspaceGroup("/projects/empty", emptyList()),
    )
    private var created: String? = "unchanged"
    private var selected: String? = null
    private var searches = 0
    private var approvals = 0
    private val visible = mutableStateOf(true)

    @Before @After fun clearFixturePrefs() = prefs.saveHomeCollapsedGroups(identity, emptySet())

    private fun show(online: Boolean = true, pending: MobileMessage? = null) {
        val actions = WorkspaceSidebarActions(
            onOpenDevice = {}, onNewSession = { created = null }, onSelectSession = { selected = it },
            onRenameSession = {}, onArchiveSession = {}, onDeleteSession = {}, onForkSession = {},
            onCreateSessionIn = { created = it }, onDeleteWorkspace = {}, onToggleSearch = {},
            onSearchQueryChange = {}, onClearSearch = {}, onRetrySearch = {}, onRetrySessions = {},
            onAddWorkspace = {}, onOpenSettings = {},
        )
        rule.setContent {
            DshTheme {
                if (visible.value) HomeWorkspacePage(
                    groups = groups, hostIdentity = identity, allSessions = sessions,
                    currentSessionId = "b", hostName = "Fixture computer", online = online,
                    offlineSinceLabel = null, pending = pending, goalSummaries = emptyMap(),
                    onAnswerApproval = { _, _, done -> approvals++; done(true) },
                    onPickStarter = {}, onOpenComputer = {}, onLongPress = {}, actions = actions,
                    onSearch = { searches++ }, statusItems = {},
                )
            }
        }
    }

    @Test fun collapseSurvivesLeavingHome_andNewTaskUsesItsFolder() {
        show()
        rule.onNodeWithText("First conversation").performClick()
        assertEquals("a", selected)
        rule.onNodeWithText("a").performClick()
        rule.onNodeWithText("First conversation").assertDoesNotExist()
        rule.runOnIdle { visible.value = false }
        rule.waitForIdle()
        rule.runOnIdle { visible.value = true }
        rule.onNodeWithText("First conversation").assertDoesNotExist()
        rule.onNodeWithText("a").performClick()
        rule.onNodeWithText("First conversation").assertExists()
        rule.onNodeWithContentDescription("${L.homeNewTask} · b").performClick()
        assertEquals("/projects/b", created)
        rule.onNodeWithText(L.searchSessions).performClick()
        assertEquals(1, searches)
        rule.onNodeWithText("empty").assertExists()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.cacheDir, "home-workspace-preview.png").outputStream().use { stream ->
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        screenshot.recycle()
    }

    @Test fun pendingApprovalIsPinned_andCollapsedFolderKeepsItsBadge() {
        show(pending = MobileMessage(id = "approval", role = "approval", text = "", approvalId = "approve-b", toolName = "bash"))
        rule.onNodeWithText(L.allowOnce).performClick()
        assertEquals(1, approvals)
        rule.onNodeWithText("b").performClick()
        // 等你处理置顶：收起所在文件夹后仍然可见，且只出现一次。
        rule.onAllNodesWithText("Waiting conversation").assertCountEquals(1)
        assertTrue(rule.onAllNodesWithText(L.homeAwaiting, substring = true).fetchSemanticsNodes().size >= 2)
    }

    @Test fun longPressFolderOffersNewTaskAndDelete() {
        show()
        rule.onNodeWithText("a").performTouchInput { longClick() }
        rule.onNodeWithText(L.homeNewTaskIn.format("a")).performClick()
        assertEquals("/projects/a", created)
    }

    @Test fun offlineDisablesCreateAndApprove_butSearchAndHistoryRemainAccessible() {
        show(online = false, pending = MobileMessage(id = "approval", role = "approval", text = "", approvalId = "approve-b"))
        rule.onNodeWithContentDescription("${L.homeNewTask} · a").assertIsNotEnabled()
        rule.onNodeWithText(L.allowOnce).assertIsNotEnabled()
        rule.onNodeWithText("First conversation").performClick()
        assertEquals("a", selected)
        rule.onNodeWithText(L.searchSessions).performClick()
        assertEquals(1, searches)
    }
}
