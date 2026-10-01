package dev.deeplinks.native

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.IntBuffer
import kotlinx.coroutines.delay

/**
 * 收紧渲染不受信内容的离屏 WebView（Mermaid / KaTeX）。
 *
 * 只保留 JS 执行能力（本地 bundle 渲染所需），其余文件、跨源、ContentProvider
 * 访问全部关闭，并开启 Safe Browsing。不得在此开启任何新能力。
 *
 * 安全边界：
 * - 禁止一切网络加载（`blockNetworkLoads`），本地 bundle 无需联网。
 * - 禁止弹窗与多窗口，避免恶意内容跳出。
 * - 关闭数据库与 DOM 存储；Mermaid / KaTeX 不依赖它们。
 */
@SuppressLint("SetJavaScriptEnabled")
@Suppress(
    "ObsoleteSdkInt", // minSdk 26 已覆盖该 API；保留显式判断，便于将来下调 minSdk
    "DEPRECATION", // file-URL 跨源开关在 API 30 起废弃，但低版本仍需显式关闭
)
internal fun WebView.hardenUntrustedSettings() {
    settings.javaScriptEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.allowFileAccessFromFileURLs = false
    settings.allowUniversalAccessFromFileURLs = false
    settings.blockNetworkLoads = true
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(false)
    settings.databaseEnabled = false
    settings.domStorageEnabled = false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        settings.safeBrowsingEnabled = true
    }
}

/**
 * 本地可信 bundle（KaTeX / Mermaid 的离屏渲染页）用的设置。
 *
 * 与 [hardenUntrustedSettings] 的唯一区别是 `allowFileAccess = true`：页面以
 * `file:///android_asset/` 为基址，要能加载 `katex.min.js` / `katex.min.css` /
 * mermaid bundle 这些**我们自己的**本地资源。之前的「WebView 禁联网」加固把它
 * 一并关掉，导致公式渲染在 CI 模拟器上直接失败（`合法公式应渲染成功` 断言红）。
 *
 * 联网边界不受影响，且比原来更严：
 * - `blockNetworkLoads = true` + `shouldInterceptRequest` 只放行
 *   `file:///android_assert 前缀`（其余 403）；
 * - `allowUniversalAccessFromFileURLs = false`：file 页不能读网络（原有关键项保留）；
 * - `allowFileAccessFromFileURLs = false`：file 页不能跨源读别的 file（原有关键项保留）。
 */
fun WebView.hardenLocalBundleSettings() {
    hardenUntrustedSettings()
    settings.allowFileAccess = true
}
/**
 * 建一个离屏 WebView（KaTeX / Mermaid 共用）：透明背景、收紧设置、给定初始尺寸。
 * 离屏 WebView 不参与布局树，不给初始尺寸时页面排版宽度为 0。
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun createOffscreenWebView(
    context: Context,
    cssWidth: Int,
    cssHeight: Int,
    assetBaseUrl: String,
    html: String,
    onPageFinished: () -> Unit,
): WebView {
    val density = context.resources.displayMetrics.density
    val width = (cssWidth * density).toInt()
    val height = (cssHeight * density).toInt()
    val wv = WebView(context)
    wv.hardenLocalBundleSettings()
    wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    // 离屏渲染必须用软件图层：硬件加速的 WebView 不挂到窗口树上时，draw(canvas)
    // 在软件渲染环境（CI 模拟器的 swiftshader_indirect）下拿到的是全透明帧——
    // drawWebViewToBitmap 连画几轮都检测不到不透明像素，返回 null，公式/图表静默失败。
    // 只影响这个离屏渲染用的 WebView，App 内界面 WebView 不受影响。
    wv.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
    wv.measure(
        android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
        android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY),
    )
    wv.layout(0, 0, width, height)
    wv.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) = onPageFinished()

        // 兜底拦截：此 WebView 不得联网，只允许加载本地 asset bundle。
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            val url = request?.url?.toString() ?: return null
            // loadDataWithBaseURL 的主文档是 data:（基址 file:///android_asset/）——
            // 部分 Chromium 版本会把它送进拦截器，403 会把整页打成 chrome-error，
            // 公式/图表全部静默失败（「合法公式应渲染成功」）。主框架 data: 放行，
            // 子资源仍受页面 CSP（default-src 'none'）与本拦截器约束。
            if (request.isForMainFrame && url.startsWith("data:")) return null
            return if (url.startsWith("file:///android_asset/")) null
            else WebResourceResponse("text/plain", "utf-8", 403, "blocked", emptyMap(), null)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: android.webkit.WebResourceError?,
        ) {
            android.util.Log.w(
                "MathRenderer",
                "webview error: ${request?.url} code=${error?.errorCode} desc=${error?.description}",
            )
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
    }
    wv.loadDataWithBaseURL(assetBaseUrl, html, "text/html", "utf-8", null)
    return wv
}

/**
 * 解析 JS 回传值（evaluateJavascript 回调 / @JavascriptInterface 入参）：
 * WebView 对返回值做 JSON 编码，对象直接为 {"ok":true,...}；若 JS 端返回字符串
 * 则再包一层引号（"{\"ok\":...}"），两层都兼容。
 */
internal fun parseJsResult(json: String?): JSONObject? {
    if (json == null) return null
    runCatching { JSONObject(json) }.getOrNull()?.let { return it }
    return runCatching { JSONObject(JSONTokener(json).nextValue().toString()) }.getOrNull()
}

/**
 * 把离屏 WebView 绘制成位图。离屏 WebView 无 UI 循环：draw 回调后合成器可能还没提交
 * 可见帧，因此检测非全透明像素，空帧则延时重画（最多 [attempts] 轮）。
 */
internal suspend fun drawWebViewToBitmap(
    wv: WebView,
    physWidth: Int,
    physHeight: Int,
    offsetX: Int,
    offsetY: Int,
    attempts: Int = 4,
): Bitmap? {
    repeat(attempts) { attempt ->
        val bmp = Bitmap.createBitmap(physWidth, physHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.translate(-offsetX.toFloat(), -offsetY.toFloat())
        wv.draw(canvas)
        val pixels = IntArray(physWidth * physHeight)
        bmp.copyPixelsToBuffer(IntBuffer.wrap(pixels))
        if (pixels.any { (it ushr 24) != 0 }) return bmp
        bmp.recycle()
        if (attempt < attempts - 1) delay(200L)
    }
    return null
}
