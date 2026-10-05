package dev.deeplinks.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import dev.deeplinks.core.Dsh
import dev.deeplinks.native.HomeComputerSheetContent
import dev.deeplinks.native.HomeEmptyStarters
import dev.deeplinks.native.HomeHeader
import dev.deeplinks.native.HomeInboxRow
import dev.deeplinks.native.HomeBottomBar
import dev.deeplinks.native.ui.v4.DlWorkspaceRow
import dev.deeplinks.native.HomeOfflineBanner
import dev.deeplinks.native.HomeSearchPage
import dev.deeplinks.native.HomeSessionSheetContent
import dev.deeplinks.native.HomeWorkspaceOption
import dev.deeplinks.native.MobileMessage
import dev.deeplinks.native.MobileSearchResult
import dev.deeplinks.native.MobileSession
import dev.deeplinks.native.MobileSessionActivity
import dev.deeplinks.native.MobileSessionResult
import dev.deeplinks.native.WorkspaceSidebarActions
import dev.deeplinks.native.ui.v4.DlBottomSheetSurface

private const val MINUTE = 60_000L

/** 时间都取「几分钟前」：相对时间跨天会变成「昨天 / 周几」，基线会随日期漂移。 */
private fun homeSession(
    id: String,
    title: String,
    cwd: String = "/Users/me/dsh-links",
    minutesAgo: Long = 0,
    running: Boolean = false,
    awaiting: Boolean = false,
    activity: MobileSessionActivity? = null,
    lastResult: MobileSessionResult? = null,
    stoppedReason: String? = null,
) = MobileSession(
    sessionId = id,
    title = title,
    updatedAt = if (minutesAgo > 0) System.currentTimeMillis() - minutesAgo * MINUTE else 0L,
    running = running,
    blank = false,
    cwd = cwd,
    agentPreset = null,
    awaitingInput = awaiting,
    activity = activity,
    lastResult = lastResult,
    stoppedReason = stoppedReason,
)

private val APPROVAL = MobileMessage(
    id = "a1",
    role = "approval",
    text = "",
    approvalId = "a-1",
    toolName = "bash",
    toolArgs = """{"command":"./gradlew :app:connectedDebugAndroidTest"}""",
)

private val NO_ACTIONS = WorkspaceSidebarActions(
    onOpenDevice = {}, onNewSession = {}, onSelectSession = {}, onRenameSession = {}, onArchiveSession = {},
    onDeleteSession = {}, onForkSession = {}, onCreateSessionIn = {}, onDeleteWorkspace = {}, onToggleSearch = {},
    onSearchQueryChange = {}, onClearSearch = {}, onRetrySearch = {}, onRetrySessions = {}, onAddWorkspace = {},
    onOpenSettings = {},
)

@Composable
private fun HomeCanvas(dark: Boolean, english: Boolean, online: Boolean = true, content: @Composable () -> Unit) {
    ShotFrame(dark = dark, english = english) {
        Box(Modifier.fillMaxSize().background(Dsh.bgBase)) {
            Column(Modifier.fillMaxSize()) {
                HomeHeader(hostName = "MacBook Pro", online = online, onOpenComputer = {}, onOpenSettings = {})
                Column(Modifier.weight(1f)) { content() }
                HomeBottomBar(online = online, onSearch = {}, onCreate = {})
            }
        }
    }
}

/** 2.1 / 2.3：文件夹分组、紧凑会话和折叠后的待处理提示。 */
@Composable
private fun InboxRows(english: Boolean, online: Boolean) {
    val awaiting = listOf(
        homeSession("s1", if (english) "Run device tests before beta.28" else "发布 beta.28 前跑一遍真机测试", minutesAgo = 2, awaiting = true),
        homeSession("s2", if (english) "Relay rate limit policy" else "中继限流策略", cwd = "/Users/me/relay", minutesAgo = 8, awaiting = true),
    )
    val running = listOf(
        homeSession(
            "s3", if (english) "Approval status sync" else "完善审批状态同步", minutesAgo = 3, running = true,
            activity = MobileSessionActivity(kind = "tool", label = "go test ./...", step = 12),
        ),
        homeSession("s4", if (english) "Structured fields for text UI" else "把文本反推 UI 改成结构化字段", minutesAgo = 21, running = true, activity = MobileSessionActivity(kind = "writing")),
    )
    val recent = listOf(
        homeSession("s5", if (english) "Fix goal round crash" else "修复 goal round 崩溃", minutesAgo = 40, lastResult = MobileSessionResult(text = if (english) "Gate run all green" else "门禁全绿")),
        homeSession("s6", if (english) "Interrupted during gate run" else "跑门禁时被中断", minutesAgo = 50, stoppedReason = "interrupted"),
    )
    val first = listOf(awaiting[0]) + running + recent
    DlWorkspaceRow("dsh-links", first.size, true, 1, running.size, online, {}, {})
    first.forEach { s ->
        HomeInboxRow(s, if (s.sessionId == "s1") APPROVAL else null, online, null, {}, {}, {}, {}, compact = true)
    }
    DlWorkspaceRow("relay", 1, false, 1, 0, online, {}, {})
}

@PreviewTest
@Preview(name = "home inbox light zh", showBackground = true, widthDp = 412, heightDp = 1000)
@Composable
internal fun HomeInboxLightZh() {
    HomeCanvas(dark = false, english = false) { InboxRows(english = false, online = true) }
}

