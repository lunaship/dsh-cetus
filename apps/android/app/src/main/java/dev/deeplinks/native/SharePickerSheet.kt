package dev.deeplinks.native

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import dev.deeplinks.core.DshS
import dev.deeplinks.core.sharePickImages
import dev.deeplinks.core.sharePickNewTask
import dev.deeplinks.core.sharePickNewTaskDesc
import dev.deeplinks.core.sharePickRunning
import dev.deeplinks.core.sharePickTitle
import dev.deeplinks.core.sharePickUntitled
import dev.deeplinks.native.ui.v4.DlBottomSheet
import dev.deeplinks.native.ui.v4.DlListRow
import dev.deeplinks.native.util.relativeTime

/** 分享选择器里最多列出几个最近会话（v4 8.3）。 */
internal const val SHARE_PICKER_MAX_SESSIONS = 6

/** 「新任务」在选择结果里的标记；其余值是会话 id。 */
internal const val SHARE_TARGET_NEW = ""

/** 可选的分享目标：隐藏子智能体会话，按最近更新排序，截断到 [limit]。 */
internal fun shareTargets(sessions: List<MobileSession>, limit: Int = SHARE_PICKER_MAX_SESSIONS): List<MobileSession> =
    sessions.asSequence()
        .filter { it.origin != "subagent" && !it.blank }
        .sortedByDescending { it.updatedAt }
        .take(limit)
        .toList()

/** 选择器副标题：文字取首行，图片写张数。 */
internal fun shareSummary(text: String?, imageCount: Int, imagesLabel: String): String? {
    val line = text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }?.take(60)
    val images = if (imageCount > 0) imagesLabel.format(imageCount) else null
    return listOfNotNull(images, line).joinToString(" · ").ifBlank { null }
}

/**
 * v4 8.3：从别的 App 分享进来时先选发到哪：新任务，或最近的某个会话。
 * 选中后内容只预填进输入框，不直接发送；关掉弹层等同于选「新任务」，分享内容不会丢。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharePickerSheet(
    sessions: List<MobileSession>,
    shareText: String?,
    imageCount: Int,
    onPick: (String) -> Unit,
) {
    val s = DshS
    DlBottomSheet(
        onDismissRequest = { onPick(SHARE_TARGET_NEW) },
        title = s.sharePickTitle,
        subtitle = shareSummary(shareText, imageCount, s.sharePickImages),
    ) {
        SharePickerContent(sessions, onPick)
    }
}

/** 选择器的行：首行「新任务」，下面是最近会话（工作区名 · 运行中 / 相对时间）。 */
@Composable
internal fun SharePickerContent(
    sessions: List<MobileSession>,
    onPick: (String) -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    val s = DshS
    DlListRow(
        title = s.sharePickNewTask,
        subtitle = s.sharePickNewTaskDesc,
        leading = PlusOutline16,
        onClick = { onPick(SHARE_TARGET_NEW) },
    )
    for (session in shareTargets(sessions)) {
        val place = session.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val state = if (session.running) s.sharePickRunning else relativeTime(session.updatedAt, now)
        DlListRow(
            title = session.title.ifBlank { s.sharePickUntitled },
            subtitle = listOfNotNull(place, state.takeIf { it.isNotBlank() }).joinToString(" · "),
            leading = ChatOutline16,
            onClick = { onPick(session.sessionId) },
        )
    }
}
