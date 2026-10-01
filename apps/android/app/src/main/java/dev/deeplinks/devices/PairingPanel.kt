package dev.deeplinks.devices

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.deeplinks.core.DshS
import dev.deeplinks.native.ImageOutline16
import dev.deeplinks.native.ScanOutline16
import dev.deeplinks.native.ui.DshEmptyState
import dev.deeplinks.native.ui.DshListRow
import dev.deeplinks.native.ui.DshListSection
import dev.deeplinks.native.ui.DshSheet
import java.net.URI

/**
 * 设备页的配对入口小件（自 DevicesActivity.kt 拆出）：
 * 空态、扫码 / 相册配对面板、baseUrl 展示名。
 */

@Composable
internal fun EmptyDevicesState(onAdd: () -> Unit) {
    val s = DshS
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // 未配对和空会话同一套留白：标题、说明、文字动作，不挂品牌标志。
        DshEmptyState(
            title = s.noDevicesYet,
            message = s.noDevicesHint,
            actionLabel = s.addDevice,
            onAction = onAdd,
            footnote = s.addDeviceScanOrCode,
        )
    }
}

@Composable
internal fun PairingPanel(
    /** 已有配对设备：配对新电脑会替换它，标题与说明据此改写。 */
    replacing: Boolean,
    onDismiss: () -> Unit,
    onScan: () -> Unit,
    onAlbum: () -> Unit,
) {
    val s = DshS
    DshSheet(
        onDismiss = onDismiss,
        title = if (replacing) s.replaceDevice else s.addDevice,
        subtitle = if (replacing) s.replaceDeviceHint else s.pairChooseHint,
        showClose = true,
        skipPartiallyExpanded = true,
    ) {
        DshListSection {
            DshListRow(
                title = s.methodScan,
                subtitle = s.methodScanDesc,
                icon = ScanOutline16,
                onClick = onScan,
            )
            DshListRow(
                title = s.methodAlbum,
                subtitle = s.methodAlbumDesc,
                icon = ImageOutline16,
                onClick = onAlbum,
            )
        }
    }
}

/** baseUrl → 展示名：去协议、去末尾斜杠。 */
internal fun hostDisplayName(baseUrl: String): String {
    return try {
        val uri = URI(baseUrl.trimEnd('/'))
        (uri.host ?: baseUrl) + (if (uri.port > 0) ":${uri.port}" else "")
    } catch (e: Exception) {
        baseUrl
    }
}
