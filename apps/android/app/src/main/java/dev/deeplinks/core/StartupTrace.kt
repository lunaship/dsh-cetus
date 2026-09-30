package dev.deeplinks.core

import android.os.SystemClock
import android.util.Log

/**
 * 启动耗时打点（第三轮 S7）：记录进程启动以来各关键节点的毫秒数，同时写 logcat（tag `DshStartup`）
 * 和 K0 的面包屑，供冷启动前后对比。取值用 [SystemClock.elapsedRealtime]，不受系统时间调整影响。
 */
object StartupTrace {
    const val TAG = "DshStartup"

    @Volatile
    private var startMs: Long = 0L

    /** 在进程最开始（[android.app.Application.onCreate]）调用一次。重复调用只记第一次。 */
    fun markStart() {
        if (startMs != 0L) return
        startMs = SystemClock.elapsedRealtime()
        mark("app_init")
    }

    fun mark(name: String, detail: String? = null) {
        val line = formatStartupMark(name, elapsedMs(), detail)
        // 单测（JVM）里 android.util.Log 未 mock，打点不能把主流程带崩。
        runCatching { Log.i(TAG, line) }
        CrashRecorder.breadcrumb("startup", line)
    }

    fun elapsedMs(): Long = if (startMs == 0L) 0L else SystemClock.elapsedRealtime() - startMs
}

/** 打点行：`home_first_frame +312ms (cache)`。纯函数，可单测。 */
fun formatStartupMark(name: String, elapsedMs: Long, detail: String? = null): String {
    val suffix = detail?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
    return "$name +${elapsedMs}ms$suffix"
}
