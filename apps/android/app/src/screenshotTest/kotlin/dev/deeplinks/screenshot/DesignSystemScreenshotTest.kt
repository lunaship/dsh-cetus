package dev.deeplinks.screenshot

import androidx.compose.foundation.background
import dev.deeplinks.native.ComposerSuggestionsRow
import dev.deeplinks.native.SessionMenuContent
import dev.deeplinks.native.sessionMenu
import dev.deeplinks.native.ui.v4.DlBottomSheetSurface
import dev.deeplinks.native.ui.v4.DlDiffStat
import dev.deeplinks.native.EllipsisOutline16
import dev.deeplinks.native.ArrowLeftOutline16
import dev.deeplinks.native.SettingsOutline16
import dev.deeplinks.native.PlusOutline16
import dev.deeplinks.native.SparkleOutline16
import dev.deeplinks.native.ArchiveBoxOutline16
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import dev.deeplinks.native.CloseOutline16
import dev.deeplinks.native.TrashOutline16
import dev.deeplinks.native.ShareOutline16
import dev.deeplinks.native.CopyOutline16
import dev.deeplinks.native.EditOutline16
import dev.deeplinks.native.LinkOutline16
import dev.deeplinks.native.RefreshOutline16
import dev.deeplinks.native.SendOutline16
import dev.deeplinks.native.Sparkle16
import dev.deeplinks.native.AgentPresetOutline16
import dev.deeplinks.native.ArchiveOutline20
import dev.deeplinks.native.FolderOpenOutline16
import dev.deeplinks.native.ChevronRightOutline16
import dev.deeplinks.native.ChevronDownOutline16
import dev.deeplinks.native.CheckOutline16
import dev.deeplinks.native.WarningOutline16
import dev.deeplinks.native.CameraOutline16
import dev.deeplinks.native.ClockOutline16
import dev.deeplinks.native.CompressOutline16
import dev.deeplinks.native.ContrastOutline16
import dev.deeplinks.native.DevicesOutline16
import dev.deeplinks.native.FeedbackOutline16
import dev.deeplinks.native.FileOutline16
import dev.deeplinks.native.FontOutline16
import dev.deeplinks.native.GiftOutline16
import dev.deeplinks.native.ImageOutline16
import dev.deeplinks.native.RemoteImageBlock
import dev.deeplinks.native.InfoOutline16
import dev.deeplinks.native.KeyOutline16
import dev.deeplinks.native.KeyboardOutline16
import dev.deeplinks.native.LaptopOutline16
import dev.deeplinks.native.MessageOutline16
import dev.deeplinks.native.MicOutline16
import dev.deeplinks.native.PaletteOutline16
import dev.deeplinks.native.QuoteOutline16
import dev.deeplinks.native.ScanOutline16
import dev.deeplinks.native.ShieldOutline16
import dev.deeplinks.native.SwapOutline16
import dev.deeplinks.native.TextSizeOutline16
import dev.deeplinks.native.TranslateOutline16
import dev.deeplinks.native.UnarchiveOutline16
import dev.deeplinks.native.UnlinkOutline16
import dev.deeplinks.native.WalletOutline16
import dev.deeplinks.native.WrapOutline16
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import dev.deeplinks.core.DarkDshColors
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshStringsEn
import dev.deeplinks.core.DshStringsZh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.LightDshColors
import dev.deeplinks.core.pureBlack
import dev.deeplinks.core.DshS
import dev.deeplinks.core.LocalDshColors
import dev.deeplinks.core.LocalDshStrings
import dev.deeplinks.core.LocaleManager
import dev.deeplinks.core.dshTypography
import androidx.compose.foundation.layout.ColumnScope
import dev.deeplinks.core.Host
import dev.deeplinks.devices.DeviceDetailSections
import dev.deeplinks.devices.DeviceState
import dev.deeplinks.devices.DeviceUi
import dev.deeplinks.native.AboutSettings
import dev.deeplinks.native.AppSettings
import dev.deeplinks.native.AppearanceSettings
import dev.deeplinks.native.ConversationSettings
import dev.deeplinks.native.SessionsSettingsContent
import dev.deeplinks.native.SettingsDest
import dev.deeplinks.native.SettingsHome
import dev.deeplinks.native.SettingsPageCanvas
import dev.deeplinks.native.ui.DshListRow
import dev.deeplinks.native.BranchOutline16
import dev.deeplinks.native.CloudOffOutline16
import dev.deeplinks.native.DocumentCheckOutline16
import dev.deeplinks.native.ListOutline16
import dev.deeplinks.native.LockOutline16
import dev.deeplinks.native.UploadOutline16
import dev.deeplinks.native.ui.DshChipTone
import dev.deeplinks.native.ui.DshPillButton
import dev.deeplinks.native.ui.DshPillTone
import dev.deeplinks.native.ui.DshStatusChip
import dev.deeplinks.native.ui.DshPageNavigation
import dev.deeplinks.native.ui.DshPageScaffold
import dev.deeplinks.native.ui.DshSection
import dev.deeplinks.native.ui.DshSectionContainer
import dev.deeplinks.native.ui.DshStatusBadge
import dev.deeplinks.native.ui.DshStatusTone
import dev.deeplinks.native.ui.DshSwitchRow
import dev.deeplinks.native.util.SessionListKind
import dev.deeplinks.native.util.SessionSnapshot
import dev.deeplinks.native.ContextMeterRow
import dev.deeplinks.native.CommandSuggestions
import dev.deeplinks.native.ComposerContextStrip
import dev.deeplinks.native.InputBar
import dev.deeplinks.native.WorkspaceChangesSummary
import dev.deeplinks.native.SearchOutline16
import dev.deeplinks.native.MobileSessionStats
import dev.deeplinks.native.UsagePanel
import dev.deeplinks.native.WorkspaceTopBar
import dev.deeplinks.native.chatEmptyCanvas
import dev.deeplinks.native.ComposerSeatsRow
import dev.deeplinks.native.OfflineStatusSlot
import dev.deeplinks.native.PreviewStatusSlot
import dev.deeplinks.native.SessionStatus
import dev.deeplinks.native.sessionStatus
import dev.deeplinks.native.ui.ChatLoadingSkeleton
import dev.deeplinks.native.util.ChatCanvasKind
import dev.deeplinks.native.util.StreamBannerKind
import dev.deeplinks.native.ui.DshBadge
import dev.deeplinks.native.ui.DshBanner
import dev.deeplinks.native.ui.DshBannerTone
import dev.deeplinks.native.DshRadius
import dev.deeplinks.native.ui.DshFilterChip
import dev.deeplinks.native.ui.DshTag
import dev.deeplinks.native.ui.DshTextTabs
import dev.deeplinks.core.dshColorScheme
import dev.deeplinks.native.GlobeOutline16
import dev.deeplinks.native.GoalOutline16
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import dev.deeplinks.native.ui.v4.DlAction
import dev.deeplinks.native.ui.v4.DlButtonStyle
import dev.deeplinks.native.ui.v4.DlChip
import dev.deeplinks.native.ui.v4.DlChipStyle
import dev.deeplinks.native.ui.v4.DlComposer
import dev.deeplinks.native.ui.v4.DlDecisionBar
import dev.deeplinks.native.ui.v4.DlDecisionOption
import dev.deeplinks.native.ui.v4.DlDialogSurface
import dev.deeplinks.native.ui.v4.DlInboxItem
import dev.deeplinks.native.ui.v4.DlListRow
import dev.deeplinks.native.ui.v4.DlRowTrailing
import dev.deeplinks.native.ui.v4.DlSectionHeader
import dev.deeplinks.native.ui.v4.DlSegmented
import dev.deeplinks.native.ui.v4.DlSendState
import dev.deeplinks.native.ui.v4.DlStatusSlot
import dev.deeplinks.native.ui.v4.DlTone
import dev.deeplinks.native.ui.v4.DlTopBar
import dev.deeplinks.native.ui.v4.DlTopBarAction
import dev.deeplinks.native.ui.v4.DlTopBarNav

