package dev.deeplinks.core

import android.content.Context
import android.os.Build
import android.os.Process
import dev.deeplinks.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 本地崩溃记录（第三轮 K0）。
 *
 * 为什么要有它：用户手机连不上电脑，闪退后拿不到 logcat，等于断案没有证据。这里把未捕获异常
 * 写进 App 私有目录（`noBackupFilesDir/crash/`，不会被云备份带走），设置页可以查看 / 复制 / 分享 / 清除。
 *
 * 约束：
 * - 记录完**必须**调用原 handler，让系统照常结束进程（否则会变成 ANR / 假死）。
 * - 写入要同步落盘（`fd.sync()`），因为进程马上就会死。
 * - 只保留最近 3 份（`last-crash.txt`、`.1`、`.2`），上限 64KB。
 * - 崩溃文本一律先过 [redactCrashText] 脱敏：URL 去掉 query，长 hex / base64、token、Authorization、Bearer 值替换成 `<redacted>`。
 */
object CrashRecorder {
    /** 内存里保留的面包屑条数。 */
    const val MAX_BREADCRUMBS = 30

    /** 磁盘上保留的崩溃文件份数（`last-crash.txt` + `.1` + `.2`）。 */
    const val MAX_FILES = 3

    /** 单份崩溃文本上限（字节）。 */
    const val MAX_TEXT_BYTES = 64 * 1024

    private const val DIR_NAME = "crash"
    internal const val BASE_NAME = "last-crash.txt"

    private val buffer = BreadcrumbBuffer(MAX_BREADCRUMBS)
    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var installed = false

    /** 在 [android.app.Application.onCreate] 最先调用。重复调用只生效一次。 */
    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (installed) return
        installed = true
        val crashDir = crashDir(app)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashReport(crashDir, thread, throwable, buffer.snapshot()) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
    }

    /** 记一条面包屑（只留在内存，崩溃时随报告写出）。不记录消息正文、标题、路径。 */
    fun breadcrumb(tag: String, message: String) {
        buffer.add("$tag: $message")
    }

    /** 最近一份崩溃文本；没有则返回 null。 */
    fun readLatest(): String? {
        val ctx = appContext ?: return null
        val f = File(crashDir(ctx), BASE_NAME)
        return runCatching { if (f.exists()) f.readText(Charsets.UTF_8) else null }.getOrNull()
    }

    /** 是否有崩溃记录。 */
    fun hasCrash(): Boolean = readLatest()?.isNotBlank() == true

    /** 清除全部崩溃记录。 */
    fun clear() {
        val ctx = appContext ?: return
        synchronized(lock) {
            runCatching {
                val dir = crashDir(ctx)
                if (dir.isDirectory) {
                    dir.listFiles()?.forEach { it.delete() }
                }
            }
        }
    }

    internal fun crashDir(context: Context): File = File(context.noBackupFilesDir, DIR_NAME)

    // ---------- 内部：写盘与报告拼装 ----------

    private fun writeCrashReport(
        crashDir: File,
        thread: Thread,
        throwable: Throwable,
        crumbs: List<String>,
    ) {
        val report = redactCrashText(buildCrashReport(thread, throwable, crumbs))
        synchronized(lock) {
            runCatching { rotateAndWrite(crashDir, report, MAX_FILES) }
        }
    }

    private fun buildCrashReport(thread: Thread, throwable: Throwable, crumbs: List<String>): String {
        val sb = StringBuilder()
        sb.append("时间: ").append(isoNow()).append('\n')
        sb.append("版本: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        sb.append("系统: Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        sb.append("线程: ").append(thread.name).append('\n')
        sb.append("--- 面包屑（最近 ").append(crumbs.size).append(" 条） ---\n")
        crumbs.forEach { sb.append(it).append('\n') }
        sb.append("--- 堆栈 ---\n")
        appendThrowable(sb, throwable, 0)
        val text = sb.toString()
        return truncateBytes(text, MAX_TEXT_BYTES)
    }

    private fun appendThrowable(sb: StringBuilder, t: Throwable, depth: Int) {
        var current: Throwable? = t
        var level = depth
        while (current != null) {
            val indent = "  ".repeat(level)
            sb.append(indent).append(current.javaClass.name)
            current.message?.let { sb.append(": ").append(it) }
            sb.append('\n')
            current.stackTrace.forEach { el -> sb.append(indent).append("    at ").append(el).append('\n') }
            current = current.cause
            level++
            if (level > 20) break
        }
    }
}

