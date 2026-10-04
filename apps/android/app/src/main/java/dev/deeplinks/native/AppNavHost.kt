package dev.deeplinks.native

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.deeplinks.core.DeviceName
import dev.deeplinks.core.Host
import dev.deeplinks.core.HostStore
import dev.deeplinks.core.L
import dev.deeplinks.core.PendingPairStore
import dev.deeplinks.core.PairFailure
import dev.deeplinks.core.PairFailureCode
import dev.deeplinks.core.PairRecovery
import dev.deeplinks.core.pairRejected
import dev.deeplinks.core.stableIdentity
import dev.deeplinks.devices.DevicesScreen
import dev.deeplinks.devices.PairApprovalPoller
import dev.deeplinks.devices.PairFailedScreen
import dev.deeplinks.devices.PairQrOutcome
import dev.deeplinks.devices.PairWaitingScreen
import dev.deeplinks.devices.pairFromQrText
import dev.deeplinks.native.util.EXTRA_SHARE_IMAGE
import dev.deeplinks.native.util.EXTRA_SHARE_IMAGES
import dev.deeplinks.native.util.EXTRA_SHARE_NOTICE
import dev.deeplinks.native.util.EXTRA_SHARE_SEQ
import dev.deeplinks.native.util.EXTRA_SHARE_TEXT
import dev.deeplinks.native.util.WorkspacePrefs
import dev.deeplinks.native.util.shareImageExtras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 应用级路由（对照 t3code 的静态路由表）。D1 迁移：Devices 与 Workspace 同一 NavHost。 */
object AppRoute {
    const val DEVICES = "devices"
    const val WORKSPACE = "workspace"

    /** 批次 2：设置从独立 Activity 并入应用级导航（SettingsActivity 仅剩兼容壳）。 */
    const val SETTINGS = "settings"

    /** v4 1.5：配对已登记，等电脑批准。 */
    const val PAIR_WAITING = "pairWaiting"

    /** v4 1.6：配对失败（原因 + 重新扫码）。 */
    const val PAIR_FAILED = "pairFailed"

    /** 扫码页把失败原因带回来（[PAIR_FAILED]）。 */
    const val EXTRA_PAIR_MESSAGE = "pairFailMessage"
    const val EXTRA_PAIR_CODE = "pairFailCode"
    const val EXTRA_PAIR_RECOVERY = "pairFailRecovery"
    const val EXTRA_PAIR_SUGGESTION = "pairFailSuggestion"
}

/**
 * 应用级 NavHost（D1/D3/D5）。
 *
 * 把原来 DevicesActivity→WorkspaceActivity 的 Activity 跳转收敛为图表内的
 * navigate；配对与保存逻辑从 DevicesActivity 搬到这里。
 * 分享/通知等外部入口仍由 MainActivity 承接并把 extras 透传给 Workspace 目的地。
 *
 * 单设备：本机只配对一台电脑。[AppRoute.DEVICES] 是配对 / 设备状态页（配对新电脑即替换），
 * 不再有设备列表与工作区内的设备切换。连接等待只在 Workspace 发生一次
 * （[AppRoute.WORKSPACE] 的 bootstrap），离线由 Workspace 顶栏 Banner 承担。
 */
