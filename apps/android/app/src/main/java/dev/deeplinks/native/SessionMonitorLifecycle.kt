package dev.deeplinks.native

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import dev.deeplinks.core.Host

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
        if (running || awaitingInput) SessionBackgroundMonitorService.start(context, host, sid, title, stream?.lastSeq ?: 0L)
        else SessionBackgroundMonitorService.finish(context, sid)
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
        if (sid != null) SessionBackgroundMonitorService.background(context, host, sid, sessions.firstOrNull { it.sessionId == sid }?.title.orEmpty(), stream?.lastSeq ?: 0L)
        stream?.stop()
    }
}
