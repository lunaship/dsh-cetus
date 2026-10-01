package dev.deeplinks.native

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 会话级远程图片加载策略（第 2 步 B2）。
 *
 * - autoLoad：设置页「隐私 · 自动加载对话中的网络图片」（本地 SharedPreferences）。
 * - allowed：本次会话里用户点按放行的 URL；只存内存，滚出屏幕不丢、切会话即失效。
 *
 * 为什么提升到会话级：原来每条消息各自 remember 一份放行集合，消息滚出屏幕再回来
 * 占位卡重新出现；且直接读 SharedPreferences 没有 Compose 状态，设置改了不一定重组。
 */
@Stable
class RemoteImagePolicy(autoLoad: Boolean) {
    var autoLoad by mutableStateOf(autoLoad)
    private val allowed = mutableStateSetOf<String>()

    /** 用户点按了某张图的占位卡：放行这一个 URL。 */
    fun allow(url: String) {
        allowed.add(url)
    }

    fun shouldLoad(url: String): Boolean = shouldLoadRemoteImage(autoLoad, allowed, url)
}

/** 纯函数：是否加载这张图（自动加载开着，或本会话已点按放行）。 */
internal fun shouldLoadRemoteImage(autoLoad: Boolean, allowed: Set<String>, url: String): Boolean =
    autoLoad || url in allowed

/**
 * 对话态默认策略：**不自动加载**。远程图片可能被提示词注入用来外泄内容与暴露 IP，
 * 默认让用户点按单张确认后才发请求。真实值由会话页根（WorkspaceScreen）注入。
 */
val LocalRemoteImagePolicy = staticCompositionLocalOf { RemoteImagePolicy(autoLoad = false) }
