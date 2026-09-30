package dev.deeplinks.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** 把检查更新指到本机假服务器，确认解析结果写进设置页读取的同一份缓存。 */
@RunWith(AndroidJUnit4::class)
class UpdateCheckDeviceTest {
    @Test
    fun localServerPublishesANewerAppRelease() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    [
                      {"tag_name":"v0.1.0-beta.19","draft":false,"html_url":"https://github.com/lunaship/dsh-links/releases/tag/v0.1.0-beta.19","published_at":"2026-09-30T00:00:00Z"},
                      {"tag_name":"app-v0.5.0-beta.98","draft":false,"html_url":"https://github.com/lunaship/dsh-links/releases/tag/app-v0.5.0-beta.98","published_at":"2026-09-30T00:00:00Z"}
                    ]
                    """.trimIndent(),
                )
                .build(),
        )
        server.start()
        try {
            UpdateCheckPrefs.setEnabled(context, true)
            UpdateCheckPrefs.setLastCheckedAt(context, 0L)
            UpdateChecker.checkBlocking(
                context,
                "0.5.0-beta.23",
                server.url("/").toString(),
                now = 1_700_000_000_000L,
            )
            val recorded = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("application/vnd.github+json", recorded.headers["Accept"])
            assertTrue(recorded.headers["User-Agent"].orEmpty().startsWith("DeepLinks-Android/0.5.0-beta.23"))
            assertEquals(null, recorded.headers["Authorization"])
            assertEquals("app-v0.5.0-beta.98", UpdateCheckPrefs.cachedNewer(context)?.tagName)
        } finally {
            server.close()
        }
    }
}
