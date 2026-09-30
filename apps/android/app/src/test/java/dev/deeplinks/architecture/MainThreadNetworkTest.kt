package dev.deeplinks.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 回归守卫（2026-09-30 真机闪退）：`ConnectionPool.evictAll()` 要写 TLS 关闭帧，是真正的网络 I/O。
 * 在主线程（例如 Activity 生命周期回调、网络回调）直接调用会抛 `NetworkOnMainThreadException` 闪退
 * ——远程配对下冷启动必现（`WorkspaceActivity` 的 `ON_START` → `HostHttp.evictIdleRemote()`）。
 *
 * 因此**只允许** `core/HostHttp.kt` 直接碰连接池，并且都要走它内部的主线程判定 + 后台派发。
 * 其它文件再直接写 `evictAll()` 就会被这条测试拦住。
 */
class MainThreadNetworkTest {

    @Test
    fun `连接池驱逐只允许出现在 HostHttp`() {
        val root = mainSourceRoot()
        val offenders = mutableListOf<String>()
        for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val rel = file.relativeTo(root).path.replace(File.separatorChar, '/')
            if (rel == "dev/deeplinks/core/HostHttp.kt") continue
            file.readLines().forEachIndexed { index, line ->
                if (line.contains(".evictAll(")) {
                    offenders += "$rel:${index + 1}"
                }
            }
        }
        assertTrue(
            "连接池驱逐只能在 core/HostHttp.kt 内、经后台派发执行；发现其它调用点：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private fun mainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "找不到 src/main/java" }
        return File(dir, "src/main/java")
    }
}