/**
 * Compose Preview Screenshot Testing 基线（AGP 内置）。
 *
 * 与 app/src/debug 的 @Preview 目录不同，这里刻意不经过 DshTheme（避免
 * LaunchedEffect 里的 SharedPreferences 初始化），而是直接提供
 * LocalDshColors / LocalDshStrings，保证宿主端渲染稳定可回归。
 *
 * 覆盖：语义字阶、组件墙、颜色 token 墙、Settings 首页（亮/暗 + 中/英）。
 * 生成/更新基线：./gradlew updateDebugScreenshotTest
 * 校验：        ./gradlew validateDebugScreenshotTest
 */

@Composable
internal fun ShotFrame(dark: Boolean, english: Boolean = false, content: @Composable () -> Unit) {
    // E1：直接读全局 LocaleManager.strings 的组件（首页副标题、审批卡、时间标签等）
    // 不会因为注入本地 LocalDshStrings 而变语言；渲染环境（layoutlib）没有可用的
    // SharedPreferences，所以走 setLanguageForPreview 只切内存态，英文预览不混中文。
    LocaleManager.setLanguageForPreview(if (english) "en" else "zh")
    val colors = if (dark) DarkDshColors else LightDshColors
    val typography = dshTypography()
    MaterialTheme(colorScheme = dshColorScheme(colors), typography = typography) {
        CompositionLocalProvider(
            LocalDshColors provides colors,
            LocalDshStrings provides if (english) DshStringsEn else DshStringsZh,
            LocalTextStyle provides typography.bodyMedium,
        ) {
            content()
        }
    }
}

@Composable
private fun Wall(dark: Boolean, english: Boolean, content: @Composable () -> Unit) {
    ShotFrame(dark = dark, english = english) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Dsh.bgBase)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Dsh.labelTertiary, style = DshType.label)
}

/**
 * 收件箱组件墙（2026-09-28 重设计 · 阶段 1）：
 * 胶囊按钮三种语义、四种状态胶囊、32dp 状态图标圈、白色分组卡与分隔线、
 * 分组标签、底部悬浮主按钮。浅色 / 深色各出一张，改色板或改形状时看这两张。
 */
@Composable
private fun InboxWall(english: Boolean = false) {
    SectionTitle("Pill buttons — Accent / Ink / Tonal")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        DshPillButton(label = if (english) "Approve" else "批准", onClick = {}, tone = DshPillTone.Accent)
        DshPillButton(label = if (english) "Reject" else "拒绝", onClick = {}, tone = DshPillTone.Tonal)
        DshPillButton(label = if (english) "Stop" else "停止", onClick = {}, tone = DshPillTone.Ink)
        DshPillButton(label = if (english) "Send" else "发送", onClick = {}, tone = DshPillTone.Accent, icon = SendOutline16)
        DshPillButton(label = if (english) "Disabled" else "置灰", onClick = {}, tone = DshPillTone.Ink, enabled = false)
    }
    SectionTitle(if (english) "Status chips — waiting for approval / for your answer / on computer / done"
        else "Status chips — 等你批准 / 等你回答 / 在电脑上处理 / 完成")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        DshStatusChip(if (english) "Waiting for approval" else "等你批准", DshChipTone.Approval)
        DshStatusChip(if (english) "Waiting for your answer" else "等你回答", DshChipTone.Answer)
        DshStatusChip(if (english) "On computer" else "在电脑上处理", DshChipTone.Remote)
        DshStatusChip(if (english) "Done" else "完成", DshChipTone.Done)
        DshStatusChip(if (english) "Stopped" else "已停止", DshChipTone.Remote)
    }
    SectionTitle(if (english) "Icons — new this round (doc / cloud / list / lock / upload / branch)"
        else "Icons — 本次新增（文档/云/列表/锁/上传/分支）")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(
            DocumentCheckOutline16,
            CloudOffOutline16,
            ListOutline16,
            LockOutline16,
            UploadOutline16,
            BranchOutline16,
        ).forEach { icon ->
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Dsh.labelPrimary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@PreviewTest
@Preview(name = "inbox components light zh", showBackground = true, widthDp = 412, heightDp = 720)
@Composable
internal fun InboxComponentsLightZh() {
    Wall(dark = false, english = false) { InboxWall() }
}

@PreviewTest
@Preview(name = "inbox components dark en", showBackground = true, widthDp = 412, heightDp = 720)
@Composable
internal fun InboxComponentsDarkEn() {
    Wall(dark = true, english = true) { InboxWall(english = true) }
}

/**
 * 纯黑（OLED）模式：只压画布 / 侧栏 / 代码底，卡片与气泡保持原色阶。
 * 色板换值后这一档最容易出现「卡片和底糊在一起」，所以单独留一张基线。
 */
@Composable
private fun PureBlackWall(content: @Composable () -> Unit) {
    // 纯黑基线只出中文一张：全局语言固定回中文，避免上一张英文预览把它带成英文。
    LocaleManager.setLanguageForPreview("zh")
    val colors = DarkDshColors.pureBlack()
    val typography = dshTypography()
    MaterialTheme(typography = typography) {
        CompositionLocalProvider(
            LocalDshColors provides colors,
            LocalDshStrings provides DshStringsZh,
            LocalTextStyle provides typography.bodyMedium,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Dsh.bgBase)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                content()
            }
        }
    }
}

@PreviewTest
@Preview(name = "inbox components pure black zh", showBackground = true, widthDp = 412, heightDp = 720)
@Composable
internal fun InboxComponentsPureBlackZh() {
    PureBlackWall { InboxWall() }
}

@Composable
private fun TypeScale() {
    SectionTitle("Type scale")
    Text("Display 28 / displayMedium", style = DshType.display, color = Dsh.labelPrimary)
    Text("Headline 18 / headlineSmall", style = DshType.headline, color = Dsh.labelPrimary)
    Text("Title 15 / titleMedium", style = DshType.title, color = Dsh.labelPrimary)
    Text("Body 15 / bodyMedium", style = DshType.body, color = Dsh.labelPrimary)
    Text("Caption 12 / bodySmall", style = DshType.caption, color = Dsh.labelSecondary)
    Text("Label 12 / labelMedium", style = DshType.label, color = Dsh.labelTertiary)
}

@Composable
private fun ComponentWall(english: Boolean = false) {
    SectionTitle("Chips")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        DshFilterChip(label = if (english) "All" else "全部", selected = true, onClick = {})
        DshFilterChip(label = if (english) "Chats" else "对话", selected = false, count = 12, onClick = {})
    }
    SectionTitle("Tabs")
    DshTextTabs(labels = if (english) listOf("Chat", "Trace") else listOf("对话", "轨迹"), selectedIndex = 0, onSelect = {})
    SectionTitle("Tags & badges")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        DshTag(text = if (english) "Task" else "任务")
        DshTag(text = if (english) "Compacted" else "压缩")
        DshBadge(dot = true)
        DshBadge(count = 3)
        DshBadge(count = 150)
    }
    SectionTitle("Banners")
    DshBanner(text = if (english) "Live stream dropped. Reconnecting…" else "实时流连接断开，正在重连…")
    DshBanner(text = if (english) "Waiting for approval" else "等待审批通过", tone = DshBannerTone.Warn, actionLabel = if (english) "View" else "查看", onAction = {})
    DshBanner(text = if (english) "Session archived" else "会话已归档", tone = DshBannerTone.Success)
    DshBanner(text = if (english) "Connection failed" else "连接失败", tone = DshBannerTone.Error, actionLabel = if (english) "Retry" else "重试", onAction = {})
    SectionTitle("Loading")
    ChatLoadingSkeleton()
}

