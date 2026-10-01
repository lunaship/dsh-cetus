package dev.deeplinks.native

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 消息流「贴底」定位（WI-003）。
 *
 * 关键约束：数据（messages）提交后，[LazyListState.layoutInfo] 要等下一帧测量才会更新。
 * 在那之前读到的 totalItemsCount 仍是上一份布局（例如加载骨架只有 1 项），
 * 此时 scrollToItem(total - 1) 实际滚到新列表的第 0 项——即「加载更早」所在的顶部。
 * 2026-09-27 真机上打开长会话落在顶部即此竞态，所以定位前必须先等一次完整布局。
 */

/**
 * 等一次完整的「重组 + 测量」：数据提交后的第一帧内完成重组与布局，
 * 第二帧回调时 layoutInfo 必然已反映新数据。
 */
internal suspend fun awaitLayoutPass() {
    withFrameNanos { }
    withFrameNanos { }
}

/** 最后一项底部超出视口的像素（≤0 表示已完整露出）；最后一项不在可见区时返回 null。 */
internal fun LazyListLayoutInfo.tailOverflowPx(): Int? {
    val total = totalItemsCount
    if (total == 0) return 0
    val last = visibleItemsInfo.lastOrNull() ?: return null
    if (last.index != total - 1) return null
    // 底部输入区为半透明悬浮时最后一项需要在视口内留出输入区高度，这里仅做 0 判定
    return (last.offset + last.size) - (viewportEndOffset - afterContentPadding)
}

/** 列表是否已贴底（空列表视为贴底）。 */
internal fun LazyListLayoutInfo.isAtTail(tolerancePx: Int = 1): Boolean {
    val overflow = tailOverflowPx() ?: return false
    return overflow <= tolerancePx
}

/**
 * 把列表真正贴到底：scrollToItem 只会把条目顶到视口顶部，
 * 长回复需要再 scrollBy 把溢出部分推上去，否则最新内容仍在视口外。
 */
internal suspend fun LazyListState.alignToBottom() {
    val total = layoutInfo.totalItemsCount
    if (total == 0) return
    scrollToItem(total - 1)
    withFrameNanos { }
    repeat(2) {
        val overflow = layoutInfo.tailOverflowPx() ?: return
        if (overflow <= 0) return
        scrollBy(overflow.toFloat())
        withFrameNanos { }
    }
}

/**
 * 等内容就绪且布局已反映该内容后贴底；贴底后再核对一次，
 * 期间若头部「加载更早」/ 粘性摘要等条目晚一帧插入导致偏离，重新对齐（至多 [maxAttempts] 轮）。
 *
 * - [hasContent] 为 false、或列表尚未上屏时一直等待（例如会话刚切换、history 还没到）；
 * - 超时不做任何滚动：此时布局可能仍是骨架，盲滚只会落到错误位置——
 *   数据晚到时由 history 回调（followIfNearBottom）重新发起请求；
 * - 用户手势进行中不抢滚动，对齐轮间一旦检测到手势立即放弃。
 *
 * @return 是否最终贴底
 */
internal suspend fun LazyListState.positionAtTail(
    hasContent: () -> Boolean,
    timeoutMs: Long = 3000,
    maxAttempts: Int = 3,
): Boolean {
    withTimeoutOrNull(timeoutMs) {
        snapshotFlow { hasContent() && !isScrollInProgress }.first { it }
        awaitLayoutPass()
        // 列表不在屏上（手机停在首页、会话在后台恢复）时 totalItemsCount 为 0：
        // 此时没有可对齐的布局，不能当作「已贴底」返回，等它上屏或超时
        snapshotFlow { layoutInfo.totalItemsCount > 0 }.first { it }
    } ?: return false
    repeat(maxAttempts) {
        if (isScrollInProgress) return false
        alignToBottom()
        awaitLayoutPass()
        if (layoutInfo.isAtTail()) return true
    }
    return layoutInfo.isAtTail()
}