/** 内存环形缓冲：超过 [max] 条丢弃最旧的。 */
class BreadcrumbBuffer(private val max: Int) {
    private val items = ArrayDeque<String>()

    @Synchronized
    fun add(item: String) {
        items.addLast(item)
        while (items.size > max) items.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = items.toList()

    @Synchronized
    fun clear() = items.clear()
}

/**
 * 把崩溃文本轮转写入：`last-crash.txt` → `.1` → `.2`，只保留 [maxFiles] 份。
 * 同步落盘（`fd.sync()`）。纯文件操作，可在 JVM 单测里用临时目录直接跑。
 */
internal fun rotateAndWrite(dir: File, text: String, maxFiles: Int) {
    dir.mkdirs()
    val base = File(dir, CrashRecorder.BASE_NAME)
    if (base.exists()) {
        for (i in maxFiles - 1 downTo 1) {
            val src = if (i == 1) base else File(dir, "${CrashRecorder.BASE_NAME}.${i - 1}")
            if (src.exists()) {
                val dst = File(dir, "${CrashRecorder.BASE_NAME}.$i")
                if (dst.exists()) dst.delete()
                src.renameTo(dst)
            }
        }
    }
    // 清掉可能残留的多余份数
    dir.listFiles()?.forEach { f ->
        val name = f.name
        if (name.startsWith("${CrashRecorder.BASE_NAME}.")) {
            val suffix = name.removePrefix("${CrashRecorder.BASE_NAME}.").toIntOrNull()
            if (suffix != null && suffix >= maxFiles) f.delete()
        }
    }
    FileOutputStream(base).use { out ->
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
        out.fd.sync()
    }
}

/** 崩溃文本脱敏：URL 去 query；token / Authorization / Bearer 值、长 hex / base64 串替换成 `<redacted>`。局域网 IP 保留。 */
fun redactCrashText(text: String): String {
    var out = text
    // 1. URL 去掉 query 与 fragment（保留 scheme://host:port/path）。
    out = Regex("""https?://[^\s"'<>()\[\]{}]+""").replace(out) { m ->
        val raw = m.value
        val cut = raw.indexOfFirst { it == '?' || it == '#' }
        if (cut >= 0) raw.substring(0, cut) else raw
    }
    // 2. Bearer <token>（先于 Authorization，避免 "Authorization: Bearer x" 只吃掉 "Bearer"）
    out = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""").replace(out) { "Bearer <redacted>" }
    // 3. Authorization 后面的值（Authorization: xxx / "authorization":"xxx"）
    out = Regex("""(?i)\bauthorization\b["']?\s*[:=]\s*["']?([^\s"',;]+)""").replace(out) {
        "Authorization=<redacted>"
    }
    // 4. token=... 的值
    out = Regex("""(?i)\btoken=([^\s&"'<>]+)""").replace(out) { "token=<redacted>" }
    // 5. 长 hex 串（>=32 位）
    out = Regex("""\b[0-9a-fA-F]{32,}\b""").replace(out) { "<redacted>" }
    // 6. 长 base64 / base64url 串（>=32 位）
    out = Regex("""\b[A-Za-z0-9+/_-]{32,}={0,2}\b""").replace(out) { "<redacted>" }
    return out
}

/** ISO-8601 本地时间（含时区偏移）。 */
internal fun isoNow(): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    fmt.timeZone = TimeZone.getDefault()
    return fmt.format(Date())
}

/** 按 UTF-8 字节上限截断，附截断标记。 */
internal fun truncateBytes(text: String, maxBytes: Int): String {
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return text
    val marker = "\n…（已截断）"
    val body = text.toByteArray(Charsets.UTF_8).copyOf(maxBytes)
    // 避免在多字节字符中间截断
    val decoded = String(body, Charsets.UTF_8)
    return decoded.dropLastWhile { it == '\uFFFD' } + marker
}
