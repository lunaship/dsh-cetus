package dev.deeplinks.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import dev.deeplinks.native.util.HomeSection
import dev.deeplinks.native.HomeApprovalCard
import androidx.compose.ui.draw.alpha
import dev.deeplinks.native.HomeEmptyStarters
import dev.deeplinks.native.HomeOfflineCard
import dev.deeplinks.native.HomeSectionHeader
import dev.deeplinks.native.HomeHeader
import dev.deeplinks.native.HomeBottomBar
import dev.deeplinks.native.SparkleOutline16
import dev.deeplinks.native.ArchiveBoxOutline16
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import dev.deeplinks.native.SettingsOutline16
import dev.deeplinks.native.PlusOutline16
import dev.deeplinks.native.CloseOutline16
import dev.deeplinks.native.EllipsisOutline16
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
import dev.deeplinks.native.ArrowLeftOutline16
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
import androidx.compose.foundation.layout.height
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
import dev.deeplinks.core.DshFontFamily
import dev.deeplinks.core.DshStringsEn
import dev.deeplinks.core.DshStringsZh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.LightDshColors
import dev.deeplinks.core.pureBlack
import dev.deeplinks.core.DshS
import dev.deeplinks.core.LocalDshColors
import dev.deeplinks.core.LocalDshFontFamily
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
import dev.deeplinks.native.StopFill16
import dev.deeplinks.native.CloudOffOutline16
import dev.deeplinks.native.DocumentCheckOutline16
import dev.deeplinks.native.ListOutline16
import dev.deeplinks.native.LockOutline16
import dev.deeplinks.native.UploadOutline16
import dev.deeplinks.native.ui.DshCardDivider
import dev.deeplinks.native.ui.DshChipTone
import dev.deeplinks.native.ui.DshGroupCard
import dev.deeplinks.native.ui.DshPillButton
import dev.deeplinks.native.ui.DshPillTone
import dev.deeplinks.native.ui.DshSectionLabel
import dev.deeplinks.native.ui.DshStatusChip
import dev.deeplinks.native.ui.DshStatusIcon
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
import dev.deeplinks.native.DshMenuItem
import dev.deeplinks.native.SearchOutline16
import dev.deeplinks.native.MobileSession
import dev.deeplinks.native.MobileSessionActivity
import dev.deeplinks.native.MobileSessionResult
import dev.deeplinks.native.MobileSessionStats
import dev.deeplinks.native.SessionRowItem
import dev.deeplinks.native.ui.DshCardRows
import dev.deeplinks.native.SessionStatsDetailDialog
import dev.deeplinks.native.WorkspaceTopBar
import dev.deeplinks.native.chatEmptyCanvas
import dev.deeplinks.native.newTaskDraftCanvas
import dev.deeplinks.native.DraftLastTask
import dev.deeplinks.native.ComposerSeatsRow
import dev.deeplinks.native.StreamReconnectBanner
import dev.deeplinks.native.ui.ChatLoadingSkeleton
import dev.deeplinks.native.util.ChatCanvasKind
import dev.deeplinks.native.util.StreamBannerKind
import dev.deeplinks.native.ui.DshBadge
import dev.deeplinks.native.ui.DshBanner
import dev.deeplinks.native.ui.DshBannerTone
import dev.deeplinks.native.COMPOSER_ISLAND_INNER_CLEARANCE
import dev.deeplinks.native.COMPOSER_SIDE_CLEARANCE
import dev.deeplinks.native.DshRadius
import dev.deeplinks.native.DshSpace
import dev.deeplinks.native.ui.DshFilterChip
import dev.deeplinks.native.ui.DshGlassTier
import dev.deeplinks.native.ui.dshGlass
import dev.deeplinks.native.ui.DshTag
import dev.deeplinks.native.ui.DshTextTabs

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
private fun ShotFrame(dark: Boolean, english: Boolean = false, content: @Composable () -> Unit) {
    // E1：直接读全局 LocaleManager.strings 的组件（首页副标题、审批卡、时间标签等）
    // 不会因为注入本地 LocalDshStrings 而变语言；渲染环境（layoutlib）没有可用的
    // SharedPreferences，所以走 setLanguageForPreview 只切内存态，英文预览不混中文。
    LocaleManager.setLanguageForPreview(if (english) "en" else "zh")
    val colors = if (dark) DarkDshColors else LightDshColors
    val typography = dshTypography(DshFontFamily)
    MaterialTheme(typography = typography) {
        CompositionLocalProvider(
            LocalDshColors provides colors,
            LocalDshStrings provides if (english) DshStringsEn else DshStringsZh,
            LocalDshFontFamily provides DshFontFamily,
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
    SectionTitle(if (english) "Status icons — done / running / offline / needs unlock"
        else "Status icons — 完成 / 进行中 / 离线 / 需解锁")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        DshStatusIcon(DocumentCheckOutline16)
        DshStatusIcon(ClockOutline16, container = Dsh.bgSubtle, content = Dsh.labelSecondary)
        DshStatusIcon(CloudOffOutline16, container = Dsh.bgSubtle, content = Dsh.labelTertiary)
        DshStatusIcon(LockOutline16, container = Dsh.bgSubtle, content = Dsh.warnLabel)
        DshStatusIcon(UploadOutline16, container = Dsh.brandTint, content = Dsh.brand500)
    }
    SectionTitle(if (english) "Group card — white card + 32dp status rows + divider"
        else "Group card — 白色分组卡 + 32dp 状态圈行 + 分隔线")
    DshGroupCard {
        DshListRow(
            title = if (english) "Approval status sync" else "完善审批状态同步",
            subtitle = if (english) "Running go test ./... · step 12" else "正在运行 go test ./... · 第 12 步",
            value = if (english) "3 min" else "3 分钟",
            iconSlot = 32.dp,
        )
        DshCardDivider()
        DshListRow(
            title = "2026-09-27_DSH-L",
            subtitle = if (english) "Done · changed 79 files, gate all green" else "完成 · 改了 79 个文件，门禁全绿",
            value = if (english) "Yesterday" else "昨天",
            leading = { DshStatusIcon(DocumentCheckOutline16) },
            iconSlot = 32.dp,
        )
        DshCardDivider()
        DshListRow(
            title = if (english) "Fix mobile model switch" else "修复手机模型切换",
            subtitle = if (english) "Stopped · you interrupted this turn" else "已停止 · 你中断了这一轮",
            value = if (english) "Thu" else "周四",
            leading = {
                DshStatusIcon(
                    StopFill16,
                    container = Dsh.bgSubtle,
                    content = Dsh.labelSecondary,
                    iconSize = 16.dp,
                )
            },
            iconSlot = 32.dp,
        )
    }
    SectionTitle(if (english) "Section label" else "分组标签")
    DshSectionLabel(if (english) "Waiting for you" else "等你处理")
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
    val typography = dshTypography(DshFontFamily)
    MaterialTheme(typography = typography) {
        CompositionLocalProvider(
            LocalDshColors provides colors,
            LocalDshStrings provides DshStringsZh,
            LocalDshFontFamily provides DshFontFamily,
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
    StreamReconnectBanner(kind = StreamBannerKind.Connecting, onRetry = {})
    StreamReconnectBanner(kind = StreamBannerKind.Failed, onRetry = {})
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
 * 对话页输入区：上下文条 + 输入卡（两层输入区），按生产结构包进浮岛（R11）。
 * 预览不带采样源（backdrop = null），展示的正是 4.5 回退表「无背景源 → 普通表面」
 * 的形态；玻璃折射/模糊的运行时效果由真机录屏验证（D04）。
 * 真机上「没有消息」的画布下方就是它——空会话的起点是输入框占位句。
 * ChatBottomWall 与空态画布帧共用，样例数据（含上下文占用）保持单一来源。
 */
@Composable
private fun ChatComposerArea(modifier: Modifier = Modifier) {
    val stats = sampleSessionStats
    Column(
        modifier = modifier
            .padding(horizontal = COMPOSER_SIDE_CLEARANCE)
            .dshGlass(
                tier = DshGlassTier.Floating,
                backdrop = null,
                shape = RoundedCornerShape(DshRadius.modal),
            )
            .padding(
                start = COMPOSER_ISLAND_INNER_CLEARANCE,
                end = COMPOSER_ISLAND_INNER_CLEARANCE,
                top = DshSpace.s6,
            ),
    ) {
        ComposerContextStrip(
            online = true,
            changes = WorkspaceChangesSummary(seq = 1, turn = 3, total = 6, added = 250, deleted = 50, files = emptyList()),
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
        running = true,
        title = if (english) "Research t3code mobile design" else "调研 t3code 移动端设计并对比项目",
        subtitle = "dsh-links · Mac mini",
        showBack = true,
        onNavigate = {},
        viewMode = "chat",
        showViewModeTabs = true,
        onSelectViewMode = {},
        menuExpanded = false,
        onMenuExpandedChange = {},
        menuItems = listOf(
            DshMenuItem(SearchOutline16, if (english) "Search tool calls" else "搜索工具调用") {},
            DshMenuItem(SearchOutline16, if (english) "Rename session" else "重命名会话") {},
        ),
    )
}

@PreviewTest
@Preview(name = "workspace top bar light", showBackground = true, widthDp = 412, heightDp = 140)
@Composable
internal fun WorkspaceTopBarLight() {
    Wall(dark = false, english = false) { TopBarWall(english = false) }
}

@PreviewTest
@Preview(name = "session usage light zh", showBackground = true, widthDp = 412, heightDp = 980)
@Composable
internal fun SessionUsageLightZh() {
    SessionUsageWall(dark = false, english = false)
}

@PreviewTest
@Preview(name = "session usage dark en", showBackground = true, widthDp = 412, heightDp = 980)
@Composable
internal fun SessionUsageDarkEn() {
    SessionUsageWall(dark = true, english = true)
}

@Composable
private fun SessionUsageWall(dark: Boolean, english: Boolean) {
    Wall(dark = dark, english = english) {
        SessionStatsDetailDialog(stats = sampleSessionStats, onDismiss = {})
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

// ---- 第 3 步：首页审批卡（C1 Outline 按钮）/ 设置隐私分组预览 ----

@PreviewTest
@Preview(name = "home approval card", showBackground = true, widthDp = 412, heightDp = 260)
@Composable
internal fun HomeApprovalCardLight() {
    ShotFrame(dark = false) {
        HomeApprovalCard(
            title = "任务首页改版",
            workspaceLabel = "dsh-links",
            timeLabel = "2 分钟前",
            toolName = "./gradlew :app:connectedDebugAndroidTest",
            onReject = {},
            onApprove = {},
        )
    }
}

@PreviewTest
@Preview(name = "home approval card dark en", showBackground = true, widthDp = 412, heightDp = 260)
@Composable
internal fun HomeApprovalCardDarkEn() {
    ShotFrame(dark = true, english = true) {
        HomeApprovalCard(
            title = "Redesign home inbox",
            workspaceLabel = "dsh-links",
            timeLabel = "2 min ago",
            toolName = "./gradlew :app:connectedDebugAndroidTest",
            onReject = {},
            onApprove = {},
        )
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

/**
 * 新任务草稿态（F09 补齐）：生产结构 [newTaskDraftCanvas]（对话页 LazyColumn 的
 * messages.isEmpty() 分支）+ 固定业务状态——继续上次 / 工作区胶囊 / 智能体预设。
 */
@Composable
private fun NewTaskDraftWall(english: Boolean) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        newTaskDraftCanvas(
            lastTask = DraftLastTask(
                sessionId = "preview",
                title = if (english) "Refactor settings into grouped list" else "重构设置页为分组列表",
                workspaceLabel = "/Users/me/dsh-links",
            ),
            workspaces = listOf("/Users/me/dsh-links", "/Users/me/relay"),
            selectedWorkspace = "/Users/me/dsh-links",
            modeLabel = if (english) "Balanced" else "均衡",
            onOpenLastTask = {},
            onSelectWorkspace = {},
            onOpenWorkspacePicker = {},
            onOpenModePicker = {},
        )
    }
}

@PreviewTest
@Preview(name = "new task draft light zh", showBackground = true, widthDp = 412, heightDp = 900)
@Composable
internal fun NewTaskDraftLightZh() {
    ShotFrame(dark = false, english = false) { NewTaskDraftWall(english = false) }
}

@PreviewTest
@Preview(name = "new task draft dark en", showBackground = true, widthDp = 412, heightDp = 900)
@Composable
internal fun NewTaskDraftDarkEn() {
    ShotFrame(dark = true, english = true) { NewTaskDraftWall(english = true) }
}

/**
 * 首页（任务中心）墙：顶栏 → 工作区筛选条 → 等待确认 / 进行中 / 今天 / 昨天 → 开始新任务。
 * 会话时间都给 0，避免相对时间随时钟漂移导致基线抖动。
 */
@Composable
private fun SidebarWall(english: Boolean = false) {
    fun session(
        id: String,
        title: String,
        running: Boolean = false,
        awaiting: Boolean = false,
        activity: MobileSessionActivity? = null,
        lastResult: MobileSessionResult? = null,
        stoppedReason: String? = null,
    ) = MobileSession(
        sessionId = id,
        title = title,
        // 真实一点的更新时间：进行中的行要有「3 分钟」这类已运行时长（方案 3.5 要求行尾有时长），
        // 最近的行要有「昨天 / 周五」。全填 0L 会让墙上看不到任何时间，掩盖真实问题。
        updatedAt = System.currentTimeMillis() - if (running) 3L * 60_000 else 26L * 3_600_000,
        stoppedReason = stoppedReason,
        running = running,
        blank = false,
        cwd = "/Users/me/dsh-links",
        agentPreset = null,
        awaitingInput = awaiting,
        activity = activity,
        lastResult = lastResult,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Dsh.bgBase)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HomeHeader(
            online = true,
            offlineSinceLabel = null,
            workspaces = listOf("/Users/me/dsh-links", "/Users/me/Hermes-perch"),
            selectedWorkspace = null,
            onSelectWorkspace = {},
            onAddWorkspace = {},
            onDeleteWorkspace = {},
            onOpenArchived = {},
            onOpenSettings = {},
            backdrop = null,
        )
        // 2026-10-02 简化：分区内容进白色分组卡（DshCardRows）
        HomeSectionHeader(HomeSection.AWAITING)
        DshCardRows {
            // 稿 07：最早一件展开成审批行（手机已接管），其余收成行
            HomeApprovalCard(
                title = if (english) "Home inbox redesign" else "任务首页改版",
                workspaceLabel = "dsh-links",
                timeLabel = if (english) "2 min ago" else "2 分钟前",
                toolName = "./gradlew :app:connectedDebugAndroidTest",
                onReject = {},
                onApprove = {},
            )
            SessionRowItem(session("s5", if (english) "Relay deploy check" else "Relay 部署检查", running = true, awaiting = true), isSelected = false, onClick = {}, onRename = {}, onFork = {})
        }
        HomeSectionHeader(HomeSection.RUNNING)
        DshCardRows {
            SessionRowItem(session("s2", if (english) "Approval status sync" else "完善审批状态同步", running = true, activity = MobileSessionActivity(kind = "tool", label = "go test ./...", step = 12)), isSelected = false, onClick = {}, onRename = {}, onFork = {})
        }
        HomeSectionHeader(HomeSection.RECENT)
        // 「最近」：元信息行有工作区与停止原因，结果一句话在标题下第二行（L7 文件数只认改动卡）
        DshCardRows {
            SessionRowItem(session("r0", if (english) "Interrupted during gate run" else "跑门禁时被中断", stoppedReason = "interrupted"), isSelected = false, onClick = {}, onRename = {}, onFork = {})
            SessionRowItem(session("s3", if (english) "Fix mobile model switch" else "修复手机模型切换", lastResult = MobileSessionResult(text = if (english) "You interrupted this turn" else "你中断了这一轮")), isSelected = false, onClick = {}, onRename = {}, onFork = {})
            SessionRowItem(session("s4", if (english) "Tidy workspace navigation" else "整理工作区导航", lastResult = MobileSessionResult(text = if (english) "Gate run all green" else "门禁全绿", files = 6)), isSelected = false, onClick = {}, onRename = {}, onFork = {})
            SessionRowItem(session("s6", if (english) "Fill in mobile tests" else "补齐移动端测试", lastResult = MobileSessionResult(text = if (english) "Added 3 test cases" else "补了 3 个用例", files = 3)), isSelected = false, onClick = {}, onRename = {}, onFork = {})
        }
        Spacer(Modifier.height(12.dp))
        HomeBottomBar(online = true, onOpenSearch = {}, onNewTask = {}, backdrop = null)
    }
}

/**
 * 离线墙（稿 08）：顶栏空心灰点 + 「离线 · N 分钟前在线」、重连卡顶掉概况行、
 * 列表 72% 不透明、进行中行换成静止时钟 + 「最后看到：」、新任务置灰。
 */
@Composable
private fun HomeOfflineWall(english: Boolean = false) {
    val session = MobileSession(
        sessionId = "s2",
        title = if (english) "Approval status sync" else "完善审批状态同步",
        updatedAt = 0L,
        running = true,
        blank = false,
        cwd = "/Users/me/dsh-links",
        agentPreset = null,
        activity = MobileSessionActivity(kind = "tool", label = "go test ./...", step = 12),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Dsh.bgBase)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HomeHeader(
            online = false,
            offlineSinceLabel = if (english) "10 min ago" else "10 分钟前",
            workspaces = listOf("/Users/me/dsh-links"),
            selectedWorkspace = null,
            onSelectWorkspace = {},
            onAddWorkspace = {},
            onDeleteWorkspace = {},
            onOpenArchived = {},
            onOpenSettings = {},
            backdrop = null,
        )
        HomeOfflineCard(hostName = "Mac mini", sinceLabel = if (english) "10 min ago" else "10 分钟前", onRetry = {}, onOpenConnectionMode = {})
        HomeSectionHeader(HomeSection.RUNNING)
        Box(Modifier.alpha(0.72f)) {
            DshCardRows {
                SessionRowItem(
                    session = session,
                    isSelected = false,
                    onClick = {},
                    onRename = {},
                    onFork = {},
                    offline = true,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.alpha(0.72f)) { HomeBottomBar(online = false, onOpenSearch = {}, onNewTask = {}, backdrop = null) }
    }
}

/** 空态墙（稿 09）：没有要你处理的事 + 三行起手式 + 悬浮新任务。 */
@Composable
private fun HomeEmptyWall() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Dsh.bgBase)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HomeHeader(
            online = true,
            offlineSinceLabel = null,
            workspaces = emptyList(),
            selectedWorkspace = null,
            onSelectWorkspace = {},
            onAddWorkspace = {},
            onDeleteWorkspace = {},
            onOpenArchived = {},
            onOpenSettings = {},
            backdrop = null,
        )
        // 空态不画概况行（稿 09 没有「0 件等你处理」）
        Spacer(Modifier.height(12.dp))
        HomeEmptyStarters(onPick = {})
        Spacer(Modifier.height(12.dp))
        HomeBottomBar(online = true, onOpenSearch = {}, onNewTask = {}, backdrop = null)
    }
}

@PreviewTest
@Preview(name = "home offline light zh", showBackground = true, widthDp = 412, heightDp = 620)
@Composable
internal fun HomeOfflineLightZh() {
    Wall(dark = false, english = false) { HomeOfflineWall(english = false) }
}

@PreviewTest
@Preview(name = "home offline dark en", showBackground = true, widthDp = 412, heightDp = 620)
@Composable
internal fun HomeOfflineDarkEn() {
    Wall(dark = true, english = true) { HomeOfflineWall(english = true) }
}

@PreviewTest
@Preview(name = "home empty light zh", showBackground = true, widthDp = 412, heightDp = 620)
@Composable
internal fun HomeEmptyLightZh() {
    Wall(dark = false, english = false) { HomeEmptyWall() }
}

@PreviewTest
@Preview(name = "home empty dark en", showBackground = true, widthDp = 412, heightDp = 620)
@Composable
internal fun HomeEmptyDarkEn() {
    Wall(dark = true, english = true) { HomeEmptyWall() }
}

@PreviewTest
@Preview(name = "sidebar light zh", showBackground = true, widthDp = 412, heightDp = 980)
@Composable
internal fun SidebarLightZh() {
    Wall(dark = false, english = false) { SidebarWall(english = false) }
}

@PreviewTest
@Preview(name = "sidebar dark en", showBackground = true, widthDp = 412, heightDp = 980)
@Composable
internal fun SidebarDarkEn() {
    Wall(dark = true, english = true) { SidebarWall(english = true) }
}
