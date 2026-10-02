package dev.deeplinks.native

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.core.ThemeManager
import dev.deeplinks.core.dshRipple
import dev.deeplinks.core.homeDone
import dev.deeplinks.core.homeQuestionPreview
import dev.deeplinks.core.homeWaitingAnswer
import dev.deeplinks.core.subagentRunning
import dev.deeplinks.native.ui.v4.DlPill
import dev.deeplinks.native.ui.v4.DlSpinner
import dev.deeplinks.native.ui.v4.DlTone

/** 首页条目里等你拍板的那一条是什么（只对当前会话里手机能处理的请求）。 */
internal enum class HomePendingKind { None, Approval, Question }

/**
 * 2.1 收件箱条目的文字（纯函数，便于单测）：
 * - [status]：等你批准 / 等你回答（等你色）、完成（成功色）、怎么停的（次要色）；进行中不写状态，靠转圈。
 * - [workspace]：工作区名（+ 子代理数）。
 * - [preview]：进行中写当前步骤（离线加「最后看到：」），结束写结果一句话，提问写「问：…」。
 * - [command]：审批的命令（等宽块）；拿不到参数时退回工具名。
 */
internal data class HomeInboxTexts(
    val status: String?,
    val tone: DlTone,
    val workspace: String,
    val preview: String?,
    val running: Boolean,
    val command: String? = null,
    val pending: HomePendingKind = HomePendingKind.None,
)

internal fun homeWorkspaceName(session: MobileSession): String? =
    session.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

internal fun homeInboxTexts(
    session: MobileSession,
    pending: MobileMessage?,
    goalSummary: String?,
    offline: Boolean = false,
): HomeInboxTexts {
    val s = L
    val workspace = listOfNotNull(
        homeWorkspaceName(session),
        session.subagentCount?.takeIf { it > 0 }?.let { s.subagentRunning.format(it) },
    ).joinToString(" · ")
    return when {
        pending?.role == "approval" -> HomeInboxTexts(
            status = s.homeChipWaitingApproval,
            tone = DlTone.Wait,
            workspace = workspace,
            preview = null,
            running = false,
            command = approvalCommand(pending.toolArgs) ?: pending.toolName?.takeIf { it.isNotBlank() },
            pending = HomePendingKind.Approval,
        )
        pending?.role == "question" -> HomeInboxTexts(
            status = s.homeWaitingAnswer,
            tone = DlTone.Wait,
            workspace = workspace,
            preview = homeQuestionText(pending)?.let { s.homeQuestionPreview.format(it) },
            running = false,
            pending = HomePendingKind.Question,
        )
        session.awaitingInput -> HomeInboxTexts(s.homeChipWaitingApproval, DlTone.Wait, workspace, preview = null, running = false)
        session.running -> {
            val body = homeRunningBody(session, goalSummary)
            HomeInboxTexts(
                status = null,
                tone = DlTone.Neutral,
                workspace = workspace,
                preview = if (offline) "${s.homeLastSeenPrefix}$body" else body,
                running = !offline,
            )
        }
        else -> HomeInboxTexts(
            status = session.stoppedReason?.let { stoppedReasonLabel(it) } ?: s.homeDone,
            tone = if (session.stoppedReason == null) DlTone.Ok else DlTone.Off,
            workspace = workspace,
            preview = session.lastResult?.text?.takeIf { it.isNotBlank() },
            running = false,
        )
    }
}

/** 进行中的那一句：工具（命令 · 第 N 步）/ 思考 / 写回复 / 目标 / 运行中。 */
internal fun homeRunningBody(session: MobileSession, goalSummary: String?): String {
    val s = L
    val activity = session.activity
    return when {
        activity?.isTool == true && !activity.label.isNullOrBlank() -> {
            val step = activity.step?.let { " · ${s.homeStepLabel.format(it)}" } ?: ""
            "${s.homeRunningInline.format(activity.label)}$step"
        }
        activity?.kind == "thinking" -> s.homeThinking
        activity?.kind == "writing" -> s.homeWriting
        !goalSummary.isNullOrBlank() -> goalSummary
        else -> s.runningStatus
    }
}

private fun homeQuestionText(msg: MobileMessage): String? =
    displayQuestionsOf(msg).firstOrNull()?.let { q -> q.prompt.ifBlank { q.header } }?.takeIf { it.isNotBlank() }
        ?: msg.text.takeIf { it.isNotBlank() }

/** 2.4 搜索框：容器色胶囊，打开即聚焦；有字时右侧 ×，检索中转圈。 */
@Composable
internal fun SidebarSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        modifier = modifier
            .heightIn(min = DshTouch.min)
            .clip(DlPill)
            .background(Dsh.surface1)
            .padding(start = DshSpace.s16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = DshType.body.copy(color = Dsh.labelPrimary),
            cursorBrush = SolidColor(Dsh.brand400),
            modifier = Modifier.weight(1f).focusRequester(focus),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(L.searchSessionsPlaceholder, color = Dsh.tertiaryText, style = DshType.body)
                    }
                    inner()
                }
            },
        )
        if (loading) {
            DlSpinner()
            Spacer(Modifier.width(DshSpace.s8))
        }
        if (value.isNotEmpty()) {
            SidebarIconAction(icon = CloseOutline16, contentDescription = L.clearSearch, onClick = onClear)
        } else {
            Spacer(Modifier.width(DshSpace.s16))
        }
    }
}

/** 侧栏小图标按钮（清除 / 折叠栏），热区与图标分开。 */
@Composable
internal fun SidebarIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: Dp = DshTouch.min,
    iconSize: Dp = DshIconSize.sm,
    active: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) Dsh.bgNavSelected else Color.Transparent)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            }
            .clickable(interactionSource = interaction, indication = dshRipple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (active) Dsh.labelPrimary else Dsh.labelSecondary,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** 折叠态：图标条（新会话 / 搜索 / 设置 / 设备 / 主题）。 */
@Composable
internal fun WorkspaceSidebarCollapsed(actions: WorkspaceSidebarActions) {
    val isDarkTheme = Dsh.isDark
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(vertical = DshSpace.s8),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SidebarIconAction(icon = PlusOutline16, contentDescription = L.newSession, onClick = { actions.onNewSession() })
        SidebarIconAction(icon = SearchOutline16, contentDescription = L.searchSessions, onClick = { actions.onToggleSearch() })
        Spacer(Modifier.weight(1f))
        SidebarIconAction(icon = SettingsOutline16, contentDescription = L.settingsTitle, onClick = { actions.onOpenSettings() })
        SidebarIconAction(icon = DevicesOutline16, contentDescription = L.deviceAndPairing, onClick = { actions.onOpenDevice() })
        SidebarIconAction(
            icon = if (isDarkTheme) LightOutline16 else DarkOutline16,
            contentDescription = if (isDarkTheme) L.switchToLight else L.switchToDark,
            onClick = { ThemeManager.toggleTheme(context, isDarkTheme) },
        )
        Spacer(Modifier.height(DshSpace.s4))
    }
}