@PreviewTest
@Preview(name = "home inbox dark en", showBackground = true, widthDp = 412, heightDp = 1000)
@Composable
internal fun HomeInboxDarkEn() {
    HomeCanvas(dark = true, english = true) { InboxRows(english = true, online = true) }
}

@PreviewTest
@Preview(name = "home empty v4 light zh", showBackground = true, widthDp = 412, heightDp = 860)
@Composable
internal fun HomeEmptyV4LightZh() {
    HomeCanvas(dark = false, english = false) { HomeEmptyStarters(onPick = {}) }
}

@PreviewTest
@Preview(name = "home empty v4 dark en", showBackground = true, widthDp = 412, heightDp = 860)
@Composable
internal fun HomeEmptyV4DarkEn() {
    HomeCanvas(dark = true, english = true) { HomeEmptyStarters(onPick = {}) }
}

@PreviewTest
@Preview(name = "home offline v4 light zh", showBackground = true, widthDp = 412, heightDp = 1100)
@Composable
internal fun HomeOfflineV4LightZh() {
    HomeCanvas(dark = false, english = false, online = false) {
        HomeOfflineBanner(hostName = "MacBook Pro", sinceLabel = "22:03", onRetry = {}, onDiagnose = {})
        InboxRows(english = false, online = false)
    }
}

@PreviewTest
@Preview(name = "home offline v4 dark en", showBackground = true, widthDp = 412, heightDp = 1100)
@Composable
internal fun HomeOfflineV4DarkEn() {
    HomeCanvas(dark = true, english = true, online = false) {
        HomeOfflineBanner(hostName = "MacBook Pro", sinceLabel = "22:03", onRetry = {}, onDiagnose = {})
        InboxRows(english = true, online = false)
    }
}

@Composable
private fun SearchWall(english: Boolean) {
    val needle = if (english) "approval" else "审批"
    val sessions = listOf(
        homeSession("t1", if (english) "Approval status sync" else "完善审批状态同步", minutesAgo = 3),
        homeSession("t2", if (english) "Risk review for approval in notifications" else "通知栏直接审批的风险评估", minutesAgo = 30),
        homeSession("c1", if (english) "Relay handshake rewrite" else "中继握手重写", cwd = "/Users/me/relay", minutesAgo = 45),
    )
    val snippet = if (english) "the phone never exposes approval ports for risky actions" else "手机端不提供审批端口这类高危操作"
    val noStatus: LazyListScope.(Boolean) -> Unit = {}
    Box(Modifier.fillMaxSize().background(Dsh.bgBase)) {
        HomeSearchPage(
            query = needle,
            needle = needle,
            loading = false,
            scoped = sessions,
            searchResults = listOf(MobileSearchResult("c1", snippet)),
            workspaces = listOf("/Users/me/dsh-links", "/Users/me/relay", "/Users/me/notion-sync"),
            activeWorkspace = null,
            onSelectWorkspace = {},
            onBack = {},
            actions = NO_ACTIONS,
            statusItems = noStatus,
        )
    }
}

@PreviewTest
@Preview(name = "home search light zh", showBackground = true, widthDp = 412, heightDp = 760)
@Composable
internal fun HomeSearchLightZh() {
    ShotFrame(dark = false, english = false) { SearchWall(english = false) }
}

@PreviewTest
@Preview(name = "home search dark en", showBackground = true, widthDp = 412, heightDp = 760)
@Composable
internal fun HomeSearchDarkEn() {
    ShotFrame(dark = true, english = true) { SearchWall(english = true) }
}

/** 2.5 电脑与工作区 + 2.6 长按会话：两张弹层的静态外观。 */
@Composable
private fun SheetsWall(english: Boolean) {
    Column(Modifier.fillMaxSize().background(Dsh.bgOverlay)) {
        DlBottomSheetSurface(title = if (english) "Computer & workspaces" else "电脑与工作区") {
            HomeComputerSheetContent(
                hostName = "MacBook Pro",
                online = true,
                viaRemote = false,
                offlineSinceLabel = null,
                workspaces = listOf(
                    HomeWorkspaceOption("/Users/me/dsh-links", "dsh-links", 4),
                    HomeWorkspaceOption("/Users/me/relay", "relay", 1),
                    HomeWorkspaceOption("/Users/me/notion-sync", "notion-sync", 1),
                ),
                onOpenDevice = {},
                onAddWorkspace = {},
                onOpenArchived = {},
                onDeleteWorkspace = {},
            )
        }
        Box(Modifier.padding(top = 16.dp)) {
            DlBottomSheetSurface {
                HomeSessionSheetContent(
                    session = homeSession("s3", if (english) "Approval status sync" else "完善审批状态同步"),
                    onRename = {},
                    onFork = {},
                    onShare = {},
                    onArchive = {},
                    onDelete = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "home sheets light zh", showBackground = true, widthDp = 412, heightDp = 1180)
@Composable
internal fun HomeSheetsLightZh() {
    ShotFrame(dark = false, english = false) { SheetsWall(english = false) }
}

@PreviewTest
@Preview(name = "home sheets dark en", showBackground = true, widthDp = 412, heightDp = 1180)
@Composable
internal fun HomeSheetsDarkEn() {
    ShotFrame(dark = true, english = true) { SheetsWall(english = true) }
}