@Composable
private fun TokenWall() {
    SectionTitle("Color tokens")
    val swatches: List<Pair<String, Color>> = listOf(
        "bgBase" to Dsh.bgBase,
        "bgSidePanel" to Dsh.bgSidePanel,
        "bgSurface" to Dsh.bgSurface,
        "bgInput" to Dsh.bgInput,
        "bgSubtle" to Dsh.bgSubtle,
        "brand400" to Dsh.brand400,
        "success" to Dsh.success,
        "warn" to Dsh.warn,
        "error" to Dsh.error,
        "traceReasoning" to Dsh.traceReasoning,
        "inkFill" to Dsh.inkFill,
        "onInk" to Dsh.onInk,
        "borderStrong" to Dsh.borderStrong,
        "successContent" to Dsh.successContent,
        "cloudContent" to Dsh.cloudContent,
        "cloudContainer" to Dsh.cloudContainer,
        "brandTint" to Dsh.brandTint,
        "onBrand" to Dsh.onBrand,
        "systemAccent" to Dsh.systemAccent,
        "toolsAccent" to Dsh.toolsAccent,
    )
    swatches.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { (name, color) ->
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(color),
                    )
                    // 11sp 等宽：token 名完整可读（原 12sp + 半宽行全被截断）
                    Text(
                        name,
                        style = DshType.microRelaxed,
                        fontFamily = FontFamily.Monospace,
                        color = Dsh.labelSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsHomeWall() {
    SettingsHome(appSettings = AppSettings(), onOpen = { _: SettingsDest -> })
}

@Composable
private fun ChromeWall() {
    SectionTitle("Stream banner")
    for (kind in listOf(StreamBannerKind.Retrying, StreamBannerKind.Failed)) {
        val status = sessionStatus(kind, null, null, null, emptyList(), running = false, previewPorts = listOf(5173))
        OfflineStatusSlot(status as SessionStatus.Offline)
    }
    PreviewStatusSlot(SessionStatus.Preview(listOf(5173)))
    SectionTitle("Context meter rows")
    ContextMeterRow(label = "System prompt", value = "5.1K", swatchColor = Dsh.systemAccent)
    ContextMeterRow(label = "Tools", value = "2.4K", swatchColor = Dsh.toolsAccent)
    ContextMeterRow(label = "Messages", value = "18.7K", swatchColor = Dsh.brand400)
    SectionTitle("Composer context strip")
    ComposerContextStrip(
        online = true,
        changes = WorkspaceChangesSummary(seq = 1, turn = 3, total = 6, added = 250, deleted = 50, files = emptyList()),
        onOpenChanges = {},
    )
    ComposerContextStrip(
        online = false,
        changes = null,
        onOpenChanges = {},
    )
    SectionTitle("Composer seats")
    ComposerSeatsRow(
        modelName = "DeepSeek V4 Flash",
        modelEffort = "high",
        permissionPreset = "workspace-write",
        permissionLabel = DshS.permWorkspaceWrite,
    )
    ComposerSeatsRow(
        modelName = "GLM-5.3-Flash",
        modelEffort = null,
        permissionPreset = "read-only",
        permissionLabel = DshS.permReadOnly,
    )
    ComposerSeatsRow(
        modelName = "MiniMax-M3",
        modelEffort = "high",
        permissionPreset = "danger-full-access",
        permissionLabel = DshS.permFullAccess,
    )
    ComposerSeatsRow(
        modelName = null,
        modelEffort = null,
        permissionPreset = "workspace-write",
        permissionLabel = DshS.permWorkspaceWrite,
        compact = true,
    )
}

@PreviewTest
@Preview(name = "workspace chrome light", showBackground = true, widthDp = 412, heightDp = 700)
@Composable
internal fun WorkspaceChromeLight() {
    Wall(dark = false, english = false) { ChromeWall() }
}

/**
 * 会话用量预览共用的同一份会话统计（E8）：输入框上下文环与「会话用量」面板的
 * 「上下文占用」是同一口径（contextPressureTokens / contextWindow），样例数据
 * 也必须一致，否则两张截图互相矛盾（曾出现 46% vs 19%）。
 */
private val sampleSessionStats = MobileSessionStats(
    turns = 3,
    steps = 421,
    llmMs = 82_000,
    toolMs = 43_000,
    decodeMs = 19_000,
    decodeTokens = 1_900,
    uncachedInputTokens = 2_100_000,
    cacheReadTokens = 126_000_000,
    outputTokens = 1_000_000,
    contextPressureTokens = 60_000,
    contextWindow = 128_000,
    systemTokens = 2_800,
    toolsTokens = 6_400,
    messageTokens = 15_300,
)

/**
 * 对话页输入区（v4 4.1）：建议行 + 实底输入区（DlComposer），与生产 WorkspaceScreen 同构。
 * ChatBottomWall 与空态画布帧共用，样例数据（含上下文占用）保持单一来源。
 */
@Composable
private fun ChatComposerArea(modifier: Modifier = Modifier) {
    val stats = sampleSessionStats
    Column(modifier = modifier.background(Dsh.bgBase)) {
        ComposerSuggestionsRow(
            online = true,
            suggestionsVisible = true,
            changesCount = null,
            onSuggestion = {},
            onOpenChanges = {},
        )
        InputBar(
            inputText = "",
            onInputChange = {},
            isListening = false,
            isSending = false,
            canSend = false,
            running = false,
            modelName = "step-5-preview",
            modelEffort = "high",
            sessionStats = stats,
            permissionPreset = "workspace-write",
            permissionLabel = DshS.permWorkspaceWrite,
            onOpenModelPicker = {},
            onOpenPermissionPicker = {},
            onToggleVoice = {},
            onStop = {},
            onSend = {},
        )
    }
}

/** 对话页底部整体（方案 9：宽屏时输入区封顶 760dp 居中）。 */
@Composable
internal fun ChatBottomWall(capWidth: Boolean = false) {
    ChatComposerArea(
        modifier = if (capWidth) Modifier.widthIn(max = 760.dp).wrapContentWidth(Alignment.CenterHorizontally) else Modifier,
    )
}

@PreviewTest
@Preview(name = "chat bottom light zh", showBackground = true, widthDp = 412, heightDp = 200)
@Composable
internal fun ChatBottomLightZh() {
    Wall(dark = false, english = false) { ChatBottomWall() }
}

@PreviewTest
@Preview(name = "chat bottom wide", showBackground = true, widthDp = 1024, heightDp = 200)
@Composable
internal fun ChatBottomWide() {
    // 方案 9：宽屏下输入区要封顶 760 居中。手机宽度的两张墙看不出这件事，
    // 所以单独加一张 1024 宽的（上一轮补过程行墙时，正是新墙立刻抓出两个真问题）。
    Wall(dark = false, english = false) { ChatBottomWall(capWidth = true) }
}

@PreviewTest
@Preview(name = "chat bottom dark en", showBackground = true, widthDp = 412, heightDp = 200)
@Composable
internal fun ChatBottomDarkEn() {
    Wall(dark = true, english = true) { ChatBottomWall() }
}

@Composable
private fun TopBarWall(english: Boolean = false) {
    WorkspaceTopBar(
        title = if (english) "Sync approval state" else "完善审批状态同步",
        subtitle = if (english) "dsh-links · Running · Step 12" else "dsh-links · 运行中 · 第 12 步",
        onNavigate = {},
        menuExpanded = false,
        onMenuExpandedChange = {},
        menu = sessionMenu(onClose = {}),
        diff = DlDiffStat(148, 37) {},
    )
}

/** v4 4.9：会话 ⋯ 菜单（弹层静态外观）。 */
@Composable
private fun SessionMenuWall() {
    val menu = sessionMenu(
        onClose = {},
        changes = WorkspaceChangesSummary(seq = 1, turn = 3, total = 4, added = 62, deleted = 9, files = emptyList()),
        canBrowseFiles = true,
        subagentCount = 2,
        previewSupported = true,
        canGoal = true,
        canSchedules = true,
    )
    Column(Modifier.fillMaxSize().background(Dsh.bgOverlay), verticalArrangement = Arrangement.Bottom) {
        DlBottomSheetSurface { SessionMenuContent(menu) }
    }
}

@PreviewTest
@Preview(name = "session menu light zh", showBackground = true, widthDp = 412, heightDp = 860)
@Composable
internal fun SessionMenuLightZh() {
    ShotFrame(dark = false, english = false) { SessionMenuWall() }
}

@PreviewTest
@Preview(name = "session menu dark en", showBackground = true, widthDp = 412, heightDp = 860)
@Composable
internal fun SessionMenuDarkEn() {
    ShotFrame(dark = true, english = true) { SessionMenuWall() }
}

@PreviewTest
@Preview(name = "workspace top bar light", showBackground = true, widthDp = 412, heightDp = 140)
@Composable
internal fun WorkspaceTopBarLight() {
    Wall(dark = false, english = false) { TopBarWall(english = false) }
}

@PreviewTest
@Preview(name = "session usage light zh", showBackground = true, widthDp = 412, heightDp = 1100)
@Composable
internal fun SessionUsageLightZh() {
    SessionUsageWall(dark = false, english = false, stats = sampleSessionStats.copy(ttftMs = 4_200, ttftSteps = 3))
}

@PreviewTest
@Preview(name = "session usage dark en", showBackground = true, widthDp = 412, heightDp = 1100)
@Composable
internal fun SessionUsageDarkEn() {
    SessionUsageWall(dark = true, english = true, stats = sampleSessionStats.copy(ttftMs = 4_200, ttftSteps = 3))
}

@PreviewTest
@Preview(name = "session usage partial light zh", showBackground = true, widthDp = 412, heightDp = 900)
@Composable
internal fun SessionUsagePartialLightZh() {
    SessionUsageWall(
        dark = false,
        english = false,
        stats = MobileSessionStats(turns = 2, uncachedInputTokens = 1_200, outputTokens = 340),
    )
}

@PreviewTest
@Preview(name = "session usage old host light zh", showBackground = true, widthDp = 412, heightDp = 280)
@Composable
internal fun SessionUsageOldHostLightZh() {
    SessionUsageWall(dark = false, english = false, stats = null)
}

@Composable
private fun SessionUsageWall(dark: Boolean, english: Boolean, stats: MobileSessionStats?) {
    Wall(dark = dark, english = english) {
        UsagePanel(stats)
    }
}

@Composable
private fun ChatCanvasFrame(
    kind: ChatCanvasKind,
    dark: Boolean = false,
    english: Boolean = false,
    elapsedSec: Long = 0L,
    error: String? = null,
) {
    ShotFrame(dark = dark, english = english) {
        // E6：ChatCanvasKind.Empty 按设计「空会话只留白」（WorkspaceChrome 里就是一个
        // 空的 fillParentMaxSize Box，没有任何入场动画），所以只截画布必然是全空白图。
        // 真机上这一屏真正的起点是下方的输入框占位句，把输入区一起入镜：既还原空会话
        // 的真实长相，也让基线能守住输入区回归。
        Column(modifier = Modifier.fillMaxSize().background(Dsh.bgBase)) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                chatEmptyCanvas(kind = kind, elapsedSec = elapsedSec, historyLoadError = error, onRetry = {})
            }
            ChatComposerArea()
        }
    }
}

