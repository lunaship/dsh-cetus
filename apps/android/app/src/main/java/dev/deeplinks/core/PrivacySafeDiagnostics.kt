package dev.deeplinks.core

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * 只在 debug 构建输出的关键路径诊断。
 *
 * API 刻意只收枚举、布尔与计数：没有任何 String 形参，调用方无法把地址、token、
 * 会话 ID、消息正文或文件路径写进日志（比事后脱敏更可靠）。Release 构建 [enabled] 恒为 false，
 * 不产生任何输出。思路参考 Clarklevis1995/dsh-mobile（MIT）的 AndroidGatewayDiagnostics。
 */
object PrivacySafeDiagnostics {
    enum class Area(val wire: String) {
        Stream("stream"),
        Queue("queue"),
        Goal("goal"),
        Schedule("schedule"),
        File("file"),
    }

    enum class Op(val wire: String) {
        Batch("batch"),
        Load("load"),
        Edit("edit"),
        Remove("remove"),
        Steer("steer"),
        Pause("pause"),
        Resume("resume"),
        Clear("clear"),
        Update("update"),
        Delete("delete"),
        Verify("verify"),
    }

    @Volatile
    var enabled: Boolean = false
        private set

    /** 测试注入；生产走 android.util.Log。 */
    internal var writer: (priority: Int, tag: String, message: String) -> Unit = { p, t, m -> Log.println(p, t, m) }

    private const val TAG = "DshDiag"
    private const val SAMPLE_EVERY = 50L
    private val streamBatches = AtomicLong()

    fun install(debuggable: Boolean) {
        enabled = debuggable
    }

    /** 一次操作的结果；[count] 是条目数 / 字节数等非敏感计数。 */
    fun event(area: Area, op: Op, ok: Boolean, count: Int? = null) {
        if (!enabled) return
        val tail = count?.let { " count=$it" }.orEmpty()
        writer(if (ok) Log.INFO else Log.WARN, TAG, "${area.wire} ${op.wire} ok=$ok$tail")
    }

    /** 流式合并：每 [SAMPLE_EVERY] 批采样一次（输入条目数 → 合并后条目数），避免刷屏。 */
    fun streamBatch(received: Int, applied: Int) {
        if (!enabled) return
        val n = streamBatches.incrementAndGet()
        if (n == 1L || n % SAMPLE_EVERY == 0L) {
            writer(Log.DEBUG, TAG, "stream batch n=$n received=$received applied=$applied")
        }
    }

    internal fun resetForTest() {
        streamBatches.set(0)
    }
}
