package dev.deeplinks.native

import android.util.Log

/**
 * assistant/chunk 增量块：delta 追加到 (turn,index) 流式消息，block-end 定稿（权威全文）。
 * 从 WorkspaceScreen 抽出（文件行数预算）；消息读写仍经屏幕自己的 append / update，
 * 贴底跟随等副作用不变。
 */
internal fun applyStreamChunk(
    item: SessionStreamClient.Item.Message,
    messages: List<MobileMessage>,
    appendStreamMessage: (MobileMessage) -> Unit,
    updateStreamMessage: (String, (MobileMessage) -> MobileMessage) -> Unit,
    streamToolCallIds: MutableMap<String, String>,
    toolCallTimes: MutableMap<String, Long>,
) {
    val chunk = item.data.optJSONObject("chunk") ?: return
    val type = chunk.optString("type")
    val turn = item.data.optInt("turn")
    val index = chunk.optInt("index")
    when (type) {
        "reasoning-delta" -> {
            val piece = chunk.optString("text")
            val id = "reason-$turn-$index"
            val i = messages.indexOfLast { it.id == id }
            if (i >= 0) updateStreamMessage(id) { it.copy(text = it.text + piece) }
            else appendStreamMessage(MobileMessage(id = id, role = "reasoning", text = piece, time = item.time, type = "reasoning", running = true))
        }
        "text-delta" -> {
            val piece = chunk.optString("text")
            val id = "msg-stream-$turn-$index"
            val i = messages.indexOfLast { it.id == id }
            if (i >= 0) updateStreamMessage(id) { it.copy(text = it.text + piece) }
            else appendStreamMessage(MobileMessage(id = id, role = "assistant", text = piece, time = item.time, type = "text", running = true))
        }
        "tool-call-delta" -> {
            val delta = chunk.optString("argumentsDelta")
            val name = chunk.optString("name")
            val callId = chunk.optString("id")
            val id = "tool-stream-$turn-$index"
            val i = messages.indexOfLast { it.id == id }
            if (i >= 0) {
                updateStreamMessage(id) {
                    it.copy(toolArgs = it.toolArgs + delta, toolName = name.ifBlank { it.toolName })
                }
            } else {
                appendStreamMessage(MobileMessage(id = id, role = "tool_call", text = "", toolName = name.takeIf { it.isNotBlank() },
                    toolArgs = delta, time = item.time, type = "tool_call", running = true))
            }
            if (callId.isNotBlank()) streamToolCallIds[id] = callId
        }
        "block-end" -> {
            val block = chunk.optJSONObject("block") ?: return
            when (val blockType = block.optString("type")) {
                "reasoning" -> {
                    val id = "reason-$turn-$index"
                    val text = block.optString("text")
                    val i = messages.indexOfLast { it.id == id }
                    if (i >= 0) updateStreamMessage(id) { it.copy(text = text.ifBlank { it.text }, running = false) }
                    else if (text.isNotBlank()) {
                        appendStreamMessage(MobileMessage(id = id, role = "reasoning", text = text, time = item.time, type = "reasoning"))
                    }
                }
                "text" -> {
                    val id = "msg-stream-$turn-$index"
                    val i = messages.indexOfLast { it.id == id }
                    if (i >= 0) updateStreamMessage(id) { it.copy(text = block.optString("text").ifBlank { it.text }, running = false) }
                    else appendStreamMessage(MobileMessage(id = id, role = "assistant", text = block.optString("text"), time = item.time, type = "text"))
                }
                "tool-call" -> {
                    val id = "tool-stream-$turn-$index"
                    val callId = block.optString("id")
                    val i = messages.indexOfLast { it.id == id }
                    if (i >= 0) {
                        updateStreamMessage(id) {
                            it.copy(
                                toolName = block.optString("name").ifBlank { it.toolName },
                                toolArgs = block.optString("arguments").ifBlank { it.toolArgs },
                                running = false,
                            )
                        }
                    } else {
                        appendStreamMessage(MobileMessage(id = id, role = "tool_call", text = "",
                            toolName = block.optString("name"), toolArgs = block.optString("arguments"), time = item.time, type = "tool_call"))
                    }
                    if (callId.isNotBlank()) streamToolCallIds[id] = callId
                    toolCallTimes[callId] = item.time
                }
                else -> Log.i("SessionStream", "unhandled block-end type=$blockType turn=$turn index=$index keys=${block.keys().asSequence().toList()}")
            }
        }
        else -> Log.i("SessionStream", "unhandled chunk type=$type turn=$turn index=$index keys=${chunk.keys().asSequence().toList()}")
    }
}