@PreviewTest
@Preview(name = "chat empty hero", showBackground = true, widthDp = 412, heightDp = 600)
@Composable
internal fun ChatEmptyHero() {
    ChatCanvasFrame(ChatCanvasKind.Empty)
}

@PreviewTest
@Preview(name = "chat empty error", showBackground = true, widthDp = 412, heightDp = 600)
@Composable
internal fun ChatEmptyError() {
    ChatCanvasFrame(ChatCanvasKind.Error)
}

// ---- 第 2 步 B4：远程图片占位卡 / 失败态 / 设置「隐私」分组 ----

@PreviewTest
@Preview(name = "remote image placeholder", showBackground = true, widthDp = 412, heightDp = 200)
@Composable
internal fun RemoteImagePlaceholder() {
    ShotFrame(dark = false) {
        RemoteImageBlock(url = "https://example.com/a.png")
    }
}

@PreviewTest
@Preview(name = "remote image placeholder dark en", showBackground = true, widthDp = 412, heightDp = 200)
@Composable
internal fun RemoteImagePlaceholderDarkEn() {
    ShotFrame(dark = true, english = true) {
        RemoteImageBlock(url = "https://example.com/a.png")
    }
}

@PreviewTest
@Preview(name = "settings privacy section", showBackground = true, widthDp = 412, heightDp = 260)
@Composable
internal fun SettingsPrivacySection() {
    ShotFrame(dark = false) {
        SettingsHome(
            appSettings = AppSettings(),
            onOpen = {},
            host = null,
        )
    }
}

@PreviewTest
@Preview(name = "settings privacy section dark en", showBackground = true, widthDp = 412, heightDp = 260)
@Composable
internal fun SettingsPrivacySectionDarkEn() {
    ShotFrame(dark = true, english = true) {
        SettingsHome(
            appSettings = AppSettings(),
            onOpen = {},
            host = null,
        )
    }
}

@PreviewTest
@Preview(name = "workspace top bar dark en", showBackground = true, widthDp = 412, heightDp = 140)
@Composable
internal fun WorkspaceTopBarDarkEn() {
    Wall(dark = true, english = true) { TopBarWall(english = true) }
}

@PreviewTest
@Preview(name = "workspace chrome dark en", showBackground = true, widthDp = 412, heightDp = 700)
@Composable
internal fun WorkspaceChromeDarkEn() {
    Wall(dark = true, english = true) { ChromeWall() }
}

@PreviewTest
@Preview(name = "chat empty hero dark en", showBackground = true, widthDp = 412, heightDp = 600)
@Composable
internal fun ChatEmptyHeroDarkEn() {
    ChatCanvasFrame(ChatCanvasKind.Empty, dark = true, english = true)
}

@PreviewTest
@Preview(name = "chat empty error dark en", showBackground = true, widthDp = 412, heightDp = 600)
@Composable
internal fun ChatEmptyErrorDarkEn() {
    ChatCanvasFrame(ChatCanvasKind.Error, dark = true, english = true, error = "timeout")
}

@PreviewTest
@Preview(name = "components light zh", showBackground = true, widthDp = 412, heightDp = 1400)
@Composable
internal fun ComponentsLightZh() {
    Wall(dark = false, english = false) {
        TypeScale()
        ComponentWall()
        TokenWall()
    }
}

@PreviewTest
@Preview(name = "components dark en", showBackground = true, widthDp = 412, heightDp = 1400)
@Composable
internal fun ComponentsDarkEn() {
    Wall(dark = true, english = true) {
        TypeScale()
        ComponentWall(english = true)
        TokenWall()
    }
}

/**
 * 图标墙：App 只有一套图标（Web 复刻集 DshIcons + 同笔法补充 DshGlyphs），
 * 统一 18dp 满幅绘制；新增或改笔画时这里会出 diff，方便对齐粗细与视觉大小。
 */
private val IconWallGlyphs: List<ImageVector> = listOf(
    SearchOutline16,
    SettingsOutline16,
    PlusOutline16,
    CloseOutline16,
    EllipsisOutline16,
    TrashOutline16,
    ShareOutline16,
    CopyOutline16,
    EditOutline16,
    LinkOutline16,
    RefreshOutline16,
    SendOutline16,
    Sparkle16,
    AgentPresetOutline16,
    ArchiveOutline20,
    FolderOpenOutline16,
    ChevronRightOutline16,
    ChevronDownOutline16,
    CheckOutline16,
    WarningOutline16,
    ArchiveBoxOutline16,
    ArrowLeftOutline16,
    CameraOutline16,
    ClockOutline16,
    CompressOutline16,
    ContrastOutline16,
    DevicesOutline16,
    FeedbackOutline16,
    FileOutline16,
    FontOutline16,
    GiftOutline16,
    ImageOutline16,
    InfoOutline16,
    KeyOutline16,
    KeyboardOutline16,
    LaptopOutline16,
    MessageOutline16,
    MicOutline16,
    PaletteOutline16,
    QuoteOutline16,
    ScanOutline16,
    ShieldOutline16,
    SparkleOutline16,
    SwapOutline16,
    TextSizeOutline16,
    TranslateOutline16,
    UnarchiveOutline16,
    UnlinkOutline16,
    WalletOutline16,
    WrapOutline16,
    // 2026-09-28 重设计新增
    DocumentCheckOutline16,
    CloudOffOutline16,
    ListOutline16,
    LockOutline16,
    UploadOutline16,
    BranchOutline16,
)

