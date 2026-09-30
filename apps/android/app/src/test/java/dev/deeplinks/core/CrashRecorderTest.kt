package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * K0 崩溃记录：脱敏规则、环形缓冲、文件轮转。全部是纯函数 / 文件操作，无 Android 依赖。
 */
class CrashRecorderTest {

    // ---------- redactCrashText ----------

    @Test
    fun `URL 去掉 query 与 fragment 但保留主机与路径`() {
        val out = redactCrashText("see https://10.0.0.2:18640/api/x?token=abc&a=1#frag now")
        assertTrue(out.contains("https://10.0.0.2:18640/api/x"))
        assertFalse(out.contains("?token"))
        assertFalse(out.contains("frag"))
    }

    @Test
    fun `token 参数值被替换`() {
        val out = redactCrashText("Get /x?token=deadbeefsecret HTTP/1.1")
        assertTrue(out.contains("<redacted>"))
        assertFalse(out.contains("deadbeefsecret"))
    }

    @Test
    fun `Authorization 头值被替换`() {
        val out = redactCrashText("Authorization: Bearer ABC / header authorization=\"zzz\"")
        assertFalse(out.contains("ABC"))
        assertFalse(out.contains("zzz"))
    }

    @Test
    fun `Bearer 值被替换`() {
        val out = redactCrashText("Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig")
        assertTrue(out.contains("Bearer <redacted>"))
        assertFalse(out.contains("eyJhbGciOiJIUzI1NiJ9"))
    }

    @Test
    fun `32 位以上 hex 串被替换`() {
        val hex = "a".repeat(40)
        val out = redactCrashText("fingerprint=$hex end")
        assertFalse(out.contains(hex))
        assertTrue(out.contains("<redacted>"))
    }

    @Test
    fun `长 base64 串被替换`() {
        val b64 = "QWxhZGRpbjpvcGVuIHNlc2FtZVZhbHVlMTIzNDU2Nzg5MA"
        val out = redactCrashText("key $b64 done")
        assertFalse(out.contains(b64))
        assertTrue(out.contains("<redacted>"))
    }

    @Test
    fun `局域网 IP 保留`() {
        val out = redactCrashText("connect to 192.168.1.20:18640 failed")
        assertTrue(out.contains("192.168.1.20:18640"))
    }

    @Test
    fun `普通堆栈行不受影响`() {
        val stack = "java.lang.IllegalStateException: boom\n\tat dev.deeplinks.native.WorkspaceActivityKt.render(WorkspaceActivity.kt:100)"
        val out = redactCrashText(stack)
        assertEquals(stack, out)
    }

    // ---------- BreadcrumbBuffer ----------

    @Test
    fun `环形缓冲超过上限丢弃最旧的`() {
        val buffer = BreadcrumbBuffer(30)
        repeat(40) { buffer.add("item-$it") }
        val snap = buffer.snapshot()
        assertEquals(30, snap.size)
        assertEquals("item-10", snap.first())
        assertEquals("item-39", snap.last())
    }

    // ---------- 文件轮转 ----------

    @Test
    fun `轮转只保留三份且最新在 base`() {
        val dir = createTempDir("crash-test")
        try {
            rotateAndWrite(dir, "one", CrashRecorder.MAX_FILES)
            rotateAndWrite(dir, "two", CrashRecorder.MAX_FILES)
            rotateAndWrite(dir, "three", CrashRecorder.MAX_FILES)
            rotateAndWrite(dir, "four", CrashRecorder.MAX_FILES)

            assertEquals("four", File(dir, CrashRecorder.BASE_NAME).readText())
            assertEquals("three", File(dir, "${CrashRecorder.BASE_NAME}.1").readText())
            assertEquals("two", File(dir, "${CrashRecorder.BASE_NAME}.2").readText())
            assertFalse(File(dir, "${CrashRecorder.BASE_NAME}.3").exists())
            // 磁盘上一共只有 3 份
            assertEquals(3, dir.listFiles()!!.count { it.name.startsWith(CrashRecorder.BASE_NAME) })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `截断按字节上限`() {
        val text = "x".repeat(100)
        val out = truncateBytes(text, 10)
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= 10 + "\n…（已截断）".toByteArray(Charsets.UTF_8).size)
    }
}

private fun createTempDir(prefix: String): File =
    File.createTempFile(prefix, "").let { it.delete(); it.mkdirs(); it }
