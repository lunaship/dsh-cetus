package dev.deeplinks.native

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** 与 iOS `IntralineDiffContractTests` 共用 `testdata/intraline-diff-cases.json`。 */
class IntralineDiffSharedCasesTest {
    @Test
    fun sharedCasesMatchAndroid() {
        val root = JSONObject(casesFile().readText())
        val tokens = root.getJSONArray("tokens")
        for (i in 0 until tokens.length()) {
            val item = tokens.getJSONObject(i)
            val text = item.getString("text")
            assertEquals(item.getString("name"), strings(item, "pieces"), text.pieces(intralineTokens(text)))
        }
        val emphasis = root.getJSONArray("emphasis")
        for (i in 0 until emphasis.length()) {
            val item = emphasis.getJSONObject(i)
            val budget = intArrayOf(if (item.has("budget")) item.getInt("budget") else INTRALINE_BUDGET_CELLS)
            val (oldRanges, newRanges) = intralineEmphasis(item.getString("old"), item.getString("new"), budget)
            assertEquals(item.getString("name"), strings(item, "oldPieces"), item.getString("old").pieces(oldRanges))
            assertEquals(item.getString("name"), strings(item, "newPieces"), item.getString("new").pieces(newRanges))
        }
        val pairs = root.getJSONArray("pairs")
        for (i in 0 until pairs.length()) {
            val item = pairs.getJSONObject(i)
            val rows = item.getJSONArray("rows")
            val input = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                DiffRow(
                    kind = DiffRow.Kind.valueOf(row.getString("kind").uppercase()),
                    oldNo = null,
                    newNo = null,
                    text = row.getString("text"),
                )
            }
            val emphasized = withIntralineEmphasis(input)
            assertEquals(item.getString("name"), rows.length(), emphasized.size)
            emphasized.forEachIndexed { index, row ->
                val expected = rows.getJSONObject(index)
                assertEquals(item.getString("name"), strings(expected, "pieces"), row.text.pieces(row.emphasis))
            }
        }
    }

    private fun strings(item: JSONObject, key: String): List<String> {
        val array = item.getJSONArray(key)
        return (0 until array.length()).map { array.getString(it) }
    }

    private fun String.pieces(ranges: List<IntRange>) = ranges.map { substring(it.first, it.last + 1) }

    private fun casesFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testdata/intraline-diff-cases.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("找不到 testdata/intraline-diff-cases.json")
    }
}