@Composable
private fun IconWall() {
    SectionTitle("Icons · DshIcons + DshGlyphs")
    IconWallGlyphs.chunked(8).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            row.forEach { icon ->
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = Dsh.labelPrimary, modifier = Modifier.size(18.dp))
                }
            }
            repeat(8 - row.size) { Box(Modifier.size(44.dp)) }
        }
    }
}

@PreviewTest
@Preview(name = "icons light", showBackground = true, widthDp = 412, heightDp = 460)
@Composable
internal fun IconsLight() {
    Wall(dark = false, english = false) { IconWall() }
}

@PreviewTest
@Preview(name = "icons dark", showBackground = true, widthDp = 412, heightDp = 460)
@Composable
internal fun IconsDark() {
    Wall(dark = true, english = true) { IconWall() }
}

@PreviewTest
@Preview(name = "command palette light zh", showBackground = true, widthDp = 412, heightDp = 560)
@Composable
internal fun CommandPaletteLightZh() {
    Wall(dark = false, english = false) {
        CommandSuggestions(query = "/", onPick = {})
    }
}

@PreviewTest
@Preview(name = "command palette filtered dark en", showBackground = true, widthDp = 412, heightDp = 300)
@Composable
internal fun CommandPaletteFilteredDarkEn() {
    Wall(dark = true, english = true) {
        CommandSuggestions(query = "/c", onPick = {})
    }
}

/**
 * 设置内容画布（与 SettingsRoute 同一件）：画布底 + 16dp 边距 + 独立滚动，
 * Section 默认扁平；设置页用它截图。
 */
@Composable
private fun GroupedWall(dark: Boolean, english: Boolean, content: @Composable ColumnScope.() -> Unit) {
    ShotFrame(dark = dark, english = english) {
        SettingsPageCanvas(content = content)
    }
}

/**
 * Section 容器策略墙（docs/visual-rules.md 第二节）：
 * Flat（默认，行落画布 + 发丝线分组）与 Tonal（bgSubtle 容器，独立数据块）。
 */
@Composable
private fun SectionWall(dark: Boolean, english: Boolean) {
    Wall(dark = dark, english = english) {
        DshSection(header = if (english) "Flat · default" else "Flat · 默认") {
            DshListRow(title = if (english) "Language" else "语言", icon = TranslateOutline16, value = if (english) "Chinese" else "中文", onClick = {})
            DshListRow(title = if (english) "Appearance" else "外观", icon = PaletteOutline16, value = if (english) "System" else "跟随系统", onClick = {})
            DshSwitchRow(title = if (english) "System font" else "系统字体", icon = FontOutline16, checked = true, onCheckedChange = {})
        }
        DshSection(header = if (english) "Tonal · standalone blocks" else "Tonal · 独立数据块", container = DshSectionContainer.Tonal) {
            DshListRow(title = "MacBook Pro", subtitle = if (english) "Online · 24ms · LAN" else "在线 · 24ms · 局域网", icon = LaptopOutline16)
            DshListRow(title = if (english) "Thinking tokens" else "思考令牌", subtitle = if (english) "Context left" else "上下文余量", icon = WalletOutline16, value = "18.7K")
        }
        DshSection(header = if (english) "Status semantics" else "状态语义") {
            DshListRow(
                title = if (english) "Waiting for confirmation" else "等待确认",
                subtitle = if (english) "Permission request pending" else "权限申请待处理",
                leading = { DshStatusBadge(if (english) "Waiting" else "等待", tone = DshStatusTone.Waiting, dot = true) },
            )
            DshListRow(
                title = if (english) "Running" else "运行中",
                subtitle = if (english) "Running a tool call" else "正在执行工具调用",
                leading = { DshStatusBadge(if (english) "Running" else "运行", tone = DshStatusTone.Running, dot = true) },
            )
        }
    }
}

@PreviewTest
@Preview(name = "section flat tonal light zh", showBackground = true, widthDp = 412, heightDp = 760)
@Composable
internal fun SectionFlatTonalLightZh() {
    SectionWall(dark = false, english = false)
}

@PreviewTest
@Preview(name = "section flat tonal dark en", showBackground = true, widthDp = 412, heightDp = 760)
@Composable
internal fun SectionFlatTonalDarkEn() {
    SectionWall(dark = true, english = true)
}

/** 1.3 字号：标题 / 副标题 / 尾部值不得重叠或截断。 */
@PreviewTest
@Preview(name = "section flat tonal large", showBackground = true, widthDp = 412, heightDp = 760, fontScale = 1.3f)
@Composable
internal fun SectionFlatTonalLarge() {
    SectionWall(dark = false, english = false)
}

@PreviewTest
@Preview(name = "settings light zh", showBackground = true, widthDp = 412, heightDp = 1100)
@Composable
internal fun SettingsLightZh() {
    GroupedWall(dark = false, english = false) { SettingsHomeWall() }
}

@PreviewTest
@Preview(name = "settings dark en large", showBackground = true, widthDp = 412, heightDp = 1100, fontScale = 1.3f)
@Composable
internal fun SettingsDarkEnLarge() {
    GroupedWall(dark = true, english = true) { SettingsHomeWall() }
}

@PreviewTest
@Preview(name = "settings home paired light zh", showBackground = true, widthDp = 412, heightDp = 900)
@Composable
internal fun SettingsHomePairedLightZh() {
    GroupedWall(dark = false, english = false) {
        // 带上连通性样例值：电脑卡右侧的「● 在线 · 云端 · 31ms」要有截图证据
        SettingsHome(
            appSettings = AppSettings(),
            onOpen = {},
            host = PreviewHost,
            connectivity = dev.deeplinks.native.util.HostConnectivitySnapshot(online = true, viaRemote = true, latencyMs = 31),
        )
    }
}

/**
 * 设置页整壳（批次 2）：DshPageScaffold 标题 + 返回热区 + 页面内容，
 * 与任务首页 / 设备页同一骨架；内容走 SettingsPageCanvas。
 */
@PreviewTest
@Preview(name = "settings page shell light zh", showBackground = true, widthDp = 412, heightDp = 900)
@Composable
internal fun SettingsPageShellLightZh() {
    ShotFrame(dark = false, english = false) {
        DshPageScaffold(
            title = DshS.settingsTitle,
            navigation = DshPageNavigation.Back,
            onNavigateBack = {},
        ) {
            SettingsPageCanvas {
                SettingsHome(appSettings = AppSettings(), onOpen = { _: SettingsDest -> }, host = PreviewHost)
            }
        }
    }
}

@PreviewTest
@Preview(name = "settings appearance light zh", showBackground = true, widthDp = 412, heightDp = 980)
@Composable
internal fun SettingsAppearanceLightZh() {
    GroupedWall(dark = false, english = false) {
        AppearanceSettings(savingNs = null, saveErrors = emptyMap(), onSave = { _, _, _ -> })
    }
}

@PreviewTest
@Preview(name = "settings appearance dark en", showBackground = true, widthDp = 412, heightDp = 980)
@Composable
internal fun SettingsAppearanceDarkEn() {
    GroupedWall(dark = true, english = true) {
        AppearanceSettings(savingNs = null, saveErrors = emptyMap(), onSave = { _, _, _ -> })
    }
}

