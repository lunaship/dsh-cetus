package dev.deeplinks.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * 「立刻重新探测电脑」的信令（R4）。
 *
 * 放在 `core` 是为了让 `DshApplication`（网络回调）也能发信号，而 `core` 不依赖 `native.util`。
 * 首页的探测循环监听 [probeNow]，被唤醒时立刻进入下一轮，不必等满 30 秒。
 */
object ConnectivitySignals {
    /** `onLinkPropertiesChanged` 触发很频繁：2 秒内的重复触发直接忽略。 */
    private const val DEBOUNCE_MS = 2_000L

    private val _probeNow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val probeNow: SharedFlow<Unit> = _probeNow

    private val lastRequest = AtomicLong(0)

    /** 请求一次立即探测（网络变化 / 回前台 / 用户点「重试」）。2 秒去抖。 */
    fun requestProbe() {
        val now = System.currentTimeMillis()
        val previous = lastRequest.get()
        if (now - previous < DEBOUNCE_MS) return
        if (!lastRequest.compareAndSet(previous, now)) return
        _probeNow.tryEmit(Unit)
    }
}
