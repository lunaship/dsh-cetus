package dev.deeplinks.native


import dev.deeplinks.native.DshIconSize
import dev.deeplinks.core.persist
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.Host
import dev.deeplinks.core.DshS
import dev.deeplinks.core.backgroundTakeover
import dev.deeplinks.core.LocaleManager
import dev.deeplinks.core.FontScaleManager
import dev.deeplinks.core.ThemeManager
import dev.deeplinks.core.UiFontManager
import dev.deeplinks.native.MobileSession
import dev.deeplinks.native.AppSettings
import dev.deeplinks.native.MobileApiClient
import dev.deeplinks.native.ui.DshListActionRow
import dev.deeplinks.native.ui.DshListCaption
import dev.deeplinks.native.ui.DshListNote
import dev.deeplinks.native.ui.DshListRetry
import dev.deeplinks.native.ui.DshListRow
import dev.deeplinks.native.ui.HostStatusDot
import dev.deeplinks.native.ui.DshListSection
import dev.deeplinks.native.ui.DshListTrailing
import dev.deeplinks.native.ui.DshPageNavigation
import dev.deeplinks.native.ui.DshPageScaffold
import dev.deeplinks.native.ui.DshSelectRow
import dev.deeplinks.native.ui.DshSwitchRow
import dev.deeplinks.native.util.SessionSnapshot
import dev.deeplinks.native.util.WorkspacePrefs
import dev.deeplinks.native.util.SessionListKind
import dev.deeplinks.native.util.catalogKind
import dev.deeplinks.core.AppSettingsStore
import dev.deeplinks.devices.DevicesActivity
import dev.deeplinks.devices.hostDisplayName
import dev.deeplinks.BuildConfig

import kotlin.text.Charsets
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置路由（批次 2：设置并入应用级导航）。
 *
 * UI 与 Activity 解耦：SettingsRoute 是纯 Composable，由 AppNavHost 的
 * AppRoute.SETTINGS 组合；SettingsActivity 只保留为外部入口兼容壳。
 * 壳层统一走 DshPageScaffold（标题 / 返回 / 页面背景），内部二级页仍用
 * 自己的 NavHost，避免一次改动同时重写全部设置状态。
 */

internal enum class SettingsDest {
    HOME,
    GENERAL,
    APPEARANCE,
    CONVERSATION,
    MODELS,
    SESSIONS,
    ABOUT,
}

/**
 * 设置二级页的画布：不透明画布底 + 独立滚动 + Compact 16dp 边距。
 * 批次 6 起不再走 DshGroupedPage 兼容包装——页面壳层由外层 [DshPageScaffold] 负责。
 */
@Composable
internal fun SettingsPageCanvas(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Dsh.bgBase)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DshSpace.s16)
            .padding(top = DshSpace.s4, bottom = DshSpace.s32),
        content = content,
    )
}

@Composable
private fun SettingsDest.label(): String {
    val s = DshS
    return when (this) {
        SettingsDest.HOME -> s.settingsTitle
        SettingsDest.GENERAL -> s.tabGeneral
        SettingsDest.APPEARANCE -> s.sectionAppearance
        SettingsDest.CONVERSATION -> s.settingsConversation
        SettingsDest.MODELS -> s.tabModels
        SettingsDest.SESSIONS -> s.tabSessions
        SettingsDest.ABOUT -> s.tabAbout
    }
}