@PreviewTest
@Preview(name = "settings conversation light zh", showBackground = true, widthDp = 412, heightDp = 720)
@Composable
internal fun SettingsConversationLightZh() {
    GroupedWall(dark = false, english = false) {
        ConversationSettings(
            appSettings = AppSettings(),
            savingNs = null,
            saveErrors = mapOf("permission" to DshS.settingsChangedElsewhere),
            onShowFullAccessConfirm = {},
            onSave = { _, _, _ -> },
        )
    }
}

@PreviewTest
@Preview(name = "settings sessions light zh", showBackground = true, widthDp = 412, heightDp = 760)
@Composable
internal fun SettingsSessionsLightZh() {
    GroupedWall(dark = false, english = false) {
        SessionsSettingsContent(
            listKind = SessionListKind.Content,
            loadError = null,
            archivedRows = listOf(
                SessionSnapshot("a1", "重构设置页为分组列表", "/Users/me/dsh-links", 0L),
                SessionSnapshot("a2", "Relay 超时排查", "/Users/me/relay", 0L),
            ),
            deletedRows = emptyList(),
            onRetry = {},
            onRestore = {},
            onClear = {},
            onClearAll = {},
        )
    }
}

@PreviewTest
@Preview(name = "settings about dark en", showBackground = true, widthDp = 412, heightDp = 560)
@Composable
internal fun SettingsAboutDarkEn() {
    GroupedWall(dark = true, english = true) { AboutSettings(onOpenLegal = { _, _ -> }) }
}

internal val PreviewHost = Host(
    name = "MacBook Pro",
    baseUrl = "https://192.168.1.8:18640",
    token = "preview",
)

@Composable
private fun DevicesWall() {
    // 批次 3：设备页与设置同一骨架（DshPageScaffold），副标题降为页首导语
    DshPageScaffold(title = DshS.pairingManage) {
        Text(
            DshS.manageYourLinks,
            color = Dsh.labelTertiary,
            style = DshType.body,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
        )
        DeviceDetailSections(
            device = DeviceUi(PreviewHost, DeviceState.ONLINE, latencyMs = 24),
            notice = null,
            onOpen = {},
            onRecheck = {},
            onReplace = {},
            onUnpair = {},
        )
    }
}

@PreviewTest
@Preview(name = "devices light zh", showBackground = true, widthDp = 412, heightDp = 900)
@Composable
internal fun DevicesLightZh() {
    // F09：预览宿主 = 生产页面结构（ShotFrame + DshPageScaffold 直接组合），
    // 不再套 GroupedWall → SettingsPageCanvas 的滚动容器——嵌套滚动宿主在
    // Linux layoutlib 上渲染崩溃（UnsupportedClassVersionError 修复前的 ScreenshotRenderException）
    // 且双层水平边距会掩盖真实页面
    ShotFrame(dark = false, english = false) { DevicesWall() }
}

@PreviewTest
@Preview(name = "devices dark en", showBackground = true, widthDp = 412, heightDp = 900)
@Composable
internal fun DevicesDarkEn() {
    ShotFrame(dark = true, english = true) { DevicesWall() }
}

/** 建议行（继续 / 复核 / 查看改动 (N)）。 */
@Composable
private fun GlassControlsWall(english: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Dsh.bgBase)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle(if (english) "Composer suggestions (continue / review / view changes)" else "建议行（继续 / 复核 / 查看改动）")
        ComposerSuggestionsRow(
            online = true,
            suggestionsVisible = true,
            changesCount = null,
            onSuggestion = {},
            onOpenChanges = {},
        )
    }
}

@PreviewTest
@Preview(name = "chat suggestions light zh", showBackground = true, widthDp = 412, heightDp = 620)
@Composable
internal fun ChatSuggestionsLightZh() {
    Wall(dark = false, english = false) { GlassControlsWall(english = false) }
}

@PreviewTest
@Preview(name = "home bottom bar dark en", showBackground = true, widthDp = 412, heightDp = 620)
@Composable
internal fun HomeBottomBarDarkEn() {
    Wall(dark = true, english = true) { GlassControlsWall(english = true) }
}

// ===== v4 基础组件（R2.2）：每个组件浅色（中文）/ 深色（英文），各含常规、长文本、禁用态 =====

@Composable
private fun V4Wall(dark: Boolean, content: @Composable (en: Boolean) -> Unit) {
    ShotFrame(dark = dark, english = dark) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Dsh.bgBase)
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content(dark)
        }
    }
}

private fun pick(en: Boolean, zh: String, english: String) = if (en) english else zh

@Composable
private fun V4TopBarCases(en: Boolean) {
    DlTopBar(
        title = pick(en, "完善审批状态同步", "Sync approval state"),
        subtitle = pick(en, "dsh-links · 运行中 · 第 12 步", "dsh-links · Running · step 12"),
        diff = DlDiffStat(148, 37) {},
        actions = listOf(DlTopBarAction(EllipsisOutline16, "more", {})),
    )
    DlTopBar(
        title = "DeepLinks",
        subtitle = pick(en, "MacBook Pro · 在线", "MacBook Pro · Online"),
        nav = DlTopBarNav.None,
        large = true,
        actions = listOf(DlTopBarAction(SearchOutline16, "search", {}), DlTopBarAction(SettingsOutline16, "settings", {})),
    )
    DlTopBar(
        title = pick(en, "一个非常非常长的会话标题，用来检查标题在顶栏里会不会被截断显示", "A very very long session title that must be truncated inside the top bar"),
        subtitle = pick(en, "一个同样很长的副标题 · 工作区 · 状态 · 第 128 步", "An equally long subtitle · workspace · state · step 128"),
        nav = DlTopBarNav.Close,
        showDivider = true,
        actions = listOf(DlTopBarAction(ShareOutline16, "share", {}, enabled = false)),
    )
}

@PreviewTest
@Preview(name = "v4 top bar light zh", showBackground = true, widthDp = 412, heightDp = 260)
@Composable
internal fun V4TopBarLightZh() = V4Wall(dark = false) { V4TopBarCases(it) }

@PreviewTest
@Preview(name = "v4 top bar dark en", showBackground = true, widthDp = 412, heightDp = 260)
@Composable
internal fun V4TopBarDarkEn() = V4Wall(dark = true) { V4TopBarCases(it) }

@Composable
private fun V4ListRowCases(en: Boolean) {
    Column {
        DlListRow(pick(en, "语言", "Language"), leading = TranslateOutline16, trailing = DlRowTrailing.Value(pick(en, "简体中文", "English")), onClick = {})
        DlListRow(pick(en, "通知", "Notifications"), subtitle = pick(en, "审批、完成、失败", "Approvals, done, failed"), leading = InfoOutline16, trailing = DlRowTrailing.Chevron, onClick = {})
        DlListRow(pick(en, "允许在通知栏直接批准", "Approve from notification"), subtitle = pick(en, "不推荐：手机收不到完整参数", "Not recommended: the phone cannot see full arguments"), leading = WarningOutline16, leadingTint = DlTone.Wait, trailing = DlRowTrailing.Switch(true) {})
        DlListRow(pick(en, "跟随系统", "Follow system"), trailing = DlRowTrailing.Radio(true), onClick = {})
        DlListRow(pick(en, "深色", "Dark"), trailing = DlRowTrailing.Check(true), onClick = {})
        DlListRow(pick(en, "这是一个非常长的列表行标题，用来检查换行和尾部控件之间的距离是否合适", "A very long list row title used to check wrapping against the trailing control"), subtitle = pick(en, "副标题也很长，最多显示三行，超出部分用省略号结束，保证列表行高度可控。", "The subtitle is long too and wraps up to three lines before it is ellipsized."), leading = FileOutline16, trailing = DlRowTrailing.TextAction(pick(en, "重新检查", "Recheck")) {})
        DlListRow(pick(en, "远程中继", "Remote relay"), subtitle = pick(en, "未开启", "Off"), leading = GlobeOutline16, trailing = DlRowTrailing.Switch(false) {}, enabled = false)
        DlListRow(pick(en, "解除配对", "Unpair"), subtitle = pick(en, "同时从电脑端吊销本机", "Also revokes this phone on the computer"), leading = UnlinkOutline16, danger = true, onClick = {})
    }
}

