package dev.deeplinks.native.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.deeplinks.core.DshTheme
import dev.deeplinks.native.LocalRemoteImagePolicy
import dev.deeplinks.native.RemoteImagePolicy
import dev.deeplinks.native.RemoteImageBlock
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 远程图片占位卡（第 2 步 B4）：默认不出图、点按放行后才请求。
 *
 * 不联网：占位态断言文字与点击动作；点按后占位消失即证明状态机走到了「请求」。
 */
@RunWith(AndroidJUnit4::class)
class RemoteImageBlockTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val url = "https://example.com/a.png"

    @Test
    fun placeholder_showsTapHint_andNotImage() {
        composeRule.setContent {
            DshTheme {
                CompositionLocalProvider(LocalRemoteImagePolicy provides RemoteImagePolicy(autoLoad = false)) {
                    RemoteImageBlock(url = url)
                }
            }
        }
        composeRule.onNodeWithText("点按加载图片").assertIsDisplayed()
        composeRule.onNodeWithText("example.com").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("加载图片").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("加载图片").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun tap_dismissesPlaceholder_andAllowsUrl() {
        composeRule.setContent {
            DshTheme {
                CompositionLocalProvider(LocalRemoteImagePolicy provides RemoteImagePolicy(autoLoad = false)) {
                    RemoteImageBlock(url = url)
                }
            }
        }
        composeRule.onNodeWithText("点按加载图片").performClick()
        composeRule.onNodeWithText("点按加载图片").assertDoesNotExist()
    }

    @Test
    fun autoLoad_skipsPlaceholder() {
        composeRule.setContent {
            DshTheme {
                CompositionLocalProvider(LocalRemoteImagePolicy provides RemoteImagePolicy(autoLoad = true)) {
                    RemoteImageBlock(url = url)
                }
            }
        }
        composeRule.onNodeWithText("点按加载图片").assertDoesNotExist()
    }
}
