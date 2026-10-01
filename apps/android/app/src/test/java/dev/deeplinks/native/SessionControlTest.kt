package dev.deeplinks.native

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControlTest {
    @Test
    fun inboxProjectionMapsPlacements() {
        val inbox = JSONObject(
            """{"next-turn":[{"id":"a","content":[{"type":"text","text":"hi"},{"type":"image"}],"source":{"kind":"user"}}],
               "next-step":[{"id":"b","content":[{"type":"text","text":"go"}],"source":{"kind":"user"}},
                            {"id":"c","content":[],"source":{"kind":"tool"}},{"content":[]}]}""",
        )
        val items = queueFromInbox(inbox)
        assertEquals(listOf("a", "b", "c"), items.map { it.id })
        assertEquals(listOf("queued", "steering", "context"), items.map { it.placement })
        assertEquals(1, items[0].images)
        assertTrue(items[1].editable)
        assertFalse(items[2].editable)
    }

    @Test
    fun pluginQueueArrayParses() {
        val items = parseQueueItems(JSONArray("""[{"id":"q","placement":"queued","text":"t","images":0},{"placement":"x"}]"""))
        assertEquals(1, items.size)
        assertEquals("t", items[0].text)
    }

    @Test
    fun goalSnapshotParsesNestedAndRejectsMissingRef() {
        val goal = parseSessionGoal(JSONObject("""{"goal":{"id":"g","revision":3,"objective":"Ship","phase":"Paused","maxGoalRounds":5},"roundsStarted":2}"""))!!
        assertEquals(SessionGoalRef("g", 3), goal.ref)
        assertEquals("paused", goal.phase)
        assertEquals(5, goal.maxGoalRounds)
        assertEquals(2, goal.roundsStarted)
        assertFalse(goal.active)
        assertTrue(goal.manageable)
        assertNull(parseSessionGoal(JSONObject("""{"goal":{"id":"g","objective":"x"}}""")))
        assertFalse(parseSessionGoal(JSONObject("""{"id":"g","revision":1,"objective":"x","phase":"complete"}"""))!!.manageable)
    }

    @Test
    fun historyResponseCarriesQueueAndGoal() {
        val root = JSONObject("""{"messages":[],"queue":[{"id":"q","placement":"steering","text":"x"}],"goal":null}""")
        val result = parseHistoryResponse(root, null)
        assertEquals(1, result.queue!!.size)
        assertTrue(result.goalKnown)
        assertNull(result.goal)
        val legacy = parseHistoryResponse(JSONObject("""{"messages":[]}"""), null)
        assertNull(legacy.queue)
        assertFalse(legacy.goalKnown)
    }

    @Test
    fun scheduledTasksParseWithRawRecord() {
        val tasks = parseScheduledTasks(
            JSONArray("""[{"id":"t","sessionId":"s","title":"T","prompt":"P","kind":"every","everySeconds":1800,"status":"active",
                          "scheduledAt":"2026-10-02T08:00:00Z","lastDelivery":{"deliveredAt":"2026-10-01T08:00:00Z"}},{"title":"no id"}]"""),
        )
        assertEquals(1, tasks.size)
        val t = tasks[0]
        assertTrue(t.active)
        assertEquals("2026-10-02T08:00:00Z", t.nextRunAt)
        assertEquals(1800, t.raw.getInt("everySeconds"))
        assertEquals("10/2 08:00", formatScheduleTime(t.nextRunAt, java.time.ZoneOffset.UTC))
    }

    @Test
    fun goalRoundsInputValidation() {
        assertNull(parseGoalRounds(" "))
        assertEquals(12, parseGoalRounds("12"))
        assertEquals(-1, parseGoalRounds("0"))
        assertEquals(-1, parseGoalRounds("5000"))
    }

    @Test
    fun sha256Verification() {
        val bytes = "abc".toByteArray()
        val hex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertTrue(sha256Matches(hex, bytes))
        assertTrue(sha256Matches(hex.uppercase(), bytes))
        assertTrue(sha256Matches(null, bytes))
        assertFalse(sha256Matches("00", bytes))
    }
}
