package dev.deeplinks.native.util

/**
 * 长回复拆成多个 LazyColumn 条目，避免一整篇长 Markdown 在同一帧里测量 / 合成。
 *
 * 只在「代码围栏之外的空行」处切开，所以围栏代码、表格、单个列表项都不会被截断；
 * 再把相邻逻辑块贪心拼到约 [targetCharacters] 字一段。短消息原样返回单段。
 *
 * 思路与实现参考 Clarklevis1995/dsh-mobile（MIT，Copyright (c) 2026 Chaofan Li）的
 * `splitMarkdownForLazyLayout`，见 THIRD_PARTY_NOTICES.md。
 */
fun splitMarkdownForLazyLayout(markdown: String, targetCharacters: Int = 320): List<String> {
    if (markdown.length <= targetCharacters) return listOf(markdown)

    val logicalBlocks = mutableListOf<String>()
    val current = StringBuilder()
    var fenceMarker: String? = null

    fun flush() {
        current.toString().trimEnd().takeIf { it.isNotEmpty() }?.let(logicalBlocks::add)
        current.clear()
    }

    markdown.lineSequence().forEach { line ->
        val trimmed = line.trimStart()
        val fence = when {
            trimmed.startsWith("```") -> "```"
            trimmed.startsWith("~~~") -> "~~~"
            else -> null
        }
        if (fence != null) {
            fenceMarker = when (fenceMarker) {
                null -> fence
                fence -> null
                else -> fenceMarker
            }
        }
        if (line.isBlank() && fenceMarker == null) {
            flush()
        } else {
            if (current.isNotEmpty()) current.append('\n')
            current.append(line)
        }
    }
    flush()

    if (logicalBlocks.size <= 1) return listOf(markdown)
    val chunks = mutableListOf<String>()
    val chunk = StringBuilder()
    logicalBlocks.forEach { block ->
        val separator = if (chunk.isEmpty()) 0 else 2
        if (chunk.isNotEmpty() && chunk.length + separator + block.length > targetCharacters) {
            chunks += chunk.toString()
            chunk.clear()
        }
        if (chunk.isNotEmpty()) chunk.append("\n\n")
        chunk.append(block)
    }
    if (chunk.isNotEmpty()) chunks += chunk.toString()
    return chunks.ifEmpty { listOf(markdown) }
}

/**
 * 按消息 id 记住上次的拆分结果：LazyColumn 内容 lambda 每次重组都会跑，
 * 流式时只有正在增长的那条消息需要重新拆。只在主线程（组合）使用。
 */
class MarkdownSplitCache(private val maxEntries: Int = 64) {
    private val entries = object : LinkedHashMap<String, Pair<String, List<String>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, List<String>>>?): Boolean =
            size > maxEntries
    }

    fun parts(id: String, text: String): List<String> {
        entries[id]?.let { (cached, parts) -> if (cached == text) return parts }
        val parts = splitMarkdownForLazyLayout(text)
        entries[id] = text to parts
        return parts
    }
}
