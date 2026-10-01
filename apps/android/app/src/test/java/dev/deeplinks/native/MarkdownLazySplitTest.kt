package dev.deeplinks.native

import dev.deeplinks.native.util.MarkdownSplitCache
import dev.deeplinks.native.util.splitMarkdownForLazyLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLazySplitTest {
    @Test
    fun shortTextStaysOnePart() {
        assertEquals(listOf("hello"), splitMarkdownForLazyLayout("hello"))
    }

    @Test
    fun splitsOnBlankLinesAndKeepsAllText() {
        val md = (1..12).joinToString("\n\n") { "Paragraph $it " + "x".repeat(40) }
        val parts = splitMarkdownForLazyLayout(md, targetCharacters = 120)
        assertTrue(parts.size > 1)
        assertEquals(md, parts.joinToString("\n\n"))
        assertTrue(parts.all { it.length <= 120 || !it.contains("\n\n") })
    }

    @Test
    fun neverCutsInsideFence() {
        val code = "```kotlin\nval a = 1\n\nval b = 2\n\nval c = 3\n```"
        val md = "intro " + "y".repeat(30) + "\n\n" + code + "\n\n" + "tail " + "z".repeat(30)
        val parts = splitMarkdownForLazyLayout(md, targetCharacters = 20)
        assertTrue(parts.any { it == code })
        parts.forEach { part -> assertEquals(0, part.split("```").size.minus(1) % 2) }
    }

    @Test
    fun unclosedFenceDuringStreamingStaysTogether() {
        val md = "a".repeat(30) + "\n\n```\nline1\n\nline2\n\nline3"
        val parts = splitMarkdownForLazyLayout(md, targetCharacters = 20)
        assertEquals("```\nline1\n\nline2\n\nline3", parts.last())
    }

    @Test
    fun cacheReusesResultForSameText() {
        val cache = MarkdownSplitCache()
        val text = (1..10).joinToString("\n\n") { "p$it " + "w".repeat(60) }
        val first = cache.parts("m1", text)
        assertSame(first, cache.parts("m1", text))
        assertTrue(cache.parts("m1", text + "\n\nmore").size >= first.size)
    }

    @Test
    fun feedRowsSplitOnlyLongAssistantMessages() {
        val long = (1..10).joinToString("\n\n") { "p$it " + "w".repeat(60) }
        val groups = listOf(
            dev.deeplinks.native.util.MessageGroup.Single(MobileMessage(id = "u", role = "user", text = long, time = 0, type = "text")),
            dev.deeplinks.native.util.MessageGroup.Single(MobileMessage(id = "a", role = "assistant", text = long, time = 0, type = "text")),
        )
        val rows = chatFeedRows(groups, isRunning = false)
        assertEquals(1, rows.count { it.group == groups[0] })
        val parts = rows.filter { it.group == groups[1] }
        assertTrue(parts.size > 1)
        assertEquals("a", parts.first().key)
        assertEquals(parts.map { it.key }.toSet().size, parts.size)
        assertTrue(parts.all { it.isTurnEnd })
    }
}
