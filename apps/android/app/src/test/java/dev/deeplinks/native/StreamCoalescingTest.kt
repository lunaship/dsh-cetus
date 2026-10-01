package dev.deeplinks.native

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamCoalescingTest {
    private fun delta(seq: Long, type: String, text: String, index: Int = 0, turn: Int = 1): SessionStreamClient.Item.Message {
        val field = if (type == "tool-call-delta") "argumentsDelta" else "text"
        val chunk = JSONObject().put("type", type).put("index", index).put(field, text)
        return SessionStreamClient.Item.Message(seq, "assistant/chunk", 100 + seq, JSONObject().put("turn", turn).put("chunk", chunk))
    }

    @Test
    fun mergesAdjacentSameBlockDeltas() {
        val out = coalesceStreamDeltas(listOf(delta(1, "text-delta", "He"), delta(2, "text-delta", "llo"), delta(3, "text-delta", "!")))
        assertEquals(1, out.size)
        val m = out[0] as SessionStreamClient.Item.Message
        assertEquals("Hello!", m.data.getJSONObject("chunk").getString("text"))
        assertEquals(3L, m.seq)
        assertEquals(101L, m.time)
    }

    @Test
    fun keepsOrderAcrossDifferentBlocksAndNonDeltas() {
        val end = SessionStreamClient.Item.Message(4, "assistant/chunk", 0, JSONObject().put("chunk", JSONObject().put("type", "block-end")))
        val out = coalesceStreamDeltas(
            listOf(
                delta(1, "reasoning-delta", "a"),
                delta(2, "reasoning-delta", "b"),
                delta(3, "text-delta", "c"),
                end,
                delta(5, "text-delta", "d", index = 1),
                delta(6, "text-delta", "e", index = 1),
            ),
        )
        assertEquals(4, out.size)
        assertEquals("ab", (out[0] as SessionStreamClient.Item.Message).data.getJSONObject("chunk").getString("text"))
        assertEquals(end, out[2])
        assertEquals("de", (out[3] as SessionStreamClient.Item.Message).data.getJSONObject("chunk").getString("text"))
    }

    @Test
    fun toolCallDeltaKeepsLatestNameAndId() {
        val a = delta(1, "tool-call-delta", "{\"a\":").also { it.data.getJSONObject("chunk").put("name", "bash").put("id", "c1") }
        val b = delta(2, "tool-call-delta", "1}")
        val m = coalesceStreamDeltas(listOf(a, b)).single() as SessionStreamClient.Item.Message
        val chunk = m.data.getJSONObject("chunk")
        assertEquals("{\"a\":1}", chunk.getString("argumentsDelta"))
        assertEquals("bash", chunk.getString("name"))
        assertEquals("c1", chunk.getString("id"))
    }
}
