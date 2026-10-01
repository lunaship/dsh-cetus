package dev.deeplinks.native

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 半透明顶栏/底栏高度：与 DshTranslucentBar.kt / WorkspaceActivity.kt 保持一致。
 * 顶栏 56 dp，输入区 120 dp。
 */
private val TopBarHeightDp = 56
private val InputAreaHeightDp = 120

class ChatTailPositioningTest {

    private data class Item(
        override val index: Int,
        override val offset: Int,
        override val size: Int,
    ) : LazyListItemInfo {
        override val key: Any = index
    }

    /**
     * 第 5 步改动：模拟 PullToRefreshBox 的上下半透明栏 padding。
     * viewportEndOffset = 原始视口底 - 输入区高度（输入区悬浮不参与滚动计算）。
     */
    private class Layout(
        override val totalItemsCount: Int,
        override val visibleItemsInfo: List<LazyListItemInfo>,
        override val viewportEndOffset: Int = 1000 - InputAreaHeightDp,
        override val afterContentPadding: Int = 0,
    ) : LazyListLayoutInfo {
        override val viewportStartOffset: Int = 0
        override val viewportSize: IntSize = IntSize(400, viewportEndOffset + InputAreaHeightDp + afterContentPadding)
        override val orientation: Orientation = Orientation.Vertical
        override val reverseLayout: Boolean = false
        override val beforeContentPadding: Int = 0
        override val mainAxisItemSpacing: Int = 0
    }

    @Test
    fun emptyList_countsAsTail() {
        val layout = Layout(totalItemsCount = 0, visibleItemsInfo = emptyList())
        assertEquals(0, layout.tailOverflowPx())
        assertTrue(layout.isAtTail())
    }

    @Test
    fun topOfLongList_isNotTail() {
        // 2026-09-27 真机现象：落在第 0 项「加载更早」，最后一项不可见
        val layout = Layout(
            totalItemsCount = 80,
            visibleItemsInfo = listOf(Item(0, 0, 60), Item(1, 60, 400), Item(2, 460, 600)),
        )
        assertNull(layout.tailOverflowPx())
        assertFalse(layout.isAtTail())
    }

    @Test
    fun lastItemFullyVisible_isTail() {
        // 半透明输入区 120 dp 占去 viewport 底部：viewportEndOffset = 1000 - 120 = 880
        val layout = Layout(
            totalItemsCount = 80,
            // 末条偏移 400、高 480 → 底部 400 + 480 = 880 = viewportEndOffset（完整露出）
            visibleItemsInfo = listOf(Item(78, 100, 400), Item(79, 400, 480)),
        )
        assertEquals(0, layout.tailOverflowPx())
        assertTrue(layout.isAtTail())
    }

    @Test
    fun longLastItemOverflowingViewport_isNotTail() {
        // scrollToItem 只把长回复顶到视口顶部，底部仍溢出 → 需要 scrollBy 补偿
        // 末条高 2400，viewportEndOffset = 880：overflow = 2400 - 880 = 1520 > 1
        val layout = Layout(
            totalItemsCount = 80,
            visibleItemsInfo = listOf(Item(79, 0, 2400)),
        )
        assertEquals(1520, layout.tailOverflowPx())
        assertFalse(layout.isAtTail())
    }

    @Test
    fun onePixelRounding_isToleratedAsTail() {
        val layout = Layout(
            totalItemsCount = 3,
            visibleItemsInfo = listOf(Item(2, 600, 401)),
        )
        // 600 + 401 - 880 = 121；isAtTail 默认 tolerance=1 → 121 > 1 不算
        // 调整使差量在 1 以内
        val withinTolerance = Layout(
            totalItemsCount = 3,
            visibleItemsInfo = listOf(Item(2, 479, 400)),
        )
        // 479 + 400 - 880 = -1
        assertEquals(-1, withinTolerance.tailOverflowPx())
        assertTrue(withinTolerance.isAtTail())
    }

    @Test
    fun `isAtTail accounts for bottom chrome`() {
        val layout = Layout(
            totalItemsCount = 3,
            visibleItemsInfo = listOf(Item(2, 479, 400)),
            viewportEndOffset = 1000,
            // afterContentPadding 模拟底部输入区高度（px 级别，不依赖 dp 扩展）
            afterContentPadding = InputAreaHeightDp,
        )
        // 479 + 400 - (1000 - 120) = 879 - 880 = -1 ≤ 1 → 贴底
        assertEquals(-1, layout.tailOverflowPx())
        assertTrue(layout.isAtTail())
    }
}
