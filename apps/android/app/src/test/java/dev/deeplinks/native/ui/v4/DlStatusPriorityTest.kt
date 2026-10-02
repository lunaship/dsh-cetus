package dev.deeplinks.native.ui.v4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DlStatusPriorityTest {

    private data class Candidate(val name: String, val kind: DlStatusKind)

    @Test
    fun disconnectedBeatsEverything() {
        val list = listOf(
            Candidate("preview", DlStatusKind.Preview),
            Candidate("goal", DlStatusKind.Goal),
            Candidate("offline", DlStatusKind.Disconnected),
            Candidate("pending", DlStatusKind.Pending),
        )
        assertEquals("offline", list.topStatus { it.kind }?.name)
    }

    @Test
    fun pendingBeatsGoalAndPreview() {
        val list = listOf(
            Candidate("goal", DlStatusKind.Goal),
            Candidate("preview", DlStatusKind.Preview),
            Candidate("pending", DlStatusKind.Pending),
        )
        assertEquals("pending", list.topStatus { it.kind }?.name)
    }

    @Test
    fun goalBeatsPreview() {
        val list = listOf(Candidate("preview", DlStatusKind.Preview), Candidate("goal", DlStatusKind.Goal))
        assertEquals("goal", list.topStatus { it.kind }?.name)
    }

    @Test
    fun emptyMeansNoSlot() {
        assertNull(emptyList<Candidate>().topStatus { it.kind })
    }
}
