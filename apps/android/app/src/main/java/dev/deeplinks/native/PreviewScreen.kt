package dev.deeplinks.native

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.Host
import dev.deeplinks.core.L
import dev.deeplinks.core.PreviewLocalProxy
import dev.deeplinks.core.hostPreviewExchange
import dev.deeplinks.core.hostPreviewWebSocket
import dev.deeplinks.core.previewCopied
import dev.deeplinks.core.previewEmpty
import dev.deeplinks.core.previewExit
import dev.deeplinks.core.previewLimit
import dev.deeplinks.core.previewOpenBrowser
import dev.deeplinks.core.previewRefresh
import dev.deeplinks.core.previewTitle
import dev.deeplinks.native.ui.DshListRow
import dev.deeplinks.native.ui.DshSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

@Composable
internal fun PreviewEntrySheet(client: MobileApiClient, host: Host, onDismiss: () -> Unit) {
    var items by remember { mutableStateOf<List<MobilePreview>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<OpenPreview?>(null) }
    LaunchedEffect(Unit) {
        try {
            items = withContext(Dispatchers.IO) { client.listPreviews() }
            error = null
        } catch (e: Exception) {
            error = e.message
        }
    }
    val current = open
    if (current != null) {
        PreviewScreen(current.title, current.url) {
            current.proxy.close()
            open = null
            onDismiss()
        }
        return
    }
    DshSheet(onDismiss = onDismiss, title = L.previewTitle, subtitle = L.previewLimit) {
        when {
            error != null -> Text(error.orEmpty(), color = Dsh.labelSecondary, style = DshType.body, modifier = Modifier.padding(DshSpace.s16))
            items.isEmpty() -> Text(L.previewEmpty, color = Dsh.labelSecondary, style = DshType.body, modifier = Modifier.padding(DshSpace.s16))
            else -> items.forEach { item ->
                DshListRow(
                    title = item.label,
                    subtitle = "127.0.0.1:${item.port}",
                    subtitleMono = true,
                    onClick = {
                        val proxy = PreviewLocalProxy(
                            exchange = { hostPreviewExchange(host, it) },
                            webSocket = { forward, socket, accept -> hostPreviewWebSocket(host, forward, socket, accept) },
                        )
                        proxy.start()
                        open = OpenPreview(item.label, proxy, proxy.localUrl(item.previewId))
                    },
                )
            }
        }
    }
}

private data class OpenPreview(val title: String, val proxy: PreviewLocalProxy, val url: String)

@Composable
private fun PreviewScreen(title: String, url: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val port = remember(url) { Uri.parse(url).port }
    var webView by remember { mutableStateOf<WebView?>(null) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true),
    ) {
        Column(Modifier.fillMaxSize().background(Dsh.bgBase)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = DshSpace.s16, vertical = DshSpace.s8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DshSpace.s8),
            ) {
                Text(title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = DshType.title, color = Dsh.labelPrimary)
                PreviewAction(L.previewRefresh) { webView?.reload() }
                PreviewAction(L.previewOpenBrowser) {
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("preview", url))
                    Toast.makeText(context, L.previewCopied, Toast.LENGTH_SHORT).show()
                }
                PreviewAction(L.previewExit, onClose)
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { createPreviewWebView(it, url, port).also { view -> webView = view } },
                onRelease = { clearPreviewWebView(it, port) },
            )
        }
    }
}

@Composable
private fun PreviewAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Dsh.accentIcon,
        style = DshType.label,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = DshSpace.s8),
    )
}

@Suppress("DEPRECATION")
@SuppressLint("SetJavaScriptEnabled")
private fun createPreviewWebView(context: android.content.Context, url: String, port: Int): WebView =
    WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.domStorageEnabled = true
        webViewClient = PreviewWebClient(port)
        loadUrl(url)
    }

/**
 * 退出时只清这次预览本机源（127.0.0.1 / localhost + 代理端口）的 Cookie 与存储，
 * 不动 App 里其他 WebView（Mermaid / 公式 / 代码高亮）的数据。
 */
private fun clearPreviewWebView(view: WebView, port: Int) {
    view.stopLoading()
    val cookies = CookieManager.getInstance()
    for (origin in previewOrigins(port)) {
        for (name in previewCookieNames(cookies.getCookie(origin))) {
            cookies.setCookie(origin, "$name=; Max-Age=0; Path=/")
        }
        WebStorage.getInstance().deleteOrigin(origin)
    }
    cookies.flush()
    view.clearCache(true)
    view.clearHistory()
    view.destroy()
}

private class PreviewWebClient(private val port: Int) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url ?: return true
        if (isLocal(uri)) return false
        if (uri.scheme == "http" || uri.scheme == "https") {
            view.context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (uri != null && isLocal(uri)) return null
        return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    private fun isLocal(uri: Uri): Boolean {
        val name = uri.host ?: return false
        return (name == "127.0.0.1" || name == "localhost") && uri.port == port
    }
}

internal fun previewOrigins(port: Int): List<String> = listOf("http://127.0.0.1:$port", "http://localhost:$port")

/** `CookieManager.getCookie` 返回 `a=1; b=2`，取出名字用于逐个过期。 */
internal fun previewCookieNames(header: String?): List<String> =
    header.orEmpty().split(';').mapNotNull { part ->
        part.substringBefore('=').trim().takeIf { it.isNotEmpty() }
    }.distinct()
