package dev.deeplinks.core

import dev.deeplinks.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方案 §4 C00：构建元数据必须真实反映当前提交，且缺值时明确写 unknown 而不是空白。
 */
class BuildInfoTest {
    @Test
    fun commitIsNotEmptyOrUnknownPlaceholder() {
        assertTrue("commit 不应为空", BuildInfo.commit.isNotEmpty())
        assertTrue(
            "commit 必须是 git SHA 或 unknown，实际=${BuildInfo.commit}",
            BuildInfo.commit == "unknown" || Regex("^[0-9a-f]{6,40}(-dirty)?$").matches(BuildInfo.commit),
        )
    }

    @Test
    fun dateIsIsoOrUnknown() {
        assertTrue(
            "date 必须是 yyyy-MM-dd 或 unknown，实际=${BuildInfo.date}",
            BuildInfo.date == "unknown" || Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(BuildInfo.date),
        )
    }

    @Test
    fun marketingVersionIsNotSilentlyReplacedByBuildMetadata() {
        // 构建元数据不得改写营销版本：两者是不同概念（方案 §4 第 3 条）。
        assertEquals(BuildConfig.VERSION_NAME, BuildInfo.marketingVersion)
        assertEquals(BuildConfig.VERSION_CODE.toString(), BuildInfo.buildNumber)
        assertEquals("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", BuildInfo.versionLine)
    }

    @Test
    fun contractVersionMatchesMobileSyncContract() {
        // docs/MOBILE_SYNC_CONTRACT.md 的 payload version 与 scripts/build-metadata.mjs 一致。
        assertEquals("1", BuildInfo.contractVersion)
    }
}