@Composable
internal fun SettingsRoute(
    host: Host?,
    onBack: () -> Unit,
    onOpenDevices: () -> Unit,
    /** 连通性快照（与首页同一个探针的单一来源，见 util.HostConnectivity）。 */
    connectivity: dev.deeplinks.native.util.HostConnectivitySnapshot? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val s = DshS
    val scope = rememberCoroutineScope()
    val initialSettings = remember(host) { AppSettingsStore.cached(context, host) }
    val settingsViewModel: SettingsViewModel = viewModel(
        key = "settings:${host?.slotKey ?: "offline"}",
        factory = SettingsViewModel.factory(host, initialSettings),
    )
    val settingsClient = settingsViewModel.client
    val navController = rememberNavController()
    val settingsEntry by navController.currentBackStackEntryAsState()
    val dest = settingsEntry?.destination?.route
        ?.let { route -> SettingsDest.entries.firstOrNull { it.name == route } }
        ?: SettingsDest.HOME
    // NavHost 自带返回栈：二级页 pop 回首页；首页时系统返回交回调用方（onBack）
    var llmGroups by settingsViewModel.llmGroups
    var llmLoading by settingsViewModel.llmLoading
    var llmError by settingsViewModel.llmError
    var llmReloadEpoch by remember { mutableStateOf(0) }
    var showFullAccessConfirm by remember { mutableStateOf(false) }
    var legalDoc by remember { mutableStateOf<Pair<String, String>?>(null) }

    // WI-004：服务端设置为唯一真实源；加载失败回退本地缓存（离线可用）
    var appSettings by settingsViewModel.appSettings
    var namespaceRevisions by settingsViewModel.namespaceRevisions
    var savingNs by settingsViewModel.savingNamespace
    var saveErrors by settingsViewModel.saveErrors

    LaunchedEffect(host) {
        if (host == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val view = settingsClient?.getSettings() ?: return@withContext
                withContext(Dispatchers.Main) {
                    val loaded = AppSettings.fromServer(view.namespaces)
                    loaded.persist(context, host, updateLocalExperience = true)
                    appSettings = loaded
                    LocaleManager.setLanguage(context, loaded.language)
                    namespaceRevisions = view.namespaces.associate { it.ns to it.revision }
                    if (loaded.theme in setOf("light", "dark", "system")) {
                        ThemeManager.setThemeMode(context, loaded.theme)
                    }
                }
            } catch (e: Exception) {
                // 离线：回退本地缓存，设置项仍可展示（保存时会提示错误）
            }
        }
    }

    /** 写服务端并校验读回；失败留在当前页面并显示行内错误与重试入口。 */
    fun saveNamespace(ns: String, patch: org.json.JSONObject, onSuccess: () -> Unit = {}) {
        val h = host
        if (h == null) {
            saveErrors = saveErrors + (ns to s.notConnectedCannotSave)
            return
        }
        if (savingNs != null) return
        savingNs = ns
        scope.launch(Dispatchers.IO) {
            try {
                val updated = AppSettingsStore.save(h, context, ns, patch, namespaceRevisions[ns])
                withContext(Dispatchers.Main) {
                    appSettings = AppSettingsStore.cached(context, h)
                    namespaceRevisions = namespaceRevisions + (ns to updated.revision)
                    saveErrors = saveErrors - ns
                    onSuccess()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    saveErrors = saveErrors + (ns to (e.message?.let { friendlyNetworkError(e) } ?: s.saveFailed))
                }
            } finally {
                withContext(Dispatchers.Main) { savingNs = null }
            }
        }
    }

    // 模型目录（llm.models）—— 模型 Tab 浏览用；失败不得伪装成加载中
    LaunchedEffect(dest, host, llmReloadEpoch) {
        if (dest != SettingsDest.MODELS) return@LaunchedEffect
        if (host == null) {
            llmLoading = false
            llmError = s.notConnectedCannotSave
            return@LaunchedEffect
        }
        llmLoading = true
        llmError = null
        withContext(Dispatchers.IO) {
            try {
                val groups = settingsClient?.getLlmModels().orEmpty()
                withContext(Dispatchers.Main) {
                    llmGroups = groups
                    llmError = null
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    llmError = e.message?.takeIf { it.isNotBlank() } ?: s.loadModelListFailed
                }
            } finally {
                withContext(Dispatchers.Main) { llmLoading = false }
            }
        }
    }

    // 统一页面壳层：标题 / 返回热区 / 页面背景（docs/visual-rules.md 第一节）
    DshPageScaffold(
        title = dest.label(),
        navigation = DshPageNavigation.Back,
        onNavigateBack = {
            if (dest == SettingsDest.HOME) onBack() else navController.popBackStack()
        },
    ) {
        // 站内转场：transition lambda 非 @Composable，时长在作用域预先捕获
        val navMotionMs = motionDuration(DshDuration.slow)
        NavHost(
            navController = navController,
            startDestination = SettingsDest.HOME.name,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            enterTransition = { slideInHorizontally(animationSpec = tween(navMotionMs, easing = DshEasing.out)) { it } },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { slideOutHorizontally(animationSpec = tween(navMotionMs, easing = DshEasing.out)) { it } },
            predictivePopEnterTransition = { EnterTransition.None },
            predictivePopExitTransition = { slideOutHorizontally(animationSpec = tween(navMotionMs, easing = DshEasing.out)) { it } },
        ) {
            // 每页自带不透明画布（SettingsPageCanvas）：pop 转场时下层页面的文字不会透上来
            composable(SettingsDest.HOME.name) {
                SettingsPageCanvas {
                    SettingsHome(
                        appSettings = appSettings,
                        onOpen = { navController.navigate(it.name) },
                        host = host,
                        onOpenDevices = onOpenDevices,
                        connectivity = connectivity,
                        savingNs = savingNs,
                        saveErrors = saveErrors,
                        onSave = { ns, patch, onSuccess -> saveNamespace(ns, patch, onSuccess) },
                    )
                }
            }

            composable(SettingsDest.GENERAL.name) {
                SettingsPageCanvas {
                    LanguageSettings(
                        appSettings = appSettings,
                        savingNs = savingNs,
                        saveErrors = saveErrors,
                        onSave = { ns, patch, onSuccess -> saveNamespace(ns, patch, onSuccess) },
                    )
                }
            }

            composable(SettingsDest.APPEARANCE.name) {
                SettingsPageCanvas {
                    AppearanceSettings(
                        savingNs = savingNs,
                        saveErrors = saveErrors,
                        onSave = { ns, patch, onSuccess -> saveNamespace(ns, patch, onSuccess) },
                    )
                }
            }

            composable(SettingsDest.CONVERSATION.name) {
                SettingsPageCanvas {
                    ConversationSettings(
                        appSettings = appSettings,
                        savingNs = savingNs,
                        saveErrors = saveErrors,
                        onShowFullAccessConfirm = { showFullAccessConfirm = true },
                        onSave = { ns, patch, onSuccess -> saveNamespace(ns, patch, onSuccess) },
                    )
                }
            }

            composable(SettingsDest.MODELS.name) {
                SettingsPageCanvas {
                    ModelsSettingsPage(
                        host = host,
                        viewModel = settingsViewModel,
                        appSettings = appSettings,
                        llmGroups = llmGroups,
                        llmLoading = llmLoading,
                        llmError = llmError,
                        onReloadCatalog = { llmReloadEpoch += 1 },
                        savingNs = savingNs,
                        saveErrors = saveErrors,
                        onSave = { ns, patch, onSuccess -> saveNamespace(ns, patch, onSuccess) },
                        onOpenDevices = onOpenDevices,
                    )
                }
            }

            composable(SettingsDest.SESSIONS.name) {
                SettingsPageCanvas {
                    SessionsSettings(host = host)
                }
            }

            composable(SettingsDest.ABOUT.name) {
                SettingsPageCanvas {
                    AboutSettings(onOpenLegal = { file, title -> legalDoc = file to title })
                }
            }
        }
    }

    if (showFullAccessConfirm) {
        DshConfirmDialog(
            title = s.confirmFullAccessTitle,
            message = s.confirmFullAccessMessage,
            confirmLabel = s.enableFullAccess,
            danger = true,
            onDismiss = { showFullAccessConfirm = false },
            onConfirm = {
                showFullAccessConfirm = false
                // WI-004：真实写入服务端 permission.defaultPreset（新会话由 DSH 服务端应用）
                saveNamespace("permission", org.json.JSONObject().put("defaultPreset", "danger-full-access"))
            },
        )
    }

    legalDoc?.let { (fileName, title) ->
        LegalDocDialog(
            fileName = fileName,
            title = title,
            onDismiss = { legalDoc = null },
        )
    }
}

