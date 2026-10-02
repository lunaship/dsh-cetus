package dev.deeplinks.native

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 会话里已看到的本机端口（每 4 秒问一次插件）。结果交给状态槽按优先级显示，没有批准按钮。 */
@Composable
internal fun rememberPreviewDetections(client: MobileApiClient, sessionId: String?, enabled: Boolean): List<Int> {
    var ports by remember(sessionId) { mutableStateOf<List<Int>>(emptyList()) }
    if (!enabled || sessionId.isNullOrBlank()) return emptyList()
    LaunchedEffect(sessionId) {
        while (true) {
            ports = runCatching {
                withContext(Dispatchers.IO) { client.listPreviewDetections(sessionId) }
            }.getOrElse { emptyList() }
            delay(4_000)
        }
    }
    return ports
}
