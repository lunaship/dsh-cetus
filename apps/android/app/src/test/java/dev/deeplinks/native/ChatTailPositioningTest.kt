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

class ChatTailPositioningTest {

    private data class Item(override val index: Int, override val offset: Int, override val size: Int) : LazyListItemInfo {
        override val key: Any = index
    }

    private class Layout(
        override val totalItemsCount: Int,
        override val visibleItemsInfo: List<LazyListItemInfo>,
        override val viewportEndOffset: Int = 1000,
    ) : LazyListLayoutInfo {
        override val viewportStartOffset: Int = 0
        override val viewportSize: IntSize = IntSize(400, viewportEndOffset)
        override val orientation: Orientation = Orientation.Vertical
        override val reverseLayout: Boolean = false
        override val beforeContentPadding: Int = 0
        override val afterContentPadding: Int = 0
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
        val layout = Layout(
            totalItemsCount = 80,
            visibleItemsInfo = listOf(Item(78, 100, 400), Item(79, 500, 480)),
        )
        assertEquals(-20, layout.tailOverflowPx())
        assertTrue(layout.isAtTail())
    }

    @Test
    fun longLastItemOverflowingViewport_isNotTail() {
        // scrollToItem 只把长回复顶到视口顶部，底部仍溢出 → 需要 scrollBy 补偿
        val layout = Layout(
            totalItemsCount = 80,
            visibleItemsInfo = listOf(Item(79, 0, 2400)),
        )
        assertEquals(1400, layout.tailOverflowPx())
        assertFalse(layout.isAtTail())
    }

    @Test
    fun onePixelRounding_isToleratedAsTail() {
        val layout = Layout(
            totalItemsCount = 3,
            visibleItemsInfo = listOf(Item(2, 600, 401)),
        )
        assertTrue(layout.isAtTail())
    }
}
