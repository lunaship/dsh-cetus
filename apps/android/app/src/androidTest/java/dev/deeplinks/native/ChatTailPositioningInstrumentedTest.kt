package dev.deeplinks.native

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 复现打开长会话落在顶部的时序：尾部请求先于数据发起（会话切换时自增 tailRequestId），
 * 列表此刻只有加载骨架 1 项；数据到达的同一刻头部「加载更早」一起插入。
 * 旧实现在数据提交后、布局更新前读 totalItemsCount(=1) → scrollToItem(0) → 停在顶部。
 */
@RunWith(AndroidJUnit4::class)
class ChatTailPositioningInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var messages by mutableStateOf(emptyList<Int>())
    private var hasMore by mutableStateOf(false)
    private var tailRequestId by mutableIntStateOf(0)
    private var lastResult: Boolean? = null
    private lateinit var listState: LazyListState

    private fun setFeed() {
        composeRule.setContent {
            listState = rememberLazyListState()
            LaunchedEffect(tailRequestId) {
                if (tailRequestId == 0) return@LaunchedEffect
                lastResult = listState.positionAtTail(hasContent = { messages.isNotEmpty() })
            }
            LazyColumn(state = listState, modifier = Modifier.size(360.dp, 640.dp)) {
                if (messages.isEmpty()) {
                    item(key = "chat-loading-skeleton") { Box(Modifier.fillMaxWidth().height(640.dp)) }
                } else {
                    if (hasMore) {
                        item(key = "load-older") { Text("load older", Modifier.testTag("load-older")) }
                    }
                    items(messages, key = { it }) { id ->
                        // 高低错落：模拟长回复与短消息混排
                        val h = if (id % 5 == 0) 900.dp else 120.dp
                        Text("m$id", Modifier.fillMaxWidth().height(h).testTag("m$id"))
                    }
                }
            }
        }
    }

    @Test
    fun openSession_requestBeforeData_landsOnLatestMessage() {
        setFeed()
        composeRule.runOnIdle { tailRequestId++ }
        // 请求已挂起等待数据；数据与 hasMore 在同一次快照里提交（与 VM 一致）
        composeRule.runOnIdle {
            messages = (1..60).toList()
            hasMore = true
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("m60").assertIsDisplayed()
        composeRule.onNodeWithTag("load-older").assertDoesNotExist()
        composeRule.runOnIdle {
            assertTrue(listState.layoutInfo.isAtTail())
            assertTrue(lastResult == true)
        }
    }

    @Test
    fun longLastMessage_bottomIsRevealed() {
        setFeed()
        composeRule.runOnIdle {
            messages = (1..40).toList() // 末条 40 高 900dp，超过视口
            hasMore = true
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { tailRequestId++ }
        composeRule.waitForIdle()

        composeRule.runOnIdle { assertTrue(listState.layoutInfo.isAtTail()) }
    }

    @Test
    fun noData_timesOutWithoutScrolling() {
        setFeed()
        composeRule.runOnIdle { tailRequestId++ }
        // 超时走协程 delay（可能是真实时间），用 waitUntil 而不是虚拟时钟
        composeRule.waitUntil(timeoutMillis = 6_000) { lastResult != null }

        composeRule.runOnIdle {
            assertFalse(lastResult!!)
            assertEquals(0, listState.firstVisibleItemIndex)
        }
    }
}
