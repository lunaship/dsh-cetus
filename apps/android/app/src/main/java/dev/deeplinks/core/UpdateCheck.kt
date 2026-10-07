package dev.deeplinks.core

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

data class AppRelease(
    val tagName: String,
    val htmlUrl: String,
    val publishedAt: String,
)

private const val DAY_MS = 24L * 60L * 60L * 1000L
private const val PREFS = "dsh_settings"
private const val KEY_ENABLED = "update_check_enabled"
private const val KEY_CHECKED = "update_last_checked_at"
private const val KEY_TAG = "update_latest_tag"
private const val KEY_URL = "update_latest_url"

const val GITHUB_RELEASES_URL = "https://api.github.com/repos/lunaship/dsh-cetus/releases?per_page=10"

/** 只认 App 的 `app-v*` 正式发布，插件 tag 和草稿都丢掉。 */
fun parseReleases(json: String): List<AppRelease> {
    val array = JSONArray(json)
    val out = ArrayList<AppRelease>(array.length())
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        if (item.optBoolean("draft", false)) continue
        val tag = item.optString("tag_name")
        if (!tag.startsWith("app-v")) continue
        out += AppRelease(
            tagName = tag,
            htmlUrl = item.optString("html_url"),
            publishedAt = item.optString("published_at"),
        )
    }
    return out
}

/**
 * 正数表示 [a] 比 [b] 新。主版本按数字段比较，预发布里的数字也按数字比（`beta.10` > `beta.9`）。
 * 同一主版本下，没有预发布标签的正式版比任何预发布新。
 */
fun compareVersions(a: String, b: String): Int {
    val left = splitVersion(a)
    val right = splitVersion(b)
    val width = maxOf(left.numbers.size, right.numbers.size)
    for (i in 0 until width) {
        val delta = left.numbers.getOrElse(i) { 0 } - right.numbers.getOrElse(i) { 0 }
        if (delta != 0) return delta
    }
    if (left.pre.isEmpty() && right.pre.isNotEmpty()) return 1
    if (left.pre.isNotEmpty() && right.pre.isEmpty()) return -1
    val preWidth = maxOf(left.pre.size, right.pre.size)
    for (i in 0 until preWidth) {
        val x = left.pre.getOrNull(i)
        val y = right.pre.getOrNull(i)
        if (x == null) return -1
        if (y == null) return 1
        val xi = x.toIntOrNull()
        val yi = y.toIntOrNull()
        val delta = if (xi != null && yi != null) xi - yi else x.compareTo(y)
        if (delta != 0) return delta
    }
    return 0
}

fun shouldCheck(lastCheckedAt: Long, now: Long): Boolean {
    if (lastCheckedAt <= 0L) return true
    return now - lastCheckedAt >= DAY_MS
}

/**
 * 更新链接只接受 github.com 上这个仓库的 https 地址。
 * 用 [java.net.URI] 取 host / path：JVM 单测里的 android.net.Uri 是空实现。
 */
fun isGithubReleaseUrl(url: String): Boolean {
    if (!url.startsWith("https://", ignoreCase = true)) return false
    val uri = try {
        java.net.URI(url)
    } catch (_: Exception) {
        return false
    }
    val host = uri.host ?: return false
    if (!host.equals("github.com", ignoreCase = true)) return false
    val path = uri.path ?: return false
    return path.startsWith("/lunaship/dsh-cetus/")
}

fun newerRelease(currentVersionName: String, releases: List<AppRelease>): AppRelease? {
    return releases
        .filter { isGithubReleaseUrl(it.htmlUrl) }
        .filter { compareVersions(it.tagName, currentVersionName) > 0 }
        .maxWithOrNull { a, b -> compareVersions(a.tagName, b.tagName) }
}

fun displayVersion(tagOrName: String): String {
    return tagOrName.removePrefix("app-v").removePrefix("v")
}

object UpdateCheckPrefs {
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun lastCheckedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_CHECKED, 0L)

    fun setLastCheckedAt(context: Context, value: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_CHECKED, value).apply()
    }

    fun cachedNewer(context: Context): AppRelease? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tag = prefs.getString(KEY_TAG, null)?.takeIf { it.isNotBlank() } ?: return null
        val url = prefs.getString(KEY_URL, null)?.takeIf { isGithubReleaseUrl(it) } ?: return null
        return AppRelease(tag, url, "")
    }

    fun setCachedNewer(context: Context, release: AppRelease?) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (release == null) {
            edit.remove(KEY_TAG).remove(KEY_URL)
        } else {
            edit.putString(KEY_TAG, release.tagName).putString(KEY_URL, release.htmlUrl)
        }
        edit.apply()
    }
}

object UpdateChecker {
    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 后台调用。失败只留面包屑，不抛给界面。 */
    fun checkBlocking(context: Context, versionName: String, url: String = GITHUB_RELEASES_URL, now: Long = System.currentTimeMillis()) {
        if (!UpdateCheckPrefs.enabled(context)) return
        if (!shouldCheck(UpdateCheckPrefs.lastCheckedAt(context), now)) return
        UpdateCheckPrefs.setLastCheckedAt(context, now)
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "cetus-Android/$versionName")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    CrashRecorder.breadcrumb("update", "http ${response.code}")
                    return
                }
                val body = response.body.string()
                val newer = newerRelease(versionName, parseReleases(body))
                UpdateCheckPrefs.setCachedNewer(context, newer)
            }
        } catch (_: Exception) {
            CrashRecorder.breadcrumb("update", "check failed")
        }
    }
}

private data class SplitVersion(val numbers: List<Int>, val pre: List<String>)

private fun splitVersion(raw: String): SplitVersion {
    var text = raw.trim()
    text = when {
        text.startsWith("app-v") -> text.removePrefix("app-v")
        text.startsWith("v") -> text.removePrefix("v")
        else -> text
    }
    val cut = text.split('-', limit = 2)
    val numbers = cut[0].split('.').map { it.toIntOrNull() ?: 0 }
    val pre = if (cut.size < 2 || cut[1].isBlank()) emptyList() else cut[1].split('.')
    return SplitVersion(numbers, pre)
}
