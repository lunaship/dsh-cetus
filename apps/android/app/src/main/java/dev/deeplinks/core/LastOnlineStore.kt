package dev.deeplinks.core

import android.content.Context

/**
 * 电脑「最后一次在线」的本地记录（2026-09-28 重设计 · 稿 08 离线态）。
 *
 * 为什么要有这个：`Host` 里没有「电脑最后在线时间」（`lastSeenAt` 是**已配对设备**的字段，
 * 不是电脑的），而离线态要写「离线 · 10 分钟前在线」。所以本端自己记一笔：
 * 每次探测到电脑可达就刷新一次时间戳，只存本机、不进服务端。
 *
 * 读不到（首次安装、从没连过）时返回 0，UI 退化成只写「离线」。
 */
object LastOnlineStore {
    private const val PREFS = "dsh_settings"
    private const val KEY = "host_last_online_at"

    fun record(context: Context, at: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY, at)
            .apply()
    }

    fun read(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY, 0L)
}
