package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
              {"tag_name":"v0.1.0-beta.19","draft":false,"html_url":"https://github.com/lunaship/dsh-cetus/releases/tag/v0.1.0-beta.19","published_at":"2026-09-30T00:00:00Z"},
              {"tag_name":"app-v0.5.0-beta.24","draft":true,"html_url":"https://github.com/lunaship/dsh-cetus/releases/tag/app-v0.5.0-beta.24","published_at":"2026-09-30T00:00:00Z"},
              {"tag_name":"app-v0.5.0-beta.23","draft":false,"html_url":"https://github.com/lunaship/dsh-cetus/releases/tag/app-v0.5.0-beta.23","published_at":"2026-09-29T00:00:00Z"}
            ]
        """.trimIndent()
        val releases = parseReleases(json)
        assertEquals(listOf("app-v0.5.0-beta.23"), releases.map { it.tagName })
        assertEquals("https://github.com/lunaship/dsh-cetus/releases/tag/app-v0.5.0-beta.23", releases.single().htmlUrl)
    }

    @Test
    fun newerReleaseIgnoresCurrentAndOlder() {
        val releases = listOf(
            AppRelease("app-v0.5.0-beta.22", "https://github.com/lunaship/dsh-cetus/releases/tag/app-v0.5.0-beta.22", ""),
            AppRelease("app-v0.5.0-beta.24", "https://github.com/lunaship/dsh-cetus/releases/tag/app-v0.5.0-beta.24", ""),
        )
        assertEquals("app-v0.5.0-beta.24", newerRelease("0.5.0-beta.23", releases)?.tagName)
        assertNull(newerRelease("0.5.0-beta.24", releases))
    }

    @Test
    fun releaseUrlMustBeThisGithubRepo() {
        val ok = "https://github.com/lunaship/dsh-cetus/releases/tag/app-v1"
        assertTrue(isGithubReleaseUrl(ok))
        assertTrue(isGithubReleaseUrl("https://GITHUB.COM/lunaship/dsh-cetus/releases/tag/app-v1"))
        assertFalse(isGithubReleaseUrl("http://github.com/lunaship/dsh-cetus/releases/tag/app-v1"))
        assertFalse(isGithubReleaseUrl("https://example.com/lunaship/dsh-links/releases/tag/app-v1"))
        assertFalse(isGithubReleaseUrl("https://github.com/other/dsh-links/releases/tag/app-v1"))
        assertFalse(isGithubReleaseUrl("https://github.com/lunaship/dsh-cetus"))
        val evil = AppRelease("app-v9.0.0", "https://example.com/lunaship/dsh-links/releases/tag/app-v9", "")
        val good = AppRelease("app-v9.0.0", ok, "")
        assertNull(newerRelease("0.0.1", listOf(evil)))
        assertEquals("app-v9.0.0", newerRelease("0.0.1", listOf(evil, good))?.tagName)
    }

    /**
     * 方案 §24.1 第 2 步：仓库迁移过渡期必须**同时**接受新旧仓库，
     * 否则装在用户手机上的旧包（写死旧地址）看不到迁移 release。
     */
    @Test
    fun legacyRepoIsAcceptedDuringTheRenameTransition() {
        val legacy = "https://github.com/lunaship/dsh-links/releases/tag/app-v0.5.0-beta.31"
        assertTrue("过渡期必须接受旧仓库", isGithubReleaseUrl(legacy))
        assertTrue(isGithubReleaseUrl("https://GITHUB.COM/lunaship/dsh-links/releases/tag/app-v1"))

        // 放宽的只是"仓库名"，其余安全约束一条都不能松：
        assertFalse("非 https 仍拒绝", isGithubReleaseUrl("http://github.com/lunaship/dsh-links/releases/tag/app-v1"))
        assertFalse("非 github 主机仍拒绝", isGithubReleaseUrl("https://evil.com/lunaship/dsh-links/releases/tag/app-v1"))
        assertFalse("非 lunaship 名下仍拒绝", isGithubReleaseUrl("https://github.com/other/dsh-links/releases/tag/app-v1"))
        assertFalse("相似前缀不能蒙混", isGithubReleaseUrl("https://github.com/lunaship/dsh-links-evil/releases/tag/app-v1"))
    }

    @Test
    fun legacyReleaseIsOfferedToOldInstalls() {
        // 旧地址上的迁移 release 要能被识别为「有新版」，否则旧包永远升不上来。
        val migration = AppRelease(
            "app-v0.5.0-beta.31",
            "https://github.com/lunaship/dsh-links/releases/tag/app-v0.5.0-beta.31",
            "",
        )
        assertEquals("app-v0.5.0-beta.31", newerRelease("0.5.0-beta.27", listOf(migration))?.tagName)
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