@PreviewTest
@Preview(name = "v4 list row light zh", showBackground = true, widthDp = 412, heightDp = 640)
@Composable
internal fun V4ListRowLightZh() = V4Wall(dark = false) { V4ListRowCases(it) }

@PreviewTest
@Preview(name = "v4 list row dark en", showBackground = true, widthDp = 412, heightDp = 640)
@Composable
internal fun V4ListRowDarkEn() = V4Wall(dark = true) { V4ListRowCases(it) }

@Composable
private fun V4SectionHeaderCases(en: Boolean) {
    Column {
        DlSectionHeader(pick(en, "等你处理", "Needs you"), trailing = "2")
        DlSectionHeader(pick(en, "通用", "General"))
        DlSectionHeader(pick(en, "一个很长很长的分组标题，用来检查右侧文字按钮是否被挤掉", "A very long section title that must not push the trailing action out"), trailing = pick(en, "全部", "All"), onTrailingClick = {})
    }
}

@PreviewTest
@Preview(name = "v4 section header light zh", showBackground = true, widthDp = 412, heightDp = 200)
@Composable
internal fun V4SectionHeaderLightZh() = V4Wall(dark = false) { V4SectionHeaderCases(it) }

@PreviewTest
@Preview(name = "v4 section header dark en", showBackground = true, widthDp = 412, heightDp = 200)
@Composable
internal fun V4SectionHeaderDarkEn() = V4Wall(dark = true) { V4SectionHeaderCases(it) }

@Composable
private fun V4StatusSlotCases(en: Boolean) {
    DlStatusSlot(
        title = pick(en, "目标 · 第 3/8 轮 · 计划 4/7", "Goal · round 3/8 · plan 4/7"),
        meta = pick(en, "正在：补审批过期的单测", "Now: tests for expired approvals"),
        icon = GoalOutline16,
        onExpandedChange = {},
        expandedContent = {},
    )
    DlStatusSlot(
        title = pick(en, "把审批状态做成双向同步", "Make approval state sync both ways"),
        meta = pick(en, "进行中 · 第 3/8 轮", "In progress · round 3/8"),
        icon = GoalOutline16,
        expanded = true,
        onExpandedChange = {},
        expandedContent = {
            Text(pick(en, "定位事件只推给发起端", "Find why events only reach the origin"), style = DshType.supporting, color = Dsh.labelSecondary)
            Text(pick(en, "补审批过期的单测", "Add tests for expired approvals"), style = DshType.bodyStrong, color = Dsh.labelPrimary)
        },
    )
    DlStatusSlot(
        title = pick(en, "连接已断开，正在重连…", "Disconnected, reconnecting…"),
        meta = pick(en, "已尝试 3 次 · 局域网", "3 attempts · LAN"),
        icon = CloudOffOutline16,
        tone = DlTone.Err,
    )
    DlStatusSlot(
        title = pick(en, "等你批准：运行一个非常长的命令，标题只显示一行，超出部分用省略号结束", "Needs approval: a very long command whose title stays on a single line and is ellipsized"),
        icon = WarningOutline16,
        tone = DlTone.Wait,
    )
}

@PreviewTest
@Preview(name = "v4 status slot light zh", showBackground = true, widthDp = 412, heightDp = 420)
@Composable
internal fun V4StatusSlotLightZh() = V4Wall(dark = false) { V4StatusSlotCases(it) }

@PreviewTest
@Preview(name = "v4 status slot dark en", showBackground = true, widthDp = 412, heightDp = 420)
@Composable
internal fun V4StatusSlotDarkEn() = V4Wall(dark = true) { V4StatusSlotCases(it) }

@Composable
private fun V4InboxItemCases(en: Boolean) {
    Column {
        DlInboxItem(
            status = pick(en, "等你批准", "Needs approval"),
            tone = DlTone.Wait,
            workspace = "dsh-links",
            time = pick(en, "2 分钟", "2 min"),
            title = pick(en, "发布 beta.28 前跑一遍真机测试", "Run device tests before beta.28"),
            command = "./gradlew :app:connectedDebugAndroidTest",
            actions = listOf(
                DlAction(pick(en, "拒绝", "Deny"), {}),
                DlAction(pick(en, "允许一次", "Allow once"), {}, DlButtonStyle.Filled),
            ),
        )
        DlInboxItem(
            workspace = "dsh-links",
            time = pick(en, "3 分钟", "3 min"),
            title = pick(en, "完善审批状态同步", "Sync approval state"),
            preview = pick(en, "正在运行 go test ./... · 第 12 步", "Running go test ./... · step 12"),
            running = true,
        )
        DlInboxItem(
            status = pick(en, "完成", "Done"),
            tone = DlTone.Ok,
            workspace = pick(en, "一个名字特别长的工作区目录用来检查省略", "a-workspace-with-a-really-long-directory-name"),
            time = pick(en, "昨天", "Yesterday"),
            title = pick(en, "一个很长的会话标题，最多显示两行，超过两行的部分会用省略号结束，避免条目高度失控", "A long session title that wraps to at most two lines before it is ellipsized so the row height stays bounded"),
            preview = pick(en, "改了 4 个文件 · +62 −9", "Changed 4 files · +62 −9"),
        )
        DlInboxItem(
            status = pick(en, "等你回答", "Needs answer"),
            tone = DlTone.Wait,
            workspace = "relay",
            time = pick(en, "8 分钟", "8 min"),
            title = pick(en, "中继限流策略", "Relay rate limit"),
            preview = pick(en, "问：每台设备每分钟上限设成 60 还是 120？", "Q: 60 or 120 requests per device per minute?"),
            actions = listOf(DlAction(pick(en, "回答", "Answer"), {}, enabled = false)),
        )
    }
}

@PreviewTest
@Preview(name = "v4 inbox item light zh", showBackground = true, widthDp = 412, heightDp = 600)
@Composable
internal fun V4InboxItemLightZh() = V4Wall(dark = false) { V4InboxItemCases(it) }

@PreviewTest
@Preview(name = "v4 inbox item dark en", showBackground = true, widthDp = 412, heightDp = 600)
@Composable
internal fun V4InboxItemDarkEn() = V4Wall(dark = true) { V4InboxItemCases(it) }

@Composable
private fun V4ComposerCases(en: Boolean) {
    DlComposer(
        text = "",
        onTextChange = {},
        placeholder = pick(en, "补充说明，这一步结束后发给它", "Add a note; it is sent after this step"),
        sendState = DlSendState.Stop,
        onSend = {},
        onAttach = {},
        modelLabel = "step-5-preview · " + pick(en, "高", "High"),
        permissionLabel = pick(en, "工作区内修改", "Workspace write"),
    )
    DlComposer(
        text = pick(en, "这是一段很长的输入内容，用来检查输入框在多行时的高度和行距是否合适，以及发送按钮是否保持在右下角。", "A long draft used to check multi-line height and line spacing, and that the send button stays bottom-right."),
        onTextChange = {},
        placeholder = "",
        sendState = DlSendState.Send,
        onSend = {},
        onAttach = {},
        modelLabel = "deepseek-v4 · " + pick(en, "中", "Medium"),
        permissionLabel = pick(en, "完全权限", "Full access"),
        permissionRisk = true,
        attachments = {
            Box(Modifier.size(48.dp).background(Dsh.surface2, RoundedCornerShape(DshRadius.control)))
            Box(Modifier.size(48.dp).background(Dsh.surface2, RoundedCornerShape(DshRadius.control)))
        },
    )
    DlComposer(
        text = pick(en, "连上后再发送", "Send after reconnecting"),
        onTextChange = {},
        placeholder = "",
        sendState = DlSendState.Disabled,
        onSend = {},
        onAttach = {},
        modelLabel = "step-5-preview",
        permissionLabel = pick(en, "只读", "Read only"),
    )
    DlComposer(
        text = "",
        onTextChange = {},
        placeholder = pick(en, "给智能体发消息", "Message the agent"),
        sendState = DlSendState.Mic,
        onSend = {},
    )
}

