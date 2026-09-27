package dev.deeplinks.devices

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import dev.deeplinks.native.ui.DshBrandMark
import dev.deeplinks.native.ui.DshEmptyState
import dev.deeplinks.native.KeyboardOutline16
import dev.deeplinks.native.ScanOutline16
import dev.deeplinks.core.DshType
import dev.deeplinks.native.AppRoute
import dev.deeplinks.native.MainActivity
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.Host
import dev.deeplinks.core.HostLoadResult
import dev.deeplinks.core.HostStore
import dev.deeplinks.core.EXTRA_AUTH_NOTICE
import dev.deeplinks.core.DshS
import dev.deeplinks.core.L
import dev.deeplinks.core.HostHealth
import dev.deeplinks.core.PairClient
import dev.deeplinks.core.PinnedSsl
import dev.deeplinks.native.MobileApiClient
import dev.deeplinks.native.shouldBlockLocalHostRemoval
import dev.deeplinks.native.shouldDemoteRelayOnAuth
import dev.deeplinks.native.DshRadius
import dev.deeplinks.native.DshConfirmDialog
import dev.deeplinks.native.DshDialogButtons
import dev.deeplinks.native.DshDialogFrame
import dev.deeplinks.native.DshDialogMessage
import dev.deeplinks.native.DshDialogTitle
import dev.deeplinks.native.ui.DshListRow
import dev.deeplinks.native.ui.DshListSection
import dev.deeplinks.native.ui.DshPageScaffold
import dev.deeplinks.native.ui.DshSheet
import dev.deeplinks.native.ui.DshSheetPrimaryButton
import dev.deeplinks.native.ui.DshTextField

import androidx.activity.ComponentActivity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

private sealed class HostOpenResult {
    data class Ok(val host: Host) : HostOpenResult()
    data object Offline : HostOpenResult()
    data class Auth(val error: Throwable) : HostOpenResult()
}

/**
 * 设备管理 Hub —— 对齐全 App 主设计语言（DeepSeek 风生产力工具）：
 * 单列紧凑列表 + 描边设备图标 + 成功色状态点 / 虚线添加卡片 /
 * 底部滑出配对面板（扫码添加 + 手动添加表单）+ 与 Workspace 一致的确认弹窗。
 */
class DevicesActivity : ComponentActivity() {

    private val hostNotice = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // D1：兼容壳，转交给 MainActivity 的 devices 目的地。
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_START_ROUTE, AppRoute.DEVICES)
                .putExtras(intent),
        )
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_AUTH_NOTICE)?.let { hostNotice.value = it }
    }
}

internal enum class DeviceState { CHECKING, ONLINE, OFFLINE, CONNECTING }

