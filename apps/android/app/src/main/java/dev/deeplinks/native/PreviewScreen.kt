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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.ui.text.style.TextAlign
import dev.deeplinks.core.previewCopyAddress
import dev.deeplinks.core.previewEmptyBody
import dev.deeplinks.core.previewEmptyTitle
import dev.deeplinks.core.previewVia
import dev.deeplinks.native.ui.v4.DlBottomSheet
import dev.deeplinks.native.ui.v4.DlListRow
import dev.deeplinks.native.ui.v4.DlRowTrailing
import dev.deeplinks.native.ui.v4.DlTone
import dev.deeplinks.native.ui.v4.DlTopBar
import dev.deeplinks.native.ui.v4.DlTopBarAction
import dev.deeplinks.native.ui.v4.DlTopBarNav
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
import dev.deeplinks.core.previewLimit
import dev.deeplinks.core.previewRefresh
import dev.deeplinks.core.previewTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

@OptIn(ExperimentalMaterial3Api::class)
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
    DlBottomSheet(onDismissRequest = onDismiss, title = L.previewTitle) {
        when {
            error != null -> DlListRow(
                title = error.orEmpty(),
                leading = WarningOutline16,
                leadingTint = DlTone.Err,
            )
            items.isEmpty() -> PreviewEmptyState()
            else -> {
                items.forEach { item ->
                    DlListRow(
                        title = item.label,
                        subtitle = "localhost:${item.port}",
                        leading = GlobeOutline16,
                        trailing = DlRowTrailing.Chevron,
                        onClick = {
                            val proxy = PreviewLocalProxy(
                                exchange = { hostPreviewExchange(host, it) },
                                webSocket = { forward, socket, accept -> hostPreviewWebSocket(host, forward, socket, accept) },
                            )
                            proxy.start()
                            open = OpenPreview("localhost:${item.port}", proxy, proxy.localUrl(item.previewId))
                        },
                    )
                }
                Text(
                    L.previewLimit,
                    color = Dsh.tertiaryText,
                    style = DshType.supporting,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpace.s20, vertical = DshSpace.s12),
                )
            }
        }
    }
}

/** 6.6 预览空态：图标、一句话、下一步。 */
@Composable
internal fun PreviewEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = DshSpace.s32, vertical = DshSpace.s32),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DshSpace.s8),
    ) {
        Icon(GlobeOutline16, contentDescription = null, tint = Dsh.tertiaryText, modifier = Modifier.size(DshIconSize.lg))
        Text(L.previewEmptyTitle, color = Dsh.labelPrimary, style = DshType.bodyStrong, textAlign = TextAlign.Center)
        Text(L.previewEmptyBody, color = Dsh.labelSecondary, style = DshType.supporting, textAlign = TextAlign.Center)
    }
}

private data class OpenPreview(val title: String, val proxy: PreviewLocalProxy, val url: String)

@Composable
private fun PreviewScreen(title: String, url: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val port = remember(url) { Uri.parse(url).port }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val copied = L.previewCopied
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true),
    ) {
        Column(Modifier.fillMaxSize().background(Dsh.bgBase)) {
            // 6.5：关闭 + 等宽地址 + 来源说明；刷新、复制地址在右侧
            DlTopBar(
                title = title,
                subtitle = L.previewVia,
                nav = DlTopBarNav.Close,
                onNav = onClose,
                monoTitle = true,
                showDivider = true,
                actions = listOf(
                    DlTopBarAction(RefreshOutline16, L.previewRefresh, { webView?.reload() }),
                    DlTopBarAction(CopyOutline16, L.previewCopyAddress, {
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("preview", url))
                        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                    }),
                ),
            )
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { createPreviewWebView(it, url, port).also { view -> webView = view } },
                onRelease = { clearPreviewWebView(it, port) },
            )
        }
    }
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
