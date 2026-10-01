package dev.deeplinks.native

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Mermaid 渲染链路验证（共享离屏 WebView → 位图）：
 * `graph TD; A-->B;` 应产出非空且含不透明像素的位图。需要设备 / 模拟器
 * （WebView 不可在 JVM 单测中运行）。CI 由 `ci-android.yml` 的模拟器 job 执行
 * ——此前该 job 只跑 StartupSmokeTest，渲染器测试从未在 CI 真机验证过。
 *
 * 失败排查顺序（方案第 5 步 D1）：
 * 1. CSP：Chromium 对 file:// 页面的 `'self'` 匹配不可靠，本地 bundle 的 file:
 *    子资源可能被拦。两个 PAGE_HTML 的 scheme 列表已显式带 `file:`；若仍失败，
 *    看 logcat 的 MermaidRenderer 标签（`render: JS ok=false` / `zero size`）。
 * 2. `domStorageEnabled`：若 logcat 报 DOM 存储相关错误，把 WebViewSecurity.kt
 *    的 `domStorageEnabled` 改回 `true` 并注释原因。
 */
@RunWith(AndroidJUnit4::class)
class MermaidRendererInstrumentedTest {

    /**
     * 与 [MathRendererInstrumentedTest] 同一理由：销毁共享离屏 WebView，
     * 避免残留的 Chromium 沙箱进程阻塞同一 Instrumentation 进程内后续的
     * Compose UI 测试（Espresso 主线程空闲检测挂起、真机白屏无响应）。
     */
    @After
    fun tearDown() = runBlocking(Dispatchers.Main) {
        MermaidRenderer.resetForTests()
    }

    @Test
    fun rendersSimpleFlowchartToBitmapWithVisiblePixels() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MermaidRenderer.attach(context.applicationContext)
        val rendered = withTimeout(30_000) {
            MermaidRenderer.render("graph TD; A-->B;", dark = false)
        }
        assertNotNull("流程图应渲染成功（失败会回退等宽源码块）", rendered)
        rendered!!
        val bitmap = rendered.bitmap.asAndroidBitmap()
        assertTrue("位图应有宽度", bitmap.width > 0)
        assertTrue("位图应有高度", bitmap.height > 0)
        assertTrue("位图应含不透明像素", hasOpaquePixels(bitmap))
    }

    private fun hasOpaquePixels(bitmap: android.graphics.Bitmap): Boolean {
        val step = maxOf(1, minOf(bitmap.width, bitmap.height) / 32)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                if (bitmap.getPixel(x, y) and 0xFF000000.toInt() != 0) return true
                x += step
            }
            y += step
        }
        return false
    }
}
