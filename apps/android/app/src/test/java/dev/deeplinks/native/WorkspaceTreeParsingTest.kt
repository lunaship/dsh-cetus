package dev.deeplinks.native

import dev.deeplinks.native.util.formatFileSize
import dev.deeplinks.native.util.looksLikeText
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTreeParsingTest {
    @Test
    fun `parses entries and drops unsafe names`() {
        val listing = parseWorkspaceDirListing(
            JSONObject(
                """
                {"ok":true,"path":"src/","total":7,"truncated":true,"entries":[
                  {"name":"deep","type":"dir"},
                  {"name":"linked","type":"dir","link":true},
                  {"name":"main.kt","type":"file","size":1234,"mtimeMs":1},
                  {"name":"out","type":"symlink","outside":true},
                  {"name":".."},{"name":"a/b","type":"file"},{"type":"file"}
                ]}
                """.trimIndent(),
            ),
        )
        assertEquals("src", listing.path)
        assertEquals(listOf("deep", "linked", "main.kt", "out"), listing.entries.map { it.name })
        assertTrue(listing.entries[0].isDir)
        assertTrue(listing.entries[1].link)
        assertEquals(1234L, listing.entries[2].size)
        assertTrue(listing.entries[3].outside)
        assertFalse(listing.entries[3].isDir || listing.entries[3].isFile)
        assertEquals(7, listing.total)
        assertTrue(listing.truncated)
    }

    @Test
    fun `path helpers walk up and down`() {
        assertEquals("src", childWorkspacePath("", "src"))
        assertEquals("src/deep", childWorkspacePath("src", "deep"))
        assertEquals("src", parentWorkspacePath("src/deep"))
        assertEquals("", parentWorkspacePath("src"))
        assertEquals("", parentWorkspacePath(""))
    }

    @Test
    fun `text sniffing accepts utf8 and rejects binary`() {
        assertTrue(looksLikeText("GOOS=linux\n中文注释\n".toByteArray()))
        assertFalse(looksLikeText(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x00, 0x01)))
        assertFalse(looksLikeText(byteArrayOf(0xc3.toByte(), 0x28)))
        // 截断点落在多字节字符中间不算非文本
        val long = ("a".repeat(8191) + "中").toByteArray()
        assertTrue(looksLikeText(long))
    }

    @Test
    fun `file sizes are human readable`() {
        assertEquals("512 B", formatFileSize(512))
        assertEquals("1.5 KB", formatFileSize(1536))
        assertEquals("2.0 MB", formatFileSize(2L * 1024 * 1024))
    }
}