internal data class DeviceUi(
    val host: Host,
    val state: DeviceState = DeviceState.CHECKING,
    val latencyMs: Long? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onOpenHost: (Host, (Boolean) -> Unit) -> Unit,
    onScanClick: () -> Unit,
    onManualPair: (name: String, url: String, code: String, fingerprint: String?, onSuccess: (Host) -> Unit, onError: (String) -> Unit) -> Unit,
    hostNotice: String? = null,
    onHostNotice: (String?) -> Unit = {},
    /** 本机存储的设备变了（过期移除 / 解除配对 / 连接偏好），宿主据此刷新当前设备。 */
    onHostChanged: () -> Unit = {},
    /** 在工作区内以底部面板呈现：当前电脑的状态与操作就地完成，不再跳页再点一次电脑。 */
    sheet: Boolean = false,
    onDismissSheet: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val s = DshS
    // 单设备：本机至多配对一台电脑
    var device by remember { mutableStateOf<DeviceUi?>(null) }
    var showPairingPanel by remember { mutableStateOf(false) }
    var unpairTarget by remember { mutableStateOf<Host?>(null) }
    // 离线设备无法走吊销：直接给「仅本机移除」出路，不再卡在吊销失败
    var unpairOffline by remember { mutableStateOf(false) }
    var unpairError by remember { mutableStateOf<String?>(null) }
    var unpairSaving by remember { mutableStateOf(false) }
    var offlineError by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    var healthJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    fun refreshHealth() {
        healthJob?.cancel()
        val current = device ?: return
        device = current.copy(state = DeviceState.CHECKING)
        healthJob = scope.launch {
            val health = withContext(Dispatchers.IO) { PairClient.probe(current.host) }
            if (health is HostHealth.AuthFailed) {
                val demote = shouldDemoteRelayOnAuth(health.error) && current.host.hasRelay
                withContext(Dispatchers.IO) {
                    if (demote) HostStore.demoteRelay(context, current.host) else if (dev.deeplinks.native.shouldDropLocalHostOnOpenAuth(health.error)) HostStore.remove(context, current.host)
                }
                onHostNotice(if (demote) L.relayRouteExpired else L.connectionAuthExpired)
                onHostChanged()
            }
            val stored = withContext(Dispatchers.IO) { HostStore.current(context) }
            device = stored?.let { h ->
                when (health) {
                    is HostHealth.Ok -> DeviceUi(h, DeviceState.ONLINE, health.latencyMs)
                    else -> DeviceUi(h, DeviceState.OFFLINE, null)
                }
            }
            offlineError = if (device?.state == DeviceState.OFFLINE) s.allOffline else null
        }
    }

    fun reload() {
        when (val loaded = HostStore.loadResult(context)) {
            is HostLoadResult.Ok -> {
                device = loaded.hosts.firstOrNull()?.let { DeviceUi(it) }
                offlineError = null
                refreshHealth()
            }
            HostLoadResult.Empty -> {
                device = null
                offlineError = null
            }
            HostLoadResult.Undecryptable -> {
                device = null
                offlineError = s.credentialsUnreadable
            }
        }
        onHostChanged()
        // reload 本身是同步的：有在跑的健康探测就等它结束再收起刷新指示，否则立即收起
        val job = healthJob
        if (job == null || !job.isActive) {
            refreshing = false
        } else {
            job.invokeOnCompletion { refreshing = false }
        }
    }

    // 每次回到前台重读设备并刷新健康状态
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) { reload() }

    LaunchedEffect(lifecycleOwner) {
        while (true) {
            delay(30_000)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                refreshHealth()
            }
        }
    }

    fun togglePreferRelay(host: Host) {
        val updated = host.copy(preferRelay = !host.preferRelay)
        if (HostStore.upsert(context, updated)) {
            Toast.makeText(context, s.preferCloudHint, Toast.LENGTH_SHORT).show()
        }
        reload()
    }

    fun requestUnpair(current: DeviceUi) {
        unpairTarget = current.host
        unpairOffline = current.state != DeviceState.ONLINE
        unpairError = null
        unpairSaving = false
    }

    // ---------- 页面骨架（工作区内为底部面板，否则整页） ----------
    if (sheet) {
        DeviceSheet(
            device = device,
            notice = hostNotice ?: offlineError,
            onDismiss = onDismissSheet,
            onRecheck = { refreshHealth() },
            onTogglePreferRelay = ::togglePreferRelay,
            onRescan = onScanClick,
            onReplace = { showPairingPanel = true },
            onUnpair = ::requestUnpair,
        )
    } else {
        DevicesPage(
            device = device,
            refreshing = refreshing,
            notice = hostNotice ?: offlineError,
            onRefresh = { refreshing = true; reload() },
            onOpen = { current -> onOpenHost(current.host) { ok -> if (!ok) reload() } },
            onTogglePreferRelay = ::togglePreferRelay,
            onUnpair = ::requestUnpair,
            onRecheck = { refreshHealth() },
            onRescan = onScanClick,
            onRescanLater = {
                scope.launch(Dispatchers.IO) {
                    HostStore.clearCloudRescan(context)
                    withContext(Dispatchers.Main) { reload() }
                }
            },
            onAddDevice = { showPairingPanel = true },
        )
    }

    // ---------- 配对面板（底部滑出） ----------
    if (showPairingPanel) {
        PairingPanel(
            replacing = device != null,
            onDismiss = { showPairingPanel = false },
            onScan = {
                showPairingPanel = false
                if (sheet) onDismissSheet()
                onScanClick()
            },
            onManualPair = { name, url, code, fingerprint, onSuccess, onError ->
                onManualPair(name, url, code, fingerprint, { host ->
                    // 新设备落库（替换旧设备）后立即刷新
                    reload()
                    onSuccess(host)
                    if (sheet) onDismissSheet()
                }, onError)
            },
        )
    }

    // ---------- 解除配对确认 ----------
    unpairTarget?.let { target ->
        // 离线：吊销不可能，直接讲清后果并把主操作变成「仅本机移除」
        val offlineOnly = unpairOffline
        fun finishUnpair() {
            HostStore.remove(context, target)
            unpairTarget = null
            unpairOffline = false
            unpairError = null
            reload()
        }
        DshConfirmDialog(
            title = if (offlineOnly) s.removeLocalOnly else s.deleteDevice,
            message = if (offlineOnly) {
                s.deleteDeviceOfflineContent.format(target.name)
            } else {
                s.deleteDeviceContent.format(target.name)
            },
            confirmLabel = if (offlineOnly) s.removeLocalOnly else s.delete,
            danger = true,
            error = unpairError,
            saving = unpairSaving,
            secondaryLabel = if (!offlineOnly && unpairError != null) s.removeLocalOnly else null,
            onSecondary = {
                if (unpairSaving) return@DshConfirmDialog
                finishUnpair()
            },
            onConfirm = {
                if (unpairSaving) return@DshConfirmDialog
                if (offlineOnly) {
                    finishUnpair()
                    return@DshConfirmDialog
                }
                unpairSaving = true
                unpairError = null
                scope.launch(Dispatchers.IO) {
                    var revokeFailure: Throwable? = null
                    if (target.deviceId.isNotBlank()) {
                        try {
                            MobileApiClient(target).revokePairedDevice(deviceId = target.deviceId)
                        } catch (e: Exception) {
                            revokeFailure = e
                        }
                    }
                    withContext(Dispatchers.Main) {
                        unpairSaving = false
                        if (shouldBlockLocalHostRemoval(revokeFailure)) {
                            unpairError = s.revokeFailed.format(revokeFailure?.message ?: s.unknownError)
                            return@withContext
                        }
                        finishUnpair()
                        if (revokeFailure != null) {
                            Toast.makeText(context, s.revokeLocalOnlyHint, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onDismiss = {
                if (!unpairSaving) {
                    unpairTarget = null
                    unpairOffline = false
                    unpairError = null
                }
            },
        )
    }
}

/**
 * 整页形态（批次 3）：统一页面骨架（DshPageScaffold 标题 + 画布）+ 电脑分组；
 * 下拉刷新重读本机设备。不再用 display 大标题与 20dp 分组卡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicesPage(
    device: DeviceUi?,
    refreshing: Boolean,
    notice: String?,
    onRefresh: () -> Unit,
    onOpen: (DeviceUi) -> Unit,
    onTogglePreferRelay: (Host) -> Unit,
    onUnpair: (DeviceUi) -> Unit,
    onRecheck: () -> Unit,
    onRescan: () -> Unit,
    onRescanLater: () -> Unit,
    onAddDevice: () -> Unit,
) {
    val s = DshS
    DshPageScaffold(title = s.pairingManage) {
        val current = device
        if (current == null) {
            Box(Modifier.weight(1f)) {
                EmptyDevicesState(onAdd = onAddDevice)
            }
            notice?.let { msg ->
                Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) { DevicesNotice(msg) }
            }
        } else {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(top = 4.dp, bottom = 32.dp),
                ) {
                    // 页首导语：副标题降为正文说明，与设置页同一字阶
                    Text(
                        s.manageYourLinks,
                        color = Dsh.labelTertiary,
                        style = DshType.body,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                    )
                    DeviceDetailSections(
                        device = current,
                        notice = notice,
                        onOpen = { onOpen(current) },
                        onRecheck = onRecheck,
                        onTogglePreferRelay = onTogglePreferRelay,
                        onRescan = onRescan,
                        onRescanLater = onRescanLater,
                        onReplace = onAddDevice,
                        onUnpair = { onUnpair(current) },
                    )
                }
            }
        }
    }
}

// ---------- 空态 ----------

@Composable
private fun EmptyDevicesState(onAdd: () -> Unit) {
    val s = DshS
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        DshEmptyState(
            title = s.noDevicesYet,
            message = s.noDevicesHint,
            graphic = { DshBrandMark() },
            actionLabel = s.addDevice,
            onAction = onAdd,
            footnote = s.addDeviceScanOrCode,
        )
    }
}

// ---------- 配对面板（底部滑出） ----------

@Composable
private fun PairingPanel(
    /** 已有配对设备：配对新电脑会替换它，标题与说明据此改写。 */
    replacing: Boolean,
    onDismiss: () -> Unit,
    onScan: () -> Unit,
    onManualPair: (name: String, url: String, code: String, fingerprint: String?, onSuccess: (Host) -> Unit, onError: (String) -> Unit) -> Unit,
) {
    val s = DshS
    var mode by remember { mutableStateOf<PairingMode>(PairingMode.CHOOSE) }

    DshSheet(
        onDismiss = onDismiss,
        title = when {
            mode != PairingMode.CHOOSE -> s.methodManual
            replacing -> s.replaceDevice
            else -> s.addDevice
        },
        subtitle = when {
            mode != PairingMode.CHOOSE -> s.manualPairSheetHint
            replacing -> s.replaceDeviceHint
            else -> s.pairChooseHint
        },
        showClose = true,
        skipPartiallyExpanded = true,
    ) {
        when (mode) {
            PairingMode.CHOOSE -> DshListSection {
                DshListRow(
                    title = s.methodScan,
                    subtitle = s.methodScanDesc,
                    icon = ScanOutline16,
                    onClick = onScan,
                )
                DshListRow(
                    title = s.methodManual,
                    subtitle = s.methodManualDesc,
                    icon = KeyboardOutline16,
                    onClick = { mode = PairingMode.MANUAL },
                )
            }

            PairingMode.MANUAL -> ManualPairForm(
                onPair = { name, url, code, fingerprint, onSuccess, onError ->
                    onManualPair(name, url, code, fingerprint, { host ->
                        onSuccess(host)
                        onDismiss()
                    }, onError)
                },
                onBack = { mode = PairingMode.CHOOSE },
            )
        }
    }
}

private enum class PairingMode { CHOOSE, MANUAL }

@Composable
private fun ColumnScope.ManualPairForm(
    onPair: (name: String, url: String, code: String, fingerprint: String?, onSuccess: (Host) -> Unit, onError: (String) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    val s = DshS
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var tofuFingerprint by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit(fingerprint: String?) {
        loading = true
        error = null
        onPair(name.trim(), url.trim(), code.trim(), fingerprint, { _ ->
            loading = false
        }) { msg ->
            loading = false
            error = msg
        }
    }

    tofuFingerprint?.let { fingerprint ->
        CertificateCheckDialog(
            url = url.trim(),
            fingerprint = fingerprint,
            dismissible = !loading,
            onDismiss = { tofuFingerprint = null },
            onConfirm = {
                tofuFingerprint = null
                submit(fingerprint)
            },
        )
    }

    fun connect() {
        val cleanUrl = url.trim()
        val cleanCode = code.trim()
        if (cleanUrl.isEmpty() || cleanCode.isEmpty()) {
            error = s.pairAddressIncomplete
            return
        }
        loading = true
        error = null
        if (!PinnedSsl.shouldPin(cleanUrl)) {
            submit(null)
            return
        }
        scope.launch {
            try {
                val fp = withContext(Dispatchers.IO) { PinnedSsl.peekFingerprint(cleanUrl) }
                loading = false
                tofuFingerprint = fp
            } catch (e: Exception) {
                loading = false
                error = PinnedSsl.unwrap(e).message ?: s.cannotReadCertificate
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DshTextField(
            value = name,
            onValueChange = { name = it },
            label = s.pairFieldName,
            placeholder = s.pairFieldNamePlaceholder,
            contentDescription = "${s.pairFieldName}，${s.pairFieldNamePlaceholder}",
        )
        DshTextField(
            value = url,
            onValueChange = { url = it },
            label = s.pairFieldAddress,
            placeholder = s.pairFieldAddressPlaceholder,
            contentDescription = "${s.pairFieldAddress}，${s.pairFieldAddressPlaceholder}",
        )
        DshTextField(
            value = code,
            onValueChange = { if (it.length <= 8) code = it.trim() },
            label = s.pairCodeLabel,
            placeholder = s.pairFieldCodePlaceholder,
            contentDescription = "${s.pairCodeLabel}，${s.pairFieldCodePlaceholder}",
        )
        error?.let { msg ->
            Text(msg, color = Dsh.error, style = DshType.captionRelaxed, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
    DshSheetPrimaryButton(
        label = if (loading) s.statusConnecting else s.connectDevice,
        enabled = !loading,
        onClick = ::connect,
    )
    TextButton(
        onClick = onBack,
        colors = ButtonDefaults.textButtonColors(contentColor = Dsh.labelSecondary),
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .padding(top = 4.dp),
    ) {
        Text(s.back, style = DshType.labelLarge)
    }
}

/** 首次连接 HTTPS 地址时核对证书指纹（TOFU）：指纹等宽排版放在底色块里，方便逐段对照。 */
@Composable
private fun CertificateCheckDialog(
    url: String,
    fingerprint: String,
    dismissible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val s = DshS
    DshDialogFrame(onDismiss = onDismiss, dismissible = dismissible, maxWidth = 360.dp) { requestDismiss ->
        DshDialogTitle(s.verifyCertificateTitle)
        Text(
            url,
            color = Dsh.labelSecondary,
            style = DshType.captionRelaxed,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            modifier = Modifier.padding(top = 6.dp),
        )
        DshDialogMessage(s.verifyCertificateDesc)
        Text(
            PinnedSsl.formatFingerprint(fingerprint),
            color = Dsh.labelPrimary,
            style = DshType.captionRelaxed,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(DshRadius.container))
                .background(Dsh.bgSubtle)
                .padding(12.dp),
        )
        DshDialogButtons(
            dismissLabel = s.cancel,
            onDismiss = requestDismiss,
            confirmLabel = s.fingerprintMatches,
            onConfirm = onConfirm,
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