@Composable
internal fun AppNavHost(
    startRoute: String,
    startHost: Host?,
    liveIntent: Intent,
    hostNotice: String?,
    onHostNotice: (String?) -> Unit,
    onScan: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartVoiceInput: ((String) -> Unit, () -> Unit, (String) -> Unit) -> Unit,
    onStopVoiceInput: () -> Unit,
    requestedRoute: String?,
    onRouteHandled: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    val workspacePrefs = remember { WorkspacePrefs(context) }
    /** 本机配对的那台电脑（单设备）；配对 / 解除配对 / 新 Intent 到达后重读。 */
    var currentHost by remember { mutableStateOf(startHost ?: HostStore.current(context)) }
    fun reloadHost() {
        currentHost = HostStore.current(context)
    }
    /** 1.6 失败页要展示的原因：站内配对直接写这里，扫码页经 Intent extras 带回。 */
    var pairFailure by remember { mutableStateOf<PairFailure?>(null) }
    LaunchedEffect(liveIntent) {
        reloadHost()
        liveIntent.getStringExtra(AppRoute.EXTRA_PAIR_MESSAGE)?.let { message ->
            pairFailure = PairFailure(
                runCatching { PairFailureCode.valueOf(liveIntent.getStringExtra(AppRoute.EXTRA_PAIR_CODE).orEmpty()) }.getOrDefault(PairFailureCode.UNKNOWN),
                runCatching { PairRecovery.valueOf(liveIntent.getStringExtra(AppRoute.EXTRA_PAIR_RECOVERY).orEmpty()) }.getOrDefault(PairRecovery.RESCAN),
                message,
                liveIntent.getStringExtra(AppRoute.EXTRA_PAIR_SUGGESTION),
            )
        }
    }

    /** 配对成功或点开设备后进入工作区：工作区已在返回栈里就退回去，不叠第二份。 */
    fun openWorkspace() {
        reloadHost()
        val back = navController.previousBackStackEntry?.destination?.route
        if (back == AppRoute.WORKSPACE) {
            navController.popBackStack()
        } else {
            navController.navigate(AppRoute.WORKSPACE) {
                launchSingleTop = true
                popUpTo(AppRoute.DEVICES) { inclusive = true }
            }
        }
    }

    LaunchedEffect(requestedRoute) {
        if (requestedRoute != null) {
            val pending = PendingPairStore.loadAny(context)
            val route = if (requestedRoute == AppRoute.WORKSPACE && pending != null) {
                if (pending.paused) AppRoute.PAIR_FAILED else AppRoute.PAIR_WAITING
            } else requestedRoute
            navController.navigate(route) { launchSingleTop = true }
            onRouteHandled()
        }
    }

    // 首屏入场：与 SplashActivity 的图标退出动画接力（淡入 + 上移 8dp）。
    // 只在整棵 NavHost 首次组合时播放一次，站内导航不再触发。
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val enterAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(motionDuration(DshDuration.slow), easing = DshEasing.out),
        label = "firstScreenAlpha",
    )
    val enterRise by animateDpAsState(
        targetValue = if (entered) 0.dp else 8.dp,
        animationSpec = tween(motionDuration(DshDuration.slow), easing = DshEasing.out),
        label = "firstScreenRise",
    )

    // 站内转场时长：在可组合作用域捕获（enter/pop 各 lambda 非 @Composable，不能现调 motionDuration）
    val navMotionMs = motionDuration(DshDuration.slow)

    /** 工作区内的「设备与配对」面板；没有已配对电脑时工作区自己会退回设备页。 */
    var deviceSheetOpen by remember { mutableStateOf(false) }

    fun openPairFailed(failure: PairFailure) {
        pairFailure = failure
        deviceSheetOpen = false
        navController.navigate(AppRoute.PAIR_FAILED) { launchSingleTop = true }
    }

    /**
     * M1：二维码文本 → 与扫码完全相同的配对路径（[pairFromQrText]），成功后落库、进 Workspace。
     * 从相册识别与扫码都汇到这里。
     */
    val pairQrText: (String, (Host) -> Unit, (String) -> Unit) -> Unit =
        { text, onSuccess, onError ->
            scope.launch {
                val attempt = withContext(Dispatchers.IO) { PendingPairStore.begin(context) }
                if (attempt == null) {
                    onError(L.credentialsSaveFailedToast)
                    return@launch
                }
                val outcome = withContext(Dispatchers.IO) { pairFromQrText(text, DeviceName.of(context), attempt) }
                if (!PendingPairStore.isCurrent(context, attempt)) return@launch
                when (outcome) {
                    is PairQrOutcome.Failed -> {
                        PendingPairStore.abort(context, attempt)
                        if (outcome.failure.code == PairFailureCode.PAIR_CODE_INVALID || outcome.failure.code == PairFailureCode.QR_REMOTE_INVALID) {
                            onError(outcome.failure.message)
                        } else openPairFailed(outcome.failure)
                    }
                    is PairQrOutcome.Paired -> {
                        val saved = withContext(Dispatchers.IO) { PendingPairStore.complete(context, attempt, outcome.host) }
                        if (saved && PendingPairStore.isCurrent(context, attempt)) {
                            onSuccess(outcome.host)
                            openWorkspace()
                        } else onError(L.credentialsSaveFailedToast)
                    }
                    is PairQrOutcome.Pending -> {
                        val saved = withContext(Dispatchers.IO) { PendingPairStore.save(context, outcome.session) }
                        if (saved && PendingPairStore.isCurrent(context, attempt)) {
                            navController.navigate(AppRoute.PAIR_WAITING) { launchSingleTop = true }
                        } else onError(L.credentialsSaveFailedToast)
                    }
                }
            }
        }

    NavHost(
        navController = navController,
        startDestination = startRoute,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = enterAlpha
                translationY = enterRise.toPx()
            },
        // 转场：新页从右侧整屏不透明滑入覆盖在静止旧页上，返回时当前页向右滑出露出下层；
        // 纯平移、无交叉淡化（淡化曾让下层文字透出，见 docs/ui-parity.md）。
        // predictive pop 同配方跟手滑出，避开 navigation-compose 2.10 默认的 scaleOut 0.7 整页缩小。
        enterTransition = {
            slideInHorizontally(animationSpec = tween(navMotionMs, easing = DshEasing.out)) { it }
        },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = {
            slideOutHorizontally(animationSpec = tween(navMotionMs, easing = DshEasing.out)) { it }
        },
        predictivePopEnterTransition = { EnterTransition.None },
        predictivePopExitTransition = {
            slideOutHorizontally(animationSpec = tween(navMotionMs, easing = DshEasing.out)) { it }
        },
    ) {
        composable(AppRoute.DEVICES) {
            // F04：从设置等有前页的入口进入时显示返回；根页面（首次配对）与凭据失效
            // （popUpTo 清栈后重进）previousBackStackEntry 为空，不提供虚假返回。
            DevicesScreen(
                hostNotice = hostNotice,
                onHostNotice = onHostNotice,
                showsBack = navController.previousBackStackEntry != null,
                onNavigateBack = { navController.popBackStack() },
                onOpenHost = { host, onDone ->
                    // 可达性与会话加载交给 Workspace 的 bootstrap。
                    val saved = HostStore.upsert(context, host)
                    if (!saved && HostStore.isLocked(context)) {
                        HostStore.clearLockAndReplace(context, host)
                        onHostNotice(L.credentialsResetToast)
                    } else if (!saved) {
                        onDone(false)
                        onHostNotice(L.credentialsSaveFailedToast)
                        return@DevicesScreen
                    }
                    onDone(true)
                    openWorkspace()
                },
                onHostChanged = { reloadHost() },
                onScanClick = onScan,
                onPairQrText = pairQrText,
            )
        }

        composable(AppRoute.SETTINGS) {
            // 设置壳层统一（DshPageScaffold），内部二级页保留自己的 NavHost
            SettingsRoute(
                host = currentHost,
                // 与首页同一个探针的结果（单一来源），设置页不自己探
                connectivity = dev.deeplinks.native.util.HostConnectivity.snapshot,
                onBack = { navController.popBackStack() },
                onOpenDevices = {
                    navController.navigate(AppRoute.DEVICES) { launchSingleTop = true }
                },
            )
        }

        composable(AppRoute.PAIR_WAITING) {
            val session = remember { PendingPairStore.loadAny(context) }
            if (session == null || session.paused) {
                LaunchedEffect(Unit) { openPairFailed(if (session == null) PairFailure.unreadable() else PairFailure.restored(session)) }
            } else {
                fun cancelPair() {
                    if (!PendingPairStore.isCurrent(context, session.attemptId)) return
                    if (PendingPairStore.remove(context, session.attemptId, session.host.deviceId)) {
                        reloadHost()
                        navController.navigate(AppRoute.DEVICES) { popUpTo(0) { inclusive = true } }
                    } else openPairFailed(PairFailure.saveFailed())
                }
                BackHandler { cancelPair() }
                PairApprovalPoller(
                    session = session,
                    isCurrent = { PendingPairStore.isCurrent(context, session.attemptId) },
                    onApproved = {
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) { PendingPairStore.promote(context, session) }
                            if (saved && PendingPairStore.isCurrent(context, session.attemptId)) {
                                reloadHost()
                                navController.navigate(AppRoute.WORKSPACE) { popUpTo(0) { inclusive = true } }
                            } else if (PendingPairStore.isCurrent(context, session.attemptId)) {
                                PendingPairStore.pause(context, session, PairFailure.saveFailed())
                                openPairFailed(PairFailure.saveFailed())
                            }
                        }
                    },
                    onRejected = {
                        if (PendingPairStore.isCurrent(context, session.attemptId)) {
                            val removed = PendingPairStore.remove(context, session.attemptId, session.host.deviceId)
                            openPairFailed(if (removed) PairFailure(PairFailureCode.PENDING_REJECTED, PairRecovery.RESCAN, L.pairRejected) else PairFailure.saveFailed())
                        }
                    },
                    onPaused = { failure ->
                        if (PendingPairStore.isCurrent(context, session.attemptId)) {
                            val saved = PendingPairStore.pause(context, session, failure)
                            openPairFailed(if (saved) failure else PairFailure.saveFailed())
                        }
                    },
                )
                PairWaitingScreen(
                    session = session,
                    deviceName = remember { DeviceName.of(context) },
                    onCancel = ::cancelPair,
                )
            }
        }

        composable(AppRoute.PAIR_FAILED) {
            val pending = PendingPairStore.loadAny(context)
            fun leavePairFailure() {
                pairFailure = null
                navController.navigate(if (currentHost == null) AppRoute.DEVICES else AppRoute.WORKSPACE) {
                    popUpTo(0) { inclusive = true }
                }
            }
            BackHandler { leavePairFailure() }
            val failure = pairFailure ?: if (PendingPairStore.isUnreadable(context)) {
                PairFailure.unreadable()
            } else if (pending != null) {
                PairFailure.restored(pending)
            } else PairFailure.network()
            PairFailedScreen(
                failure = failure,
                onRescan = onScan,
                onRetry = {
                    val pending = PendingPairStore.loadAny(context)
                    if (pending == null) {
                        onScan()
                    } else if (PendingPairStore.resume(context, pending)) {
                        pairFailure = null
                        navController.navigate(AppRoute.PAIR_WAITING) {
                            popUpTo(AppRoute.PAIR_WAITING) { inclusive = true }
                            launchSingleTop = true
                        }
                    } else pairFailure = PairFailure.saveFailed()
                },
                onBack = ::leavePairFailure,
            )
        }

        composable(AppRoute.WORKSPACE) {
            val host = currentHost
            if (host == null) {
                LaunchedEffect(Unit) {
                    navController.navigate(AppRoute.DEVICES) { popUpTo(0) { inclusive = true } }
                }
            } else {
                // host 切换必须重建整棵状态树；intent sessionId 变化由内部 LaunchedEffect 接入
                val hostIdentity = host.stableIdentity()
                // 外部 Intent 的 sessionId 优先；没有就恢复该设备上次打开的会话（仍由
                // Workspace 在拿到会话列表后校验可见性，失效则回退最新可见会话）。
                val deepLinkSessionId = liveIntent.getStringExtra("sessionId")
                val restoreSessionId = if (deepLinkSessionId.isNullOrBlank()) {
                    workspacePrefs.lastSessionId(hostIdentity)
                } else {
                    null
                }
                key(host.slotKey) {
                    WorkspaceScreen(
                        host = host,
                        initialSessionId = deepLinkSessionId,
                        restoreSessionId = restoreSessionId,
                        initialShareText = liveIntent.getStringExtra(EXTRA_SHARE_TEXT),
                        initialShareImages = shareImageExtras(
                            liveIntent.getStringArrayListExtra(EXTRA_SHARE_IMAGES),
                            liveIntent.getStringExtra(EXTRA_SHARE_IMAGE),
                        ),
                        initialShareSeq = liveIntent.getLongExtra(EXTRA_SHARE_SEQ, 0L),
                        // 通知动作（方案 8）：看改动 / 回复
                        initialIntentAction = when {
                            liveIntent.getBooleanExtra(dev.deeplinks.core.DshNotifier.INTENT_ACTION_CHANGES, false) ->
                                dev.deeplinks.core.DshNotifier.INTENT_ACTION_CHANGES
                            liveIntent.getBooleanExtra(dev.deeplinks.core.DshNotifier.INTENT_ACTION_REPLY, false) ->
                                dev.deeplinks.core.DshNotifier.INTENT_ACTION_REPLY
                            else -> null
                        },
                        initialIntentActionRequestId = liveIntent.getLongExtra(
                            dev.deeplinks.core.DshNotifier.EXTRA_ACTION_REQUEST_ID,
                            0L,
                        ),
                        initialShareNotice = liveIntent.getStringExtra(EXTRA_SHARE_NOTICE),
                        onOpenDevice = { notice ->
                            if (notice.isNullOrBlank()) {
                                deviceSheetOpen = true
                            } else {
                                // 凭据失效：配对已不可用，整页设备页负责重新配对
                                onHostNotice(notice)
                                navController.navigate(AppRoute.DEVICES) {
                                    launchSingleTop = true
                                    popUpTo(AppRoute.DEVICES) { inclusive = true }
                                }
                            }
                        },
                        onOpenSettings = { onOpenSettings() },
                        onStartVoiceInput = onStartVoiceInput,
                        onStopVoiceInput = onStopVoiceInput,
                    )
                }
                if (deviceSheetOpen) {
                    DevicesScreen(
                        hostNotice = hostNotice,
                        onHostNotice = onHostNotice,
                        onOpenHost = { _, onDone -> onDone(true) },
                        onHostChanged = { reloadHost() },
                        onScanClick = onScan,
                        onPairQrText = pairQrText,
                        sheet = true,
                        onDismissSheet = { deviceSheetOpen = false },
                    )
                }
            }
        }
    }
}
