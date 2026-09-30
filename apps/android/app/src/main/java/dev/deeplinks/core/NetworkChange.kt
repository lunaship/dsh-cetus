package dev.deeplinks.core

/**
 * 默认网络回调该怎么处置（第三轮 S5）。
 *
 * 之前 `onAvailable` / `onLost` / `onLinkPropertiesChanged` 一律清空连接池 + 作废选路缓存，
 * 但注册回调时系统会立刻回调一次 `onAvailable`，蜂窝网络下 `onLinkPropertiesChanged` 还很频繁，
 * 结果冷启动刚建的池子被反复清掉。这里只区分三种处置。
 */
enum class NetworkChangeAction {
    /** 什么都不用做（仅 DNS 等属性变化）。 */
    None,

    /** 默认网络真正切换：清连接池 + 作废选路缓存。 */
    ResetPool,

    /** 还是同一张网，只有 IP 变了：只作废选路缓存，不动连接池。 */
    InvalidateRoutes,
}

/**
 * 纯函数判定默认网络回调的处置：
 * - `prevHandle == null`：首次回调只登记，不清池（[NetworkChangeAction.None]）；
 * - `newHandle == null`：`onLost`，清池；
 * - handle 变了：默认网络切换，清池；
 * - handle 没变但 IP 集合变了：只作废选路缓存；
 * - 其余：[NetworkChangeAction.None]。
 */
fun networkChangeAction(
    prevHandle: Long?,
    newHandle: Long?,
    prevAddrs: Set<String>,
    newAddrs: Set<String>,
): NetworkChangeAction = when {
    prevHandle == null -> NetworkChangeAction.None
    newHandle == null -> NetworkChangeAction.ResetPool
    prevHandle != newHandle -> NetworkChangeAction.ResetPool
    prevAddrs != newAddrs -> NetworkChangeAction.InvalidateRoutes
    else -> NetworkChangeAction.None
}