@Composable
private fun LegalDocDialog(fileName: String, title: String, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val s = DshS
    val body = remember(fileName) {
        runCatching {
            context.assets.open("legal/$fileName").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse { s.legalLoadFailed.replace("%s", fileName) }
    }
    DshDialogFrame(
        onDismiss = onDismiss,
        maxWidth = 440.dp,
        cardModifier = Modifier.fillMaxHeight(0.8f),
    ) { requestDismiss ->
        DshDialogTitle(title)
        Text(
            text = body,
            color = Dsh.labelSecondary,
            style = DshType.microRelaxed,
            modifier = Modifier
                .padding(top = DshSpace.s12)
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        )
        DshDialogButtons(dismissLabel = s.close, onDismiss = requestDismiss)
    }
}

// ---------- 设置首页（分类导航） ----------

@Composable
internal fun SettingsHome(
    appSettings: AppSettings,
    onOpen: (SettingsDest) -> Unit,
    host: Host? = null,
    onOpenDevices: () -> Unit = {},
    /** 连通性快照（由上层注入）；null = 还没探过，不写状态。 */
    connectivity: dev.deeplinks.native.util.HostConnectivitySnapshot? = null,
    /** 「执行中发消息」写回服务端设置用的通路（与二级页同一套 savingNs/saveErrors 表现）。 */
    savingNs: String? = null,
    saveErrors: Map<String, String> = emptyMap(),
    onSave: (String, org.json.JSONObject, () -> Unit) -> Unit = { _, _, _ -> },
) {
    val s = DshS
    // 通知开关存本机（方案 7.3）：与 LastOnlineStore 同一层，阶段 8 的 DshNotifier 从这里读
    val notifyContext = androidx.compose.ui.platform.LocalContext.current
    val notifyPrefs = remember { dev.deeplinks.native.util.WorkspacePrefs(notifyContext) }
    var notifyApproval by remember { mutableStateOf(notifyPrefs.notifyOnApproval) }
    var notifyDone by remember { mutableStateOf(notifyPrefs.notifyOnDone) }
    var backgroundTakeover by remember { mutableStateOf(notifyPrefs.backgroundTakeover) }
    var quickApprove by remember { mutableStateOf(notifyPrefs.allowApproveFromNotification) }
    var autoLoadRemoteImages by remember { mutableStateOf(notifyPrefs.autoLoadRemoteImages) }
    val alias = notifyPrefs.hostAlias
    val themeLabel = when (ThemeManager.currentThemeMode) {
        "light" -> s.themeLight
        "dark" -> s.themeDark
        else -> s.themeSystem
    }

    // 设置页只用一种容器：扁平行 + 发丝分隔，已配对电脑也不另铺灰卡
    DshListSection(header = s.sectionComputer) {
        if (host != null) {
            val address = hostDisplayName(host.baseUrl)
            // E4：状态点改用共享组件 HostStatusDot（此前的「●」是文字 glyph，颜色跟随
            // value 文字色呈深灰，与首页/设备页的绿色点不一致）。点 + 文字放进 trailingContent，
            // 保留 chevron。
            val hostStatus = dev.deeplinks.native.util.hostStatusText(
                online = connectivity?.online,
                viaRemote = connectivity?.viaRemote == true,
                onlineText = s.statusOnline,
                offlineText = s.statusOffline,
                viaRemoteText = s.viaRemoteShort,
            )
            DshListRow(
                title = alias.ifBlank { host.name.ifBlank { address } },
                subtitle = address,
                subtitleMono = true,
                icon = LaptopOutline16,
                onClick = onOpenDevices,
                trailingContent = if (hostStatus != null) {
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HostStatusDot(connectivity?.online)
                            Spacer(Modifier.width(DshSpace.s6))
                            Text(
                                hostStatus,
                                color = Dsh.labelSecondary,
                                style = DshType.supporting,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    null
                },
            )
        } else {
            DshListRow(
                title = s.noDevicesYet,
                subtitle = s.addDeviceScanOrCode,
                icon = ScanOutline16,
                onClick = onOpenDevices,
            )
        }
    }
    if (host != null) {
        DshListSection(header = s.sectionAgent) {
            DshListRow(
                title = s.agentPermission,
                icon = ShieldOutline16,
                value = dev.deeplinks.native.util.permissionPresetLabel(appSettings.permissionPreset, s),
                onClick = { onOpen(SettingsDest.CONVERSATION) },
            )
            DshListRow(
                title = s.modelsAndBalance,
                icon = SparkleOutline16,
                onClick = { onOpen(SettingsDest.MODELS) },
            )
        }
    }
    DshListSection(header = s.sectionGeneral) {
        DshListRow(
            title = s.language,
            icon = TranslateOutline16,
            value = if (appSettings.language == "zh") s.langZh else s.langEn,
            onClick = { onOpen(SettingsDest.GENERAL) },
        )
        DshListRow(
            title = s.sectionAppearance,
            icon = PaletteOutline16,
            value = themeLabel,
            onClick = { onOpen(SettingsDest.APPEARANCE) },
        )
        val busyEnterId = canonicalBusyEnter(appSettings.busyEnter)
        DshSelectRow(
            title = s.busyEnter,
            icon = SendOutline16,
            value = when (busyEnterId) {
                "send" -> s.busySend
                "steer" -> s.busySteer
                else -> s.busyQueue
            },
            options = listOf(s.busySend to "send", s.busySteer to "steer", s.busyQueue to "queue"),
            selectedId = busyEnterId,
            saving = savingNs == "ui-conversation",
            error = saveErrors["ui-conversation"],
            onRetry = { onSave("ui-conversation", org.json.JSONObject().put("busyEnter", busyEnterId), {}) },
            onSelect = { _, id -> onSave("ui-conversation", org.json.JSONObject().put("busyEnter", id), {}) },
            description = s.busyEnterDesc,
        )
    }
    DshListSection(header = s.sectionNotifications, footer = s.notifyExplain) {
        DshSwitchRow(
            title = s.notifyOnApproval,
            checked = notifyApproval,
            onCheckedChange = { notifyApproval = it; notifyPrefs.notifyOnApproval = it },
        )
        DshSwitchRow(
            title = s.notifyOnDone,
            checked = notifyDone,
            onCheckedChange = { notifyDone = it; notifyPrefs.notifyOnDone = it },
        )
        // 默认关闭：打开后前台服务在离开 App 时保持订阅，插件就把审批交给手机（见 WorkspacePrefs）。
        // 关闭时立刻停服务并收回它发出的可操作审批通知，之后的审批回到电脑网页。
        DshSwitchRow(
            title = s.backgroundTakeover,
            checked = backgroundTakeover,
            onCheckedChange = {
                backgroundTakeover = it
                notifyPrefs.backgroundTakeover = it
                if (!it) SessionBackgroundMonitorService.stopAll(notifyContext)
            },
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            DshSwitchRow(
                title = s.allowApproveFromNotification,
                subtitle = s.allowApproveFromNotificationDesc,
                checked = quickApprove,
                enabled = notifyApproval,
                onCheckedChange = {
                    quickApprove = it
                    notifyPrefs.allowApproveFromNotification = it
                },
            )
        }
    }
    // 第 2 步 B1：远程图片开关移到「隐私」分组。默认不自动加载——
    // 对话里的网络图片可能被提示词注入用来外泄内容、暴露 IP。
    DshListSection(header = s.sectionPrivacy, footer = s.autoLoadRemoteImagesFooter) {
        DshSwitchRow(
            title = s.autoLoadRemoteImages,
            checked = autoLoadRemoteImages,
            onCheckedChange = { autoLoadRemoteImages = it; notifyPrefs.autoLoadRemoteImages = it },
        )
    }
    // K0：有崩溃记录时多一行「上次崩溃」（没有记录则不渲染）
    CrashReportEntry()
    // 方案 7：页脚「DeepLinks 版本号 · 关于」——原来「更多」分区里那一行降级成页脚，
    // 「关于」仍可点进 ABOUT（开源许可在里面，不能丢）
    DshListNote(
        text = "DeepLinks ${BuildConfig.VERSION_NAME} · ${s.tabAbout}",
        onClick = { onOpen(SettingsDest.ABOUT) },
    )
}

// ---------- 通用：语言与配对（WI-004：服务端设置为唯一真实源，保存需读回校验） ----------

@Composable
internal fun LanguageSettings(
    appSettings: AppSettings,
    savingNs: String?,
    saveErrors: Map<String, String>,
    onSave: (ns: String, patch: org.json.JSONObject, onSuccess: () -> Unit) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val s = DshS
    DshListSection(footer = s.languageDesc) {
        DshSelectRow(
            title = s.language,
            icon = TranslateOutline16,
            value = if (appSettings.language == "zh") s.langZh else s.langEn,
            options = listOf(s.langZh to "zh", s.langEn to "en"),
            selectedId = appSettings.language,
            saving = savingNs == "locale",
            error = saveErrors["locale"],
            onRetry = { onSave("locale", org.json.JSONObject().put("preference", appSettings.language), {}) },
            onSelect = { _, id ->
                LocaleManager.setLanguage(context, id)
                onSave("locale", org.json.JSONObject().put("preference", id), {})
            },
        )
    }
}

// ---------- 外观：主题 / 深色背景 / 字号 / 系统字体 / 动态取色 ----------

@Composable
internal fun AppearanceSettings(
    savingNs: String?,
    saveErrors: Map<String, String>,
    onSave: (ns: String, patch: org.json.JSONObject, onSuccess: () -> Unit) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val s = DshS
    val haptic = rememberDshHaptic()
    DshListSection(header = s.settingsTheme, footer = s.darkBackgroundDesc) {
        DshSelectRow(
            title = s.settingsTheme,
            icon = ContrastOutline16,
            value = when (ThemeManager.currentThemeMode) {
                "light" -> s.themeLight
                "dark" -> s.themeDark
                else -> s.themeSystem
            },
            options = listOf(
                s.themeLight to "light",
                s.themeDark to "dark",
                s.themeSystem to "system",
            ),
            selectedId = ThemeManager.currentThemeMode,
            saving = savingNs == "ui-theme",
            error = saveErrors["ui-theme"],
            onRetry = { onSave("ui-theme", org.json.JSONObject().put("preference", ThemeManager.currentThemeMode), {}) },
            onSelect = { _, id ->
                ThemeManager.setThemeMode(context, id)
                onSave("ui-theme", org.json.JSONObject().put("preference", id), {})
            },
        )
        DshSelectRow(
            title = s.darkBackground,
            icon = DarkOutline16,
            value = if (ThemeManager.pureBlack) s.darkBackgroundBlack else s.darkBackgroundSoft,
            options = listOf(s.darkBackgroundSoft to "soft", s.darkBackgroundBlack to "black"),
            selectedId = if (ThemeManager.pureBlack) "black" else "soft",
            onSelect = { _, id -> ThemeManager.setPureBlack(context, id == "black") },
        )
    }
    DshListSection(header = s.sectionText, footer = s.systemFontDesc) {
        DshSelectRow(
            title = s.settingsFontSize,
            icon = TextSizeOutline16,
            value = when (FontScaleManager.currentScale) {
                FontScaleManager.SMALL -> s.fontSizeSmall
                FontScaleManager.LARGE -> s.fontSizeLarge
                else -> s.fontSizeDefault
            },
            options = listOf(
                s.fontSizeSmall to FontScaleManager.SMALL,
                s.fontSizeDefault to FontScaleManager.DEFAULT,
                s.fontSizeLarge to FontScaleManager.LARGE,
            ),
            selectedId = FontScaleManager.currentScale,
            onSelect = { _, id -> FontScaleManager.setScale(context, id) },
        )
        DshSwitchRow(
            title = s.systemFont,
            icon = FontOutline16,
            checked = UiFontManager.useSystemFont,
            onCheckedChange = { haptic(DshHaptic.Tick); UiFontManager.setUseSystemFont(context, it) },
        )
    }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        DshListSection(header = s.sectionColor, footer = s.dynamicColorDesc) {
            DshSwitchRow(
                title = s.dynamicColor,
                icon = ImageOutline16,
                checked = ThemeManager.dynamicColor,
                onCheckedChange = { haptic(DshHaptic.Tick); ThemeManager.setDynamicColor(context, it) },
            )
        }
    }
}

// ---------- 对话：预设 / 权限 / 繁忙行为 ----------

@Composable
internal fun ConversationSettings(
    appSettings: AppSettings,
    savingNs: String?,
    saveErrors: Map<String, String>,
    onShowFullAccessConfirm: () -> Unit,
    onSave: (ns: String, patch: org.json.JSONObject, onSuccess: () -> Unit) -> Unit,
) {
    val s = DshS
    DshListSection(header = s.sectionNewSessionDefaults, footer = s.agentPresetDesc) {
        DshSelectRow(
            title = s.agentPreset,
            icon = AgentPresetOutline16,
            value = presetDisplayName(appSettings.agentPreset, null, s),
            options = listOf(
                s.presetStandard to "standard",
                s.presetCode to "ptc",
                s.presetMinimal to "minimal",
                s.presetCreator to "cordis",
            ),
            selectedId = appSettings.agentPreset,
            saving = savingNs == "agent-presets",
            error = saveErrors["agent-presets"],
            onRetry = { onSave("agent-presets", org.json.JSONObject().put("default", appSettings.agentPreset), {}) },
            onSelect = { _, id ->
                onSave("agent-presets", org.json.JSONObject().put("default", id), {})
            },
        )
        DshSelectRow(
            title = s.permission,
            icon = ShieldOutline16,
            value = when (appSettings.permissionPreset) {
                "read-only" -> s.permReadOnly
                "danger-full-access" -> s.permFullAccess
                else -> s.permWorkspaceWrite
            },
            options = listOf(
                s.permReadOnly to "read-only",
                s.permWorkspaceWrite to "workspace-write",
                s.permFullAccess to "danger-full-access",
            ),
            selectedId = appSettings.permissionPreset,
            saving = savingNs == "permission",
            error = saveErrors["permission"],
            onRetry = { onSave("permission", org.json.JSONObject().put("defaultPreset", appSettings.permissionPreset), {}) },
            onSelect = { _, id ->
                if (id == "danger-full-access") {
                    onShowFullAccessConfirm()
                } else {
                    onSave("permission", org.json.JSONObject().put("defaultPreset", id), {})
                }
            },
        )
    }
}

private fun openReleasePage(context: android.content.Context, url: String) {
    if (!url.startsWith("https://")) return
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
    context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ---------- 关于 ----------

@Composable
internal fun AboutSettings(onOpenLegal: (fileName: String, title: String) -> Unit) {
    val s = DshS
    val context = androidx.compose.ui.platform.LocalContext.current
    var checkUpdates by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(dev.deeplinks.core.UpdateCheckPrefs.enabled(context))
    }
    var newer by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(dev.deeplinks.core.UpdateCheckPrefs.cachedNewer(context))
    }
    DshListSection(footer = s.unofficialNotice) {
        DshListRow(
            title = "DeepLinks",
            subtitle = s.aboutVersion.replace("%s", BuildConfig.VERSION_NAME),
            icon = InfoOutline16,
        )
        DshSwitchRow(
            title = s.checkUpdates,
            checked = checkUpdates,
            onCheckedChange = {
                checkUpdates = it
                dev.deeplinks.core.UpdateCheckPrefs.setEnabled(context, it)
                if (!it) newer = null
            },
        )
        val release = newer
        if (checkUpdates && release != null) {
            DshListRow(
                title = s.updateAvailable.format(dev.deeplinks.core.displayVersion(release.tagName)),
                icon = InfoOutline16,
                onClick = { openReleasePage(context, release.htmlUrl) },
            )
        }
    }
    DshListSection(header = s.sectionLegal) {
        DshListRow(
            title = s.openSourceLicense,
            icon = FileOutline16,
            value = "MIT",
            onClick = { onOpenLegal("LICENSE", s.openSourceLicense) },
        )
        DshListRow(
            title = s.thirdPartyNotices,
            icon = FileOutline16,
            onClick = { onOpenLegal("THIRD_PARTY_NOTICES.md", s.thirdPartyNotices) },
        )
    }
}

