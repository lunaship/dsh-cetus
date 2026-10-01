package dev.deeplinks.native

import androidx.compose.runtime.withFrameNanos
import dev.deeplinks.core.PrivacySafeDiagnostics
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * SSE 增量帧合并：模型吐字快时一帧里可能到几十个 text-delta，逐个提交会让
 * `messages` 每个 delta 复制一次整表、拼一次整串。这里把「已经到达、相邻、同一块」
 * 的 delta 合成一个，再在处理完一批含 delta 的条目后等下一帧——
 * 保证每帧至多一次流式文本提交，且条目顺序不变（非 delta 条目原样穿插）。
 *
 * 思路参考 Clarklevis1995/dsh-mobile（MIT）的 AndroidProjectionActor.acceptStreamingFrame。
 */
internal suspend fun ReceiveChannel<SessionStreamClient.Item>.forEachCoalesced(
    maxBatch: Int = 512,
    apply: (SessionStreamClient.Item) -> Unit,
) {
    for (first in this) {
        val batch = ArrayList<SessionStreamClient.Item>(8)
        batch += first
        while (batch.size < maxBatch) {
            batch += tryReceive().getOrNull() ?: break
        }
        val merged = coalesceStreamDeltas(batch)
        PrivacySafeDiagnostics.streamBatch(batch.size, merged.size)
        merged.forEach(apply)
        // 后台时帧时钟可能不走：超时兜底，避免流处理被挂起
        if (merged.any { deltaKey(it) != null }) withTimeoutOrNull(FRAME_WAIT_MS) { withFrameNanos { } }
    }
}

private const val FRAME_WAIT_MS = 48L

private val DELTA_FIELDS = mapOf(
    "text-delta" to "text",
    "reasoning-delta" to "text",
    "tool-call-delta" to "argumentsDelta",
)

/** delta 合并键：chunk 类型 + turn + index；非 delta 返回 null。 */
private fun deltaKey(item: SessionStreamClient.Item): String? {
    if (item !is SessionStreamClient.Item.Message || item.type != "assistant/chunk") return null
    val chunk = item.data.optJSONObject("chunk") ?: return null
    val type = chunk.optString("type")
    if (type !in DELTA_FIELDS) return null
    return "$type:${item.data.optInt("turn")}:${chunk.optInt("index")}"
}

/** 只合并相邻且同键的 delta；其余条目（含 block-end）保持原位，顺序语义不变。 */
internal fun coalesceStreamDeltas(items: List<SessionStreamClient.Item>): List<SessionStreamClient.Item> {
    if (items.size < 2) return items
    val out = ArrayList<SessionStreamClient.Item>(items.size)
    var i = 0
    while (i < items.size) {
        val key = deltaKey(items[i])
        var j = i + 1
        if (key != null) while (j < items.size && deltaKey(items[j]) == key) j++
        out += if (j - i > 1) mergeRun(items.subList(i, j)) else items[i]
        i = j
    }
    return out
}

@Suppress("UNCHECKED_CAST")
private fun mergeRun(run: List<SessionStreamClient.Item>): SessionStreamClient.Item {
    val msgs = run as List<SessionStreamClient.Item.Message>
    val first = msgs.first()
    val firstChunk = first.data.getJSONObject("chunk")
    val field = DELTA_FIELDS.getValue(firstChunk.optString("type"))
    val text = StringBuilder()
    val chunk = JSONObject(firstChunk.toString())
    for (m in msgs) {
        val c = m.data.getJSONObject("chunk")
        text.append(c.optString(field))
        // tool-call-delta：name / id 可能只在某一帧出现，取最新的非空值
        for (extra in listOf("name", "id")) {
            val v = c.optString(extra)
            if (v.isNotBlank()) chunk.put(extra, v)
        }
    }
    chunk.put(field, text.toString())
    val data = JSONObject(first.data.toString())
    data.put("chunk", chunk)
    return SessionStreamClient.Item.Message(seq = msgs.last().seq, type = first.type, time = first.time, data = data)
}
