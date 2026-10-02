package dev.deeplinks.native

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import android.content.Context
import dev.deeplinks.core.CrashRecorder
import dev.deeplinks.core.Host
import dev.deeplinks.core.HostStore
import dev.deeplinks.core.StartupTrace
import kotlinx.coroutines.CancellationException

/**
 * 冷启动相关的前台副作用（第三轮 S1 / S7 从 WorkspaceScreen 抽出）：
 * 首页第一帧打点、会话列表缓存的读与写。
 */
@Composable
internal fun StartupFirstFrameMark() {
    LaunchedEffect(Unit) {
        withFrameNanos { }
        StartupTrace.mark("home_first_frame")
    }
}

/**
 * S1：会话列表本地缓存。
 * - 首次组合读缓存，先铺会话列表与工作区目录（IO 线程，不阻塞主线程）；
 * - 会话/工作区/归档集合变化后写回（VM 内 2 秒防抖）。
 */
@Composable
internal fun SessionListCacheEffects(
    host: Host,
    viewModel: WorkspaceViewModel,
    sessions: List<MobileSession>,
    workspaceCatalogItems: List<MobileWorkspace>,
    archivedIds: Set<String>,
    applyWorkspaceCatalog: (MobileWorkspaceCatalog) -> Unit,
    setArchivedIds: (Set<String>) -> Unit,
) {
    LaunchedEffect(host) {
        val cached = viewModel.hydrateFromSessionListCache() ?: return@LaunchedEffect
        if (viewModel.sessions.value.isNotEmpty() && workspaceCatalogItems.isEmpty()) {
            applyWorkspaceCatalog(MobileWorkspaceCatalog(cached.workspaces))
        }
        if (cached.archivedSessionIds.isNotEmpty() && archivedIds.isEmpty()) {
            setArchivedIds(cached.archivedSessionIds)
        }
        StartupTrace.mark("cache_list", "${cached.sessions.size}")
    }
    LaunchedEffect(sessions, workspaceCatalogItems, archivedIds) {
        viewModel.scheduleSessionListCacheWrite(sessions, archivedIds, workspaceCatalogItems)
    }
}

/** 冷启动 bootstrap 的结局（第三轮 S2 / S7）。 */
internal enum class ColdStartOutcome { Ok, BootstrapFailed, AuthExpired }

/**
 * 冷启动第一段（从 WorkspaceScreen 抽出）：bootstrap 拉主机信息 + 会话 + 归档集合，完成打点并落状态。
 * 工作区目录留给调用方——它要写屏幕侧的工作区注册表（applyWorkspaceCatalog）。
 */
internal suspend fun WorkspaceViewModel.runColdStartBootstrap(
    host: Host,
    context: Context,
    restoreSessionId: String?,
    composeNewSession: Boolean,
    onAuthExpired: (Throwable) -> Unit,
): ColdStartOutcome {
    CrashRecorder.breadcrumb("bootstrap", "start")
    var ok = false
    try {
        val (boot, refreshed) = repo.bootstrap()
        ok = true
        CrashRecorder.breadcrumb("bootstrap", "done ${boot.sessions.size}")
        StartupTrace.mark("bootstrap_done", "${boot.sessions.size}")
        filesTreeSupported.value = boot.filesTree
        sessionControl.supported.value = boot.sessionControl
        previewSupported.value = boot.preview
        // 远程能力补齐 / 清除（bootstrap 的 remote，RFC §6.4）
        if (refreshed != host) runCatching { HostStore.upsert(context, refreshed) }
        // Bootstrap carries the same durable archive set as Web. Apply it before selecting a session,
        // so an archived Web session cannot flash back into the App during cold start.
        val nextArchivedIds = if (boot.archiveSnapshotAvailable) {
            val prefs = local.prefs
            val restored = prefs.restoredSessionIds
            val nextRestored = restored intersect boot.archivedSessionIds
            if (nextRestored != restored) prefs.restoredSessionIds = nextRestored
            val synced = reconcileArchivedSessionIds(boot.archivedSessionIds, nextRestored)
            if (synced != local.archivedSessionIds.value) local.setArchivedSessionIds(synced)
            synced
        } else {
            local.archivedSessionIds.value
        }
        sessions.value = boot.sessions
        sessionsLoadError.value = null
        sessionsInitialLoad.value = false
        StartupTrace.mark("home_network_data", "${boot.sessions.size}")
        if (currentSessionId.value == null && !composeNewSession && boot.sessions.isNotEmpty()) {
            currentSessionId.value = reconciledSessionId(
                currentSessionId = null,
                preferredSessionId = restoreSessionId,
                sessions = boot.sessions,
                hiddenSessionIds = nextArchivedIds + local.deletedSessionIds.value,
                preserveEmptySelection = false,
                selectLatest = true,
            )
            if (boot.sessions.any { it.sessionId == currentSessionId.value && it.running }) {
                liveRunning.value = true
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (isMobileAuthFailure(e)) {
            onAuthExpired(e)
            return ColdStartOutcome.AuthExpired
        }
    }
    return if (ok) ColdStartOutcome.Ok else ColdStartOutcome.BootstrapFailed
}
