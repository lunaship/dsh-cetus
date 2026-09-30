package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test
    fun betaTenIsNewerThanBetaNine() {
        assertTrue(compareVersions("0.5.0-beta.10", "0.5.0-beta.9") > 0)
        assertTrue(compareVersions("app-v0.5.0-beta.10", "0.5.0-beta.9") > 0)
    }

    @Test
    fun releaseIsNewerThanPrereleaseOfTheSameNumber() {
        assertTrue(compareVersions("0.5.0", "0.5.0-beta.23") > 0)
        assertTrue(compareVersions("0.5.0-beta.23", "0.5.0") < 0)
    }

    @Test
    fun parseKeepsAppTagsAndDropsPluginTagsAndDrafts() {
        val json = """
            [
              {"tag_name":"v0.1.0-beta.19","draft":false,"html_url":"https://github.com/lunaship/dsh-links/releases/tag/v0.1.0-beta.19","published_at":"2026-09-30T00:00:00Z"},
              {"tag_name":"app-v0.5.0-beta.24","draft":true,"html_url":"https://github.com/lunaship/dsh-links/releases/tag/app-v0.5.0-beta.24","published_at":"2026-09-30T00:00:00Z"},
              {"tag_name":"app-v0.5.0-beta.23","draft":false,"html_url":"https://github.com/lunaship/dsh-links/releases/tag/app-v0.5.0-beta.23","published_at":"2026-09-29T00:00:00Z"}
            ]
        """.trimIndent()
        val releases = parseReleases(json)
        assertEquals(listOf("app-v0.5.0-beta.23"), releases.map { it.tagName })
        assertEquals("https://github.com/lunaship/dsh-links/releases/tag/app-v0.5.0-beta.23", releases.single().htmlUrl)
    }

    @Test
    fun newerReleaseIgnoresCurrentAndOlder() {
        val releases = listOf(
            AppRelease("app-v0.5.0-beta.22", "https://example.com/22", ""),
            AppRelease("app-v0.5.0-beta.24", "https://example.com/24", ""),
        )
        assertEquals("app-v0.5.0-beta.24", newerRelease("0.5.0-beta.23", releases)?.tagName)
        assertNull(newerRelease("0.5.0-beta.24", releases))
    }

    @Test
    fun checksAtMostOncePerDay() {
        val now = 1_700_000_000_000L
        assertTrue(shouldCheck(0L, now))
        assertTrue(!shouldCheck(now - 60_000L, now))
        assertTrue(shouldCheck(now - DAY, now))
    }

    private companion object {
        const val DAY = 24L * 60L * 60L * 1000L
    }
}
