package dev.deeplinks.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.welcomeScan
import dev.deeplinks.core.welcomeStep1
import dev.deeplinks.core.welcomeStep1Hint
import dev.deeplinks.core.welcomeStep2
import dev.deeplinks.core.welcomeStep2Hint
import dev.deeplinks.core.welcomeStep3
import dev.deeplinks.core.welcomeStep3Hint
import dev.deeplinks.core.welcomeSubtitle
import dev.deeplinks.core.welcomeTitle
import dev.deeplinks.native.DshIconSize
import dev.deeplinks.native.DshSpace
import dev.deeplinks.native.ImageOutline16
import dev.deeplinks.native.ScanOutline16
import dev.deeplinks.native.ui.v4.DlAction
import dev.deeplinks.native.ui.v4.DlBottomSheet
import dev.deeplinks.native.ui.v4.DlButton
import dev.deeplinks.native.ui.v4.DlButtonStyle
import dev.deeplinks.native.ui.v4.DlListRow
import java.net.URI

/**
 * 设备页的配对入口小件（自 DevicesActivity.kt 拆出）：
 * 空态、扫码 / 相册配对面板、baseUrl 展示名。
 */

/**
 * 1.2 欢迎 / 未配对：品牌标 + 一句话 + 三步说明，底部「扫码配对」主按钮 +「从相册识别」次按钮。
 */
@Composable
internal fun EmptyDevicesState(onScan: () -> Unit, onAlbum: () -> Unit) {
    val s = DshS
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DshSpace.s24, vertical = DshSpace.s24),
    ) {
        Text(">_", style = DshType.titleLarge.copy(fontFamily = FontFamily.Monospace), color = Dsh.brand400)
        Spacer(Modifier.height(DshSpace.s16))
        Text(s.welcomeTitle, style = DshType.titleLarge, color = Dsh.labelPrimary)
        Spacer(Modifier.height(DshSpace.s8))
        Text(s.welcomeSubtitle, style = DshType.body, color = Dsh.labelSecondary)
        Spacer(Modifier.height(DshSpace.s32))
        WelcomeStep(1, s.welcomeStep1, s.welcomeStep1Hint)
        WelcomeStep(2, s.welcomeStep2, s.welcomeStep2Hint)
        WelcomeStep(3, s.welcomeStep3, s.welcomeStep3Hint)
        Spacer(Modifier.weight(1f).heightIn(min = DshSpace.s32))
        DlButton(DlAction(s.welcomeScan, onScan, DlButtonStyle.Filled), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(DshSpace.s8))
        DlButton(DlAction(s.methodAlbum, onAlbum, DlButtonStyle.Text), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun WelcomeStep(index: Int, title: String, hint: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = DshSpace.s8)) {
        Box(
            modifier = Modifier.size(DshIconSize.lg).background(Dsh.primarySoft, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$index", style = DshType.supporting.copy(fontWeight = FontWeight.SemiBold), color = Dsh.brand400)
        }
        Spacer(Modifier.width(DshSpace.s16))
        Column(Modifier.weight(1f)) {
            Text(title, style = DshType.bodyStrong, color = Dsh.labelPrimary)
            Text(hint, style = DshType.supporting, color = Dsh.labelSecondary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PairingPanel(
    /** 已有配对设备：配对新电脑会替换它，标题与说明据此改写。 */
    replacing: Boolean,
    onDismiss: () -> Unit,
    onScan: () -> Unit,
    onAlbum: () -> Unit,
) {
    val s = DshS
    DlBottomSheet(
        onDismissRequest = onDismiss,
        title = if (replacing) s.replaceDevice else s.addDevice,
        subtitle = if (replacing) s.replaceDeviceHint else s.pairChooseHint,
    ) {
        DlListRow(
            title = s.methodScan,
            subtitle = s.methodScanDesc,
            leading = ScanOutline16,
            onClick = onScan,
        )
        DlListRow(
            title = s.methodAlbum,
            subtitle = s.methodAlbumDesc,
            leading = ImageOutline16,
            onClick = onAlbum,
        )
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
