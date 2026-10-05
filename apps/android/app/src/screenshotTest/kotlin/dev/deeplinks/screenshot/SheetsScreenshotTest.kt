package dev.deeplinks.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.native.AttachSheetContent
import dev.deeplinks.native.CommandSuggestions
import dev.deeplinks.native.ConversationShareContent
import dev.deeplinks.native.ConversationSharePreview
import dev.deeplinks.native.DshDialogButtons
import dev.deeplinks.native.DshDialogMessage
import dev.deeplinks.native.DshDialogTitle
import dev.deeplinks.native.DshRadius
import dev.deeplinks.native.FolderOpenOutline16
import dev.deeplinks.native.MobileModelCatalog
import dev.deeplinks.native.MobileModelGroup
import dev.deeplinks.native.MobileModelOption
import dev.deeplinks.native.ModelPickerContent
import dev.deeplinks.native.PermissionPickerContent
import dev.deeplinks.native.SelectTextPage
import dev.deeplinks.native.ShieldOutline16
import dev.deeplinks.native.SubagentNode
import dev.deeplinks.native.SubagentTree
import dev.deeplinks.native.TrashOutline16
import dev.deeplinks.native.ui.v4.DlBottomSheetSurface
import dev.deeplinks.native.ui.v4.DlOverlayColor
import dev.deeplinks.native.ui.v4.DlTextField
import dev.deeplinks.native.ui.v4.DlTone

private fun pick(en: Boolean, zh: String, english: String) = if (en) english else zh

private val sampleCatalog = MobileModelCatalog(
    currentProvider = "stepfun",
    currentModel = "step-5-preview",
    currentReasoningEffort = "high",
    groups = listOf(
        MobileModelGroup(
            "stepfun",
            "StepFun",
            listOf(
                MobileModelOption("step-5-preview", "step-5-preview", 256_000, null, listOf("low", "medium", "high"), "medium"),
                MobileModelOption("step-3.7-flash", "step-3.7-flash", 128_000, null),
            ),
        ),
        MobileModelGroup("deepseek", "DeepSeek", listOf(MobileModelOption("deepseek-v3.2", "deepseek-v3.2", 128_000, null))),
    ),
)

@Composable
private fun SheetGap() = Spacer(Modifier.height(16.dp))

/** 5.2 / 5.3 / 5.5 / 5.8：模型、权限、附件、子代理四张弹层。 */
@Composable
private fun SheetsWallA(en: Boolean) {
    Column(Modifier.fillMaxSize().background(Dsh.bgOverlay)) {
        DlBottomSheetSurface(title = DshS.modelAndEffort) {
            ModelPickerContent(sampleCatalog, loading = false, error = null, contextPercent = 46, onRetry = {}, onSelect = { _, _, _ -> })
        }
        SheetGap()
        DlBottomSheetSurface(title = DshS.accessMode) {
            PermissionPickerContent(
                selected = "workspace-write",
                saving = false,
                error = null,
                footnote = pick(en, "只影响当前会话。新会话的默认权限在 设置 · 对话 里改。", "Only affects this session. Change the default for new sessions in Settings · Conversation."),
                onSelect = {},
                onRetry = {},
            )
        }
        SheetGap()
        DlBottomSheetSurface(title = pick(en, "添加附件", "Add attachment")) {
            AttachSheetContent(DshS.permWorkspaceWrite, FolderOpenOutline16, {}, {}, {})
        }
        SheetGap()
        DlBottomSheetSurface(title = DshS.subagents) {
            SubagentTree(
                nodes = listOf(
                    SubagentNode("a", pick(en, "Kuhn · 检查插件广播路径", "Kuhn · check plugin broadcast path"), running = true),
                    SubagentNode(
                        "b",
                        pick(en, "Bacon · 审查 App 订阅逻辑", "Bacon · review app subscription logic"),
                        running = true,
                        children = listOf(SubagentNode("c", pick(en, "Erdos · 复核测试覆盖", "Erdos · recheck test coverage"), running = false)),
                    ),
                ),
                onSelect = {},
            )
        }
    }
}

/** 5.9 / 5.1：分享、指令面板（定时任务弹层已从手机端删除）。 */
@Composable
private fun SheetsWallB(en: Boolean) {
    Column(Modifier.fillMaxSize().background(Dsh.bgOverlay)) {
        DlBottomSheetSurface(title = DshS.shareConversation) {
            ConversationShareContent(
                preview = ConversationSharePreview(
                    title = pick(en, "完善审批状态同步", "Polish approval state sync"),
                    meta = pick(en, "dsh-links · 12 轮", "dsh-links · 12 turns"),
                    excerpt = pick(
                        en,
                        "插件在任一端处理审批后广播 approval.resolved，App 收到后把对应条目标为已处理，补了 3 个单测，全部通过。",
                        "The plugin broadcasts approval.resolved after either side handles it; the app marks the item handled. Added 3 unit tests, all passing.",
                    ),
                ),
                onShareImage = {},
                onExportText = {},
            )
        }
        SheetGap()
        CommandSuggestions(query = "/", onPick = {})
    }
}