@PreviewTest
@Preview(name = "v4 composer light zh", showBackground = true, widthDp = 412, heightDp = 560)
@Composable
internal fun V4ComposerLightZh() = V4Wall(dark = false) { V4ComposerCases(it) }

@PreviewTest
@Preview(name = "v4 composer dark en", showBackground = true, widthDp = 412, heightDp = 560)
@Composable
internal fun V4ComposerDarkEn() = V4Wall(dark = true) { V4ComposerCases(it) }

@Composable
private fun V4DecisionBarCases(en: Boolean) {
    DlDecisionBar(
        status = pick(en, "等你批准", "Needs approval"),
        meta = pick(en, "2 分钟前", "2 min ago"),
        question = pick(en, "要运行这个命令吗？", "Run this command?"),
        command = "./gradlew :app:connectedDebugAndroidTest --tests 'dev.deeplinks.architecture.*' --stacktrace",
        note = pick(en, "工作区 dsh-links · 在电脑上执行 · 当前权限：工作区内修改", "Workspace dsh-links · runs on the computer · permission: workspace write"),
        secondary = DlAction(pick(en, "拒绝", "Deny"), {}),
        primary = DlAction(pick(en, "允许一次", "Allow once"), {}),
    )
    DlDecisionBar(
        status = pick(en, "等你回答", "Needs answer"),
        meta = pick(en, "问题 1/2", "Question 1/2"),
        question = pick(en, "每台设备每分钟的请求上限设成多少？", "What should the per-device limit per minute be?"),
        options = listOf(
            DlDecisionOption(pick(en, "60 次（推荐，与现在的面板一致）", "60 (recommended, matches the panel)"), true, {}),
            DlDecisionOption(pick(en, "120 次", "120"), false, {}),
            DlDecisionOption(pick(en, "自己写答案", "Write my own answer"), false, {}, custom = true),
        ),
        secondary = DlAction(pick(en, "跳过", "Skip"), {}),
        primary = DlAction(pick(en, "下一题", "Next"), {}, enabled = false),
    )
}

@PreviewTest
@Preview(name = "v4 decision bar light zh", showBackground = true, widthDp = 412, heightDp = 680)
@Composable
internal fun V4DecisionBarLightZh() = V4Wall(dark = false) { V4DecisionBarCases(it) }

@PreviewTest
@Preview(name = "v4 decision bar dark en", showBackground = true, widthDp = 412, heightDp = 680)
@Composable
internal fun V4DecisionBarDarkEn() = V4Wall(dark = true) { V4DecisionBarCases(it) }

@Composable
private fun V4BottomSheetCases(en: Boolean) {
    Box(Modifier.fillMaxSize().background(Dsh.bgOverlay), contentAlignment = Alignment.BottomCenter) {
        DlBottomSheetSurface(
            title = pick(en, "模型与推理", "Model and reasoning"),
            subtitle = pick(en, "只影响这个会话；这是一个较长的副标题，用来检查换行", "Only affects this session; a longer subtitle to check wrapping"),
        ) {
            DlListRow("step-5-preview", subtitle = pick(en, "阶跃星辰 · 上下文 256K", "StepFun · 256K context"), trailing = DlRowTrailing.Radio(true), onClick = {})
            DlListRow("deepseek-v4", subtitle = pick(en, "DeepSeek 账户 · 上下文 128K", "DeepSeek account · 128K context"), trailing = DlRowTrailing.Radio(false), onClick = {})
            DlListRow("legacy-model", subtitle = pick(en, "不可用", "Unavailable"), trailing = DlRowTrailing.Radio(false), enabled = false, onClick = {})
        }
    }
}

@PreviewTest
@Preview(name = "v4 bottom sheet light zh", showBackground = true, widthDp = 412, heightDp = 480)
@Composable
internal fun V4BottomSheetLightZh() = V4Wall(dark = false) { V4BottomSheetCases(it) }

@PreviewTest
@Preview(name = "v4 bottom sheet dark en", showBackground = true, widthDp = 412, heightDp = 480)
@Composable
internal fun V4BottomSheetDarkEn() = V4Wall(dark = true) { V4BottomSheetCases(it) }

@Composable
private fun V4DialogCases(en: Boolean) {
    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DlDialogSurface(
            title = pick(en, "删除这个会话？", "Delete this session?"),
            text = pick(en, "会从手机列表移除「完善审批状态同步」，并在电脑端归档。30 天内可以在 设置 › 会话记录 里恢复。", "Removes \u201cSync approval state\u201d from the phone and archives it on the computer. Restore within 30 days in Settings › Sessions."),
            icon = TrashOutline16,
            dismiss = DlAction(pick(en, "取消", "Cancel"), {}),
            confirm = DlAction(pick(en, "删除", "Delete"), {}, DlButtonStyle.Danger),
        )
        DlDialogSurface(
            title = pick(en, "编辑目标", "Edit goal"),
            leading = DlAction(pick(en, "清除目标", "Clear goal"), {}, DlButtonStyle.Danger),
            dismiss = DlAction(pick(en, "取消", "Cancel"), {}),
            confirm = DlAction(pick(en, "保存", "Save"), {}, enabled = false),
            content = {
                Text(pick(en, "把审批状态做成双向同步，并补齐真机验证", "Make approval state sync both ways and verify on device"), style = DshType.body, color = Dsh.labelPrimary)
            },
        )
    }
}

@PreviewTest
@Preview(name = "v4 dialog light zh", showBackground = true, widthDp = 412, heightDp = 520)
@Composable
internal fun V4DialogLightZh() = V4Wall(dark = false) { V4DialogCases(it) }

@PreviewTest
@Preview(name = "v4 dialog dark en", showBackground = true, widthDp = 412, heightDp = 520)
@Composable
internal fun V4DialogDarkEn() = V4Wall(dark = true) { V4DialogCases(it) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun V4ChipCases(en: Boolean) {
    FlowRow(
        modifier = Modifier.padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DlChip(pick(en, "全部", "All"), {}, selected = true)
        DlChip("dsh-links", {}, selected = false)
        DlChip(pick(en, "跑一遍测试", "Run the tests"), {})
        DlChip(pick(en, "工作区内修改", "Workspace write"), {}, icon = ShieldOutline16, style = DlChipStyle.Filled)
        DlChip(pick(en, "完全权限", "Full access"), {}, icon = ShieldOutline16, tone = DlTone.Wait, style = DlChipStyle.Filled)
        DlChip(pick(en, "一个非常长的建议文字，用来检查 chip 的截断", "A very long suggestion used to check chip truncation"), {})
        DlChip(pick(en, "不可用", "Disabled"), {}, enabled = false)
    }
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DlSegmented(listOf(pick(en, "低", "Low"), pick(en, "中", "Medium"), pick(en, "高", "High")), 1, {})
        DlSegmented(listOf(pick(en, "浅色", "Light"), pick(en, "深色", "Dark"), pick(en, "跟随系统", "System")), 2, {}, enabled = false)
    }
}

@PreviewTest
@Preview(name = "v4 chip light zh", showBackground = true, widthDp = 412, heightDp = 320)
@Composable
internal fun V4ChipLightZh() = V4Wall(dark = false) { V4ChipCases(it) }

@PreviewTest
@Preview(name = "v4 chip dark en", showBackground = true, widthDp = 412, heightDp = 320)
@Composable
internal fun V4ChipDarkEn() = V4Wall(dark = true) { V4ChipCases(it) }
