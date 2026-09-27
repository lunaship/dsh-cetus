package dev.deeplinks.native

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.deeplinks.native.util.ComposerDraft
import dev.deeplinks.native.util.StoredDraft
import dev.deeplinks.native.util.WorkspacePrefs
import dev.deeplinks.native.util.pruneStoredDrafts
import dev.deeplinks.native.util.restoreComposerDrafts
import dev.deeplinks.native.util.storedDraftsFrom
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private const val DRAFT_SAVE_DEBOUNCE_MS = 400L

/** 上次写盘的集合：沿用未变正文的 savedAt，也用于跳过无变化的写。null = 尚未读盘。 */
private class SavedDrafts {
    var value: Map<String, StoredDraft>? = null
}

/**
 * 输入条草稿正文按主机落盘：进程被杀（HyperOS 清后台等）后回到同一会话，正文还在。
 *
 * - 冷启动：落盘正文并进 [drafts]（进程内优先），再开始回写，避免空集合先覆盖掉磁盘。
 * - 输入槽为空且当前会话有暂存时回填（覆盖启动时直接绑定 currentSessionId、未走切换的路径）。
 * - 回写去抖；退到后台时立即落一次。
 */
@Composable
internal fun PersistComposerDrafts(
    prefs: WorkspacePrefs,
    slotKey: String,
    drafts: MutableState<Map<String, ComposerDraft>>,
    ownerKey: () -> String,
    live: () -> ComposerDraft,
    deletedSessionIds: () -> Set<String>,
    fillLiveText: (String) -> Unit,
) {
    val currentOwner = rememberUpdatedState(ownerKey)
    val currentLive = rememberUpdatedState(live)
    val currentDeleted = rememberUpdatedState(deletedSessionIds)
    val saved = remember(slotKey) { SavedDrafts() }
    val loaded = remember(slotKey) { mutableStateOf(false) }

    fun compute(): Map<String, StoredDraft> = storedDraftsFrom(
        drafts = drafts.value,
        liveKey = currentOwner.value(),
        live = currentLive.value(),
        previous = saved.value.orEmpty(),
        deletedSessionIds = currentDeleted.value(),
        now = System.currentTimeMillis(),
    )

    fun flush(next: Map<String, StoredDraft>) {
        if (saved.value == null || next == saved.value) return
        prefs.saveComposerDrafts(slotKey, next)
        saved.value = next
    }

    LaunchedEffect(prefs, slotKey) {
        val stored = pruneStoredDrafts(prefs.composerDrafts(slotKey), System.currentTimeMillis())
        drafts.value = restoreComposerDrafts(drafts.value, stored)
        saved.value = stored
        loaded.value = true
        snapshotFlow { compute() }.collectLatest { next ->
            delay(DRAFT_SAVE_DEBOUNCE_MS)
            flush(next)
        }
    }

    val owner = ownerKey()
    LaunchedEffect(slotKey, owner, loaded.value) {
        if (!loaded.value) return@LaunchedEffect
        val stored = drafts.value[owner] ?: return@LaunchedEffect
        if (currentLive.value().text.isBlank() && stored.text.isNotBlank()) fillLiveText(stored.text)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, slotKey) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) flush(compute())
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
