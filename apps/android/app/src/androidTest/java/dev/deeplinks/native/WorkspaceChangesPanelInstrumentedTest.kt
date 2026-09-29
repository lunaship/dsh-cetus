package dev.deeplinks.native

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.deeplinks.core.DshTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceChangesPanelInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun longContextFold_canBeExpandedInPanel() {
        val state = ChangesPanelState(initialProgress = 1f).apply {
            seq = 7L
            fileIndex = 0
        }
        val summary = WorkspaceChangesSummary(
            seq = 7L,
            turn = 1,
            total = 1,
            added = 1,
            deleted = 0,
            files = listOf(ChangedFile(path = "fold-fixture.txt", display = "fold-fixture.txt", added = 1)),
        )
        val diff = WorkspaceFileDiff.Text(
            path = "fold-fixture.txt",
            display = "fold-fixture.txt",
            before = true,
            after = true,
            coarse = false,
            hunks = listOf(
                DiffHunk(
                    oldStart = 1,
                    oldLines = 21,
                    newStart = 1,
                    newLines = 22,
                    lines = (1..20).map { " stable-context-row-${it.toString().padStart(2, '0')}" } + "+ added-content",
                ),
            ),
        )

        composeRule.setContent {
            DshTheme {
                WorkspaceChangesPanel(
                    state = state,
                    summaries = listOf(summary),
                    loadSummary = { null },
                    loadDiff = { _, _ -> diff },
                )
            }
        }

        composeRule.waitForIdle()
        val foldedLabel = ChangesL.expandHiddenRows.format(14)
        composeRule.onNodeWithText(foldedLabel).assertExists().performClick()
        composeRule.onNodeWithText("stable-context-row-10", substring = true).assertExists()
        composeRule.onNodeWithText(foldedLabel).assertDoesNotExist()
    }
}
