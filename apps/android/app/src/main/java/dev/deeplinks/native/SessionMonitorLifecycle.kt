package dev.deeplinks.native

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import dev.deeplinks.core.Host
import dev.deeplinks.native.util.WorkspacePrefs

@Composable
internal fun SessionMonitorActivation(
    host: Host,
    sessionId: String?,
    title: String,
    running: Boolean,
    awaitingInput: Boolean,
    stream: SessionStreamClient?,
) {
    val context = LocalContext.current
    LaunchedEffect(sessionId, running, awaitingInput) {
        val sid = sessionId ?: return@LaunchedEffect
        if (shouldMonitorSession(WorkspacePrefs(context).backgroundTakeover, running, awaitingInput)) {
            SessionBackgroundMonitorService.start(context, host, sid, title, stream?.lastSeq ?: 0L)
        } else {
            SessionBackgroundMonitorService.finish(context, sid)
        }
    }
}

internal object SessionMonitorLifecycle {
    fun onForeground(sessionId: String?, stream: SessionStreamClient?) {
        val cursor = sessionId?.let { SessionBackgroundMonitorService.foreground(it) } ?: 0L
        if (cursor > (stream?.lastSeq ?: 0L)) stream?.applySnapshotCursor(cursor)
        stream?.start()
    }

    fun onBackground(context: Context, host: Host, sessionId: String?, sessions: List<MobileSession>, stream: SessionStreamClient?) {
        val sid = sessionId
        // 开关关闭（默认）：与重设计前一致，离开 App 即断流，审批交给电脑网页
        if (sid != null && WorkspacePrefs(context).backgroundTakeover) {
            SessionBackgroundMonitorService.background(context, host, sid, sessions.firstOrNull { it.sessionId == sid }?.title.orEmpty(), stream?.lastSeq ?: 0L)
        }
        stream?.stop()
    }
}

/** 是否为当前会话启用后台接管：开关打开，且会话仍在运行或在等你处理。 */
internal fun shouldMonitorSession(backgroundTakeover: Boolean, running: Boolean, awaitingInput: Boolean): Boolean =
    backgroundTakeover && (running || awaitingInput)