@Composable
private fun DialogCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 32.dp)
            .fillMaxWidth()
            .background(DlOverlayColor, RoundedCornerShape(DshRadius.modal))
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
    ) { content() }
}

/** 5.4 / 5.10 / 5.11 / 5.13：完全权限确认、重命名、删除、编辑目标。 */
@Composable
private fun DialogsWall(en: Boolean) {
    Column(
        Modifier.fillMaxSize().background(Dsh.bgOverlay).padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        DialogCard {
            DshDialogTitle(DshS.confirmFullAccessTitle, ShieldOutline16, DlTone.Wait)
            DshDialogMessage(DshS.confirmFullAccessMessage)
            DshDialogButtons(dismissLabel = DshS.cancel, onDismiss = {}, confirmLabel = DshS.confirm, danger = true)
        }
        DialogCard {
            DshDialogTitle(DshS.renameSession)
            Spacer(Modifier.height(16.dp))
            DlTextField(value = pick(en, "审批状态双向同步", "Two-way approval sync"), onValueChange = {}, label = pick(en, "名称", "Name"))
            DshDialogButtons(dismissLabel = DshS.cancel, onDismiss = {}, confirmLabel = DshS.save)
        }
        DialogCard {
            DshDialogTitle(DshS.deleteSessionTitle, TrashOutline16)
            DshDialogMessage(DshS.deleteSessionMessage.format(pick(en, "完善审批状态同步", "Polish approval state sync")))
            DshDialogButtons(dismissLabel = DshS.cancel, onDismiss = {}, confirmLabel = DshS.delete, danger = true)
        }
        DialogCard {
            DshDialogTitle(pick(en, "编辑目标", "Edit goal"))
            Spacer(Modifier.height(16.dp))
            DlTextField(
                value = pick(en, "把审批状态做成双向同步，并补齐真机验证", "Make approval state sync both ways and verify on device"),
                onValueChange = {},
                label = pick(en, "目标内容", "Objective"),
                singleLine = false,
                minLines = 2,
            )
            Spacer(Modifier.height(12.dp))
            DlTextField(value = "8", onValueChange = {}, label = pick(en, "最多轮数（留空不限）", "Max rounds (blank = unlimited)"))
            DshDialogButtons(
                dismissLabel = DshS.cancel,
                onDismiss = {},
                confirmLabel = DshS.save,
                leadingLabel = pick(en, "清除目标", "Clear goal"),
            )
        }
    }
}

@PreviewTest
@Preview(name = "sheets a light zh", showBackground = true, widthDp = 412, heightDp = 1500)
@Composable
internal fun SheetsALightZh() = ShotFrame(dark = false, english = false) { SheetsWallA(en = false) }

@PreviewTest
@Preview(name = "sheets a dark en", showBackground = true, widthDp = 412, heightDp = 1500)
@Composable
internal fun SheetsADarkEn() = ShotFrame(dark = true, english = true) { SheetsWallA(en = true) }

@PreviewTest
@Preview(name = "sheets b light zh", showBackground = true, widthDp = 412, heightDp = 1300)
@Composable
internal fun SheetsBLightZh() = ShotFrame(dark = false, english = false) { SheetsWallB(en = false) }

@PreviewTest
@Preview(name = "sheets b dark en", showBackground = true, widthDp = 412, heightDp = 1300)
@Composable
internal fun SheetsBDarkEn() = ShotFrame(dark = true, english = true) { SheetsWallB(en = true) }

@PreviewTest
@Preview(name = "dialogs light zh", showBackground = true, widthDp = 412, heightDp = 1100)
@Composable
internal fun DialogsLightZh() = ShotFrame(dark = false, english = false) { DialogsWall(en = false) }

@PreviewTest
@Preview(name = "dialogs dark en", showBackground = true, widthDp = 412, heightDp = 1100)
@Composable
internal fun DialogsDarkEn() = ShotFrame(dark = true, english = true) { DialogsWall(en = true) }

@PreviewTest
@Preview(name = "select text light zh", showBackground = true, widthDp = 412, heightDp = 640)
@Composable
internal fun SelectTextLightZh() = ShotFrame(dark = false, english = false) {
    Box(Modifier.fillMaxSize()) {
        SelectTextPage(
            text = "原因是 approval.resolved 事件只推给了发起审批的连接。我会让插件在任一端处理后广播所有订阅者，App 收到后把那条审批标为已处理。这样电脑上点了允许，手机会在一秒内更新。",
            onDismiss = {},
            onCopy = {},
            onQuote = {},
        )
    }
}
