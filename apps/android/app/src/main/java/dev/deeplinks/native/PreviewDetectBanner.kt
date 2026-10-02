package dev.deeplinks.native

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.core.previewDetected
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 会话里提示已看到的本机端口。没有批准按钮。 */
@Composable
internal fun PreviewDetectBanner(client: MobileApiClient, sessionId: String?, enabled: Boolean) {
    if (!enabled || sessionId.isNullOrBlank()) return
    var ports by remember(sessionId) { mutableStateOf<List<Int>>(emptyList()) }
    LaunchedEffect(sessionId) {
        while (true) {
            ports = runCatching {
                withContext(Dispatchers.IO) { client.listPreviewDetections(sessionId) }
            }.getOrElse { emptyList() }
            delay(4_000)
        }
    }
    if (ports.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpace.s16, vertical = DshSpace.s8),
    ) {
        for (port in ports) {
            Text(
                text = L.previewDetected.format(port),
                color = Dsh.labelSecondary,
                style = DshType.body,
            )
        }
    }
}
