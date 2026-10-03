package dev.deeplinks.devices

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import dev.deeplinks.native.DshSpace
import dev.deeplinks.native.AppRoute
import dev.deeplinks.native.MainActivity
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.Host
import dev.deeplinks.core.HostLoadResult
import dev.deeplinks.core.HostStore
import dev.deeplinks.core.EXTRA_AUTH_NOTICE
import dev.deeplinks.core.DshS
import dev.deeplinks.core.L
import dev.deeplinks.core.HostHealth
import dev.deeplinks.core.PairClient
import dev.deeplinks.native.MobileApiClient
import dev.deeplinks.native.shouldBlockLocalHostRemoval
import dev.deeplinks.native.DshConfirmDialog
import dev.deeplinks.native.DshRenameDialog
import dev.deeplinks.native.util.WorkspacePrefs
import dev.deeplinks.native.ConnectionDiagnosticsPage
import dev.deeplinks.native.ui.DshPageNavigation
import dev.deeplinks.native.ui.DshPageScaffold

import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class HostOpenResult {
    data class Ok(val host: Host) : HostOpenResult()
    data object Offline : HostOpenResult()
    data class Auth(val error: Throwable) : HostOpenResult()
}

/**
 * 设备管理 Hub —— 对齐全 App 主设计语言（DeepSeek 风生产力工具）：
 * 单列紧凑列表 + 描边设备图标 + 成功色状态点 / 虚线添加卡片 /
 * 底部滑出配对面板（扫描二维码 / 从相册识别）+ 与 Workspace 一致的确认弹窗。
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
    /** 最近一次成功的请求走的是远程（中继）。 */
    val viaRemote: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onOpenHost: (Host, (Boolean) -> Unit) -> Unit,
    onScanClick: () -> Unit,
    /** M1：二维码文本走与扫码完全相同的配对路径（远程首配 / pending 批准 / 错误提示一致）。 */
    onPairQrText: (text: String, onSuccess: (Host) -> Unit, onError: (String) -> Unit) -> Unit,
    hostNotice: String? = null,
    onHostNotice: (String?) -> Unit = {},
    /** 本机存储的设备变了（过期移除 / 解除配对 / 连接偏好），宿主据此刷新当前设备。 */
    onHostChanged: () -> Unit = {},
    /** 在工作区内以底部面板呈现：当前电脑的状态与操作就地完成，不再跳页再点一次电脑。 */
    sheet: Boolean = false,
    onDismissSheet: () -> Unit = {},
    alias: String = "",
    onAliasChanged: () -> Unit = {},
    /** 整页形态的返回入口由宿主按导航栈决定：根页面（首次配对 / 凭据失效）不提供虚假返回。 */
    showsBack: Boolean = false,
    onNavigateBack: () -> Unit = {},
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

    val prefs = remember { dev.deeplinks.native.util.WorkspacePrefs(context) }
    var alias by remember { mutableStateOf(prefs.hostAlias.ifBlank { device?.host?.name.orEmpty() }) }
    var renameOpen by remember { mutableStateOf(false) }

    fun applyRename(newAlias: String) {
        prefs.hostAlias = newAlias.trim()
        alias = newAlias.trim()
        onAliasChanged()
    }

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
                withContext(Dispatchers.IO) {
                    if (dev.deeplinks.native.shouldDropLocalHostOnOpenAuth(health.error)) HostStore.remove(context, current.host)
                }
                onHostNotice(L.connectionAuthExpired)
                onHostChanged()
            }
            val stored = withContext(Dispatchers.IO) { HostStore.current(context) }
            device = stored?.let { h ->
                when (health) {
                    is HostHealth.Ok -> DeviceUi(h, DeviceState.ONLINE, health.latencyMs, dev.deeplinks.core.HostHttp.isViaRemote(h))
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

    // 每次回到前台重读设备并刷新健康状态 + 首次装载 + 30 秒健康轮询
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DevicesForegroundRefresh(lifecycleOwner, reload = ::reload, refreshHealth = ::refreshHealth)

    fun requestUnpair(current: DeviceUi) {
        unpairTarget = current.host
        unpairOffline = current.state != DeviceState.ONLINE
        unpairError = null
        unpairSaving = false
    }

    // ---------- 配对面板（底部滑出） ----------
    // M1：从相册识别——Photo Picker 选图，IO 线程解码二维码，再走共用配对流程。
    val albumPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) { QrImageDecoder.decodeUri(context, uri) }
            if (text.isNullOrBlank()) {
                onHostNotice(L.qrImageNotFound)
                return@launch
            }
            onPairQrText(text, {
                reload()
                if (sheet) onDismissSheet()
            }, { message -> onHostNotice(message) })
        }
    }
    val launchScan = { if (sheet) onDismissSheet(); onScanClick() }
    val launchAlbum = { albumPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    // ---------- 页面骨架（工作区内为底部面板，否则整页） ----------
    var showDiagnostics by remember { mutableStateOf(false) }
    val currentDevice = device
    if (showDiagnostics && currentDevice != null) {
        ConnectionDiagnosticsPage(
            host = currentDevice.host,
            onBack = { showDiagnostics = false },
        )
    } else if (sheet) {
        DeviceSheet(
            device = device,
            notice = hostNotice ?: offlineError,
            onDismiss = onDismissSheet,
            onRecheck = { refreshHealth() },
            onReplace = { showPairingPanel = true },
            onUnpair = ::requestUnpair,
            alias = alias,
            onRename = onAliasChanged,
            onOpenDiagnostics = { showDiagnostics = true },
        )
    } else {
        DevicesPage(
            device = device,
            refreshing = refreshing,
            notice = hostNotice ?: offlineError,
            onRefresh = { refreshing = true; reload() },
            onOpen = { current -> onOpenHost(current.host) { ok -> if (!ok) reload() } },
            onUnpair = ::requestUnpair,
            onRecheck = { refreshHealth() },
            onAddDevice = { showPairingPanel = true },
            onScan = launchScan,
            onAlbum = launchAlbum,
            alias = alias,
            onRename = { renameOpen = true },
            showsBack = showsBack,
            onNavigateBack = onNavigateBack,
            onOpenDiagnostics = { showDiagnostics = true },
        )
    }

    if (showPairingPanel) {
        PairingPanel(
            replacing = device != null,
            onDismiss = { showPairingPanel = false },
            onScan = { showPairingPanel = false; launchScan() },
            onAlbum = { showPairingPanel = false; launchAlbum() },
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

    if (renameOpen) {
        DshRenameDialog(
            currentName = alias.ifBlank { device?.host?.name.orEmpty() },
            title = s.rename,
            message = s.renameComputerDesc,
            onDismiss = { renameOpen = false },
            onSave = { newName -> applyRename(newName); renameOpen = false },
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
    onUnpair: (DeviceUi) -> Unit,
    onRecheck: () -> Unit,
    onAddDevice: () -> Unit,
    onScan: () -> Unit = onAddDevice,
    onAlbum: () -> Unit = onAddDevice,
    alias: String = "",
    onRename: () -> Unit = {},
    showsBack: Boolean = false,
    onNavigateBack: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
) {
    val s = DshS
    DshPageScaffold(
        title = s.pairingManage,
        navigation = if (showsBack) DshPageNavigation.Back else DshPageNavigation.None,
        onNavigateBack = onNavigateBack,
    ) {
        val current = device
        if (current == null) {
            Box(Modifier.weight(1f)) {
                EmptyDevicesState(onScan = onScan, onAlbum = onAlbum)
            }
            notice?.let { msg ->
                Box(Modifier.padding(horizontal = DshSpace.s16).padding(bottom = DshSpace.s16)) { DevicesNotice(msg) }
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
                        // 顶部避让悬浮 chrome（L9）：骨架实测高度经 LocalDshPageTopInset 注入
                        .padding(top = dev.deeplinks.native.ui.LocalDshPageTopInset.current)
                        .padding(horizontal = DshSpace.s16)
                        .padding(bottom = DshSpace.s32),
                ) {
                    // 页首导语：副标题降为正文说明，与设置页同一字阶
                    Text(
                        s.manageYourLinks,
                        color = Dsh.labelTertiary,
                        style = DshType.body,
                        modifier = Modifier.padding(start = DshSpace.s4, top = DshSpace.s4, bottom = DshSpace.s4),
                    )
                    DeviceDetailSections(
                        device = current,
                        notice = notice,
                        onOpen = { onOpen(current) },
                        onRecheck = onRecheck,
                        onReplace = onAddDevice,
                        onUnpair = { onUnpair(current) },
                        alias = alias,
                        onRename = onRename,
                        onOpenDiagnostics = onOpenDiagnostics,
                    )
                }
            }
        }
    }
}

// ---------- 空态 ----------

// EmptyDevicesState / PairingPanel / hostDisplayName 已拆至 PairingPanel.kt

/**
 * 设备页的前台刷新节奏：回到前台重读设备、组合时首次装载、前台期间每 30 秒探测健康。
 */
@Composable
private fun DevicesForegroundRefresh(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    reload: () -> Unit,
    refreshHealth: () -> Unit,
) {
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
}