// ---------- 会话管理：已归档 / 已删除 ----------

@Composable
private fun SessionsSettings(host: Host?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val s = DshS
    val prefs = remember { WorkspacePrefs(context) }
    var archivedIds by remember { mutableStateOf(prefs.archivedSessionIds) }
    var deletedIds by remember { mutableStateOf(prefs.deletedSessionIds) }
    var snapshots by remember { mutableStateOf(prefs.sessionSnapshots) }
    var hiddenIds by remember { mutableStateOf(prefs.settingsHiddenSessionIds) }
    var liveById by remember { mutableStateOf<Map<String, MobileSession>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadEpoch by remember { mutableStateOf(0) }
    var pendingClearAll by remember { mutableStateOf(false) }

    fun reloadLocal() {
        archivedIds = prefs.archivedSessionIds
        deletedIds = prefs.deletedSessionIds
        snapshots = prefs.sessionSnapshots
        hiddenIds = prefs.settingsHiddenSessionIds
    }

    LaunchedEffect(host, reloadEpoch) {
        loading = true
        loadError = null
        if (host != null) {
            withContext(Dispatchers.IO) {
                try {
                    val client = MobileApiClient(host)
                    val sessionSnapshot = client.getSessions()
                    val sessions = sessionSnapshot.sessions
                    val catalog = try { client.getWorkspaces() } catch (_: Exception) { null }
                    withContext(Dispatchers.Main) {
                        liveById = sessions.associateBy { it.sessionId }
                        val serverArchivedIds = when {
                            sessionSnapshot.archiveSnapshotAvailable -> sessionSnapshot.archivedSessionIds
                            catalog?.archiveSnapshotAvailable == true -> catalog.archivedSessionIds
                            else -> null
                        }
                        if (serverArchivedIds != null) {
                            val restored = prefs.restoredSessionIds
                            val nextRestored = restored intersect serverArchivedIds
                            if (nextRestored != restored) {
                                prefs.restoredSessionIds = nextRestored
                            }
                            val syncedArchivedIds = reconcileArchivedSessionIds(serverArchivedIds, nextRestored)
                            if (syncedArchivedIds != prefs.archivedSessionIds) {
                                prefs.archivedSessionIds = syncedArchivedIds
                            }
                            // 为仅有 id、尚无快照的归档项补一条占位
                            (syncedArchivedIds - nextRestored).forEach { id ->
                                if (id !in prefs.sessionSnapshots) {
                                    val live = sessions.firstOrNull { it.sessionId == id }
                                    prefs.rememberSessionSnapshot(
                                        sessionId = id,
                                        title = live?.title ?: id.take(8),
                                        cwd = live?.cwd,
                                        updatedAt = live?.updatedAt ?: 0L,
                                    )
                                }
                            }
                        }
                        // 活跃列表里仍能拿到的归档/删除项，刷新快照标题
                        sessions.forEach { session ->
                            if (session.sessionId in prefs.archivedSessionIds || session.sessionId in prefs.deletedSessionIds) {
                                prefs.rememberSessionSnapshot(
                                    sessionId = session.sessionId,
                                    title = session.title,
                                    cwd = session.cwd,
                                    updatedAt = session.updatedAt,
                                )
                            }
                        }
                        reloadLocal()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        reloadLocal()
                        loadError = e.message?.takeIf { it.isNotBlank() } ?: s.loadFailed
                    }
                }
            }
        } else {
            reloadLocal()
        }
        loading = false
    }

    fun resolveRow(id: String): SessionSnapshot {
        liveById[id]?.let {
            return SessionSnapshot(it.sessionId, it.title, it.cwd, it.updatedAt)
        }
        return snapshots[id] ?: SessionSnapshot(id, id.take(8), null, 0L)
    }

    fun restoreToSidebar(id: String) {
        prefs.archivedSessionIds = prefs.archivedSessionIds - id
        prefs.deletedSessionIds = prefs.deletedSessionIds - id
        prefs.settingsHiddenSessionIds = prefs.settingsHiddenSessionIds - id
        prefs.restoredSessionIds = prefs.restoredSessionIds + id
        // 保留快照无妨；侧边栏以 id 集合为准
        reloadLocal()
    }

    fun clearLocalRecords(ids: Collection<String>) {
        val set = ids.toSet()
        if (set.isEmpty()) return
        prefs.hideFromSettings(set)
        reloadLocal()
    }

    val deletedRows = (deletedIds - hiddenIds).map { resolveRow(it) }.sortedByDescending { it.updatedAt }
    val archivedRows = (archivedIds - deletedIds - hiddenIds).map { resolveRow(it) }.sortedByDescending { it.updatedAt }
    val totalManaged = archivedRows.size + deletedRows.size
    val listKind = catalogKind(
        hasItems = totalManaged > 0,
        initialLoad = loading,
        hasError = loadError != null,
    )

    SessionsSettingsContent(
        listKind = listKind,
        loadError = loadError,
        archivedRows = archivedRows,
        deletedRows = deletedRows,
        onRetry = { reloadEpoch += 1 },
        onRestore = { restoreToSidebar(it) },
        onClear = { clearLocalRecords(listOf(it)) },
        onClearAll = { pendingClearAll = true },
    )

    if (pendingClearAll) {
        DshConfirmDialog(
            title = s.clearAllSessionsTitle,
            message = s.clearAllSessionsMessage.format(totalManaged),
            confirmLabel = s.clearAllLocalRecords,
            danger = true,
            onDismiss = { pendingClearAll = false },
            onConfirm = {
                pendingClearAll = false
                clearLocalRecords((archivedRows + deletedRows).map { it.sessionId })
            },
        )
    }
}

/** 会话管理的纯展示层：已归档 / 已删除两组（单条在行菜单里处理），危险的批量清除只在底部一行。 */
@Composable
internal fun SessionsSettingsContent(
    listKind: SessionListKind,
    loadError: String?,
    archivedRows: List<SessionSnapshot>,
    deletedRows: List<SessionSnapshot>,
    onRetry: () -> Unit,
    onRestore: (String) -> Unit,
    onClear: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    val s = DshS
    DshListCaption(s.sessionsSettingsHint)
    when (listKind) {
        SessionListKind.Loading -> DshListSection { DshListNote(s.loading) }
        SessionListKind.Error -> DshListSection { DshListRetry(loadError ?: s.loadFailed, onRetry) }
        else -> {
            DshListSection(header = s.sectionArchivedSessions) {
                if (archivedRows.isEmpty()) DshListNote(s.noArchivedSessions)
                archivedRows.forEach { row ->
                    ManagedSessionRow(row, onRestore = { onRestore(row.sessionId) }, onClear = { onClear(row.sessionId) })
                }
            }
            DshListSection(header = s.sectionDeletedSessions) {
                if (deletedRows.isEmpty()) DshListNote(s.noDeletedSessions)
                deletedRows.forEach { row ->
                    ManagedSessionRow(row, onRestore = { onRestore(row.sessionId) }, onClear = { onClear(row.sessionId) })
                }
            }
            if (archivedRows.size + deletedRows.size > 0) {
                // 危险操作单独成组（红字 destructive 行），不再与普通设置行混排
                DshListSection(footer = s.clearAllLocalRecordsDesc) {
                    DshListActionRow(
                        label = s.clearAllLocalRecords,
                        icon = TrashOutline16,
                        destructive = true,
                        onClick = onClearAll,
                    )
                }
            }
        }
    }
}

@Composable
private fun ManagedSessionRow(
    snapshot: SessionSnapshot,
    onRestore: () -> Unit,
    onClear: () -> Unit,
) {
    val s = DshS
    var menuOpen by remember { mutableStateOf(false) }
    val desc = buildList {
        snapshot.cwd?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (snapshot.updatedAt > 0L) add(formatSessionTime(snapshot.updatedAt))
    }.joinToString(" · ")
    Box {
        DshListRow(
            title = displaySessionTitle(snapshot.title),
            subtitle = desc.ifBlank { null },
            onClick = { menuOpen = true },
            trailing = DshListTrailing.None,
            trailingContent = {
                Icon(EllipsisOutline16, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(DshIconSize.md))
            },
        )
        Box(Modifier.align(Alignment.BottomEnd).padding(end = DshSpace.s16)) {
            DshMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                offset = DpOffset(0.dp, 4.dp),
                items = listOf(
                    DshMenuItem(UnarchiveOutline16, s.restoreToSidebar) {
                        menuOpen = false
                        onRestore()
                    },
                    DshMenuItem(TrashOutline16, s.removeFromLocalList, danger = true) {
                        menuOpen = false
                        onClear()
                    },
                ),
            )
        }
    }
}

private fun formatSessionTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val millis = if (timestamp < 1_000_000_000_000L) timestamp * 1000 else timestamp
    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(millis))
}