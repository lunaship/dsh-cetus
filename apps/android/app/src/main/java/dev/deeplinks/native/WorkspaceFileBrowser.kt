package dev.deeplinks.native

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.native.ui.DshListRow
import dev.deeplinks.native.ui.DshListTrailing
import dev.deeplinks.native.ui.DshSheet
import dev.deeplinks.native.util.ProducedFileKind
import dev.deeplinks.native.util.decodeProducedText
import dev.deeplinks.native.util.formatFileSize
import dev.deeplinks.native.util.isProducedTextMime
import dev.deeplinks.native.util.looksLikeText
import dev.deeplinks.native.util.producedFileKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 超过这个大小的「未知类型」文件不下载嗅探，直接复制路径。 */
private const val SNIFF_MAX_BYTES = 512L * 1024

private sealed interface DirState {
    data object Loading : DirState
    data class Loaded(val listing: WorkspaceDirListing) : DirState
    data class Failed(val message: String) : DirState
}

/**
 * 远端工作区文件浏览（lody「Remote Workspace File Tree」的对应物）：按层懒加载，
 * 目录进入 / 上一级；图片与文本就地预览（复用本轮产出的预览框），其它文件复制路径。
 * 插件未宣告 `capabilities.files.tree` 时入口不出现。
 */
@Composable
internal fun WorkspaceFileBrowserSheet(
    sessionId: String,
    loadDir: (sessionId: String, path: String) -> WorkspaceDirListing,
    fetchFile: (sessionId: String, path: String) -> Pair<String, ByteArray>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dir by remember(sessionId) { mutableStateOf("") }
    var state by remember(sessionId) { mutableStateOf<DirState>(DirState.Loading) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<ProducedPreview?>(null) }
    var openingName by remember { mutableStateOf<String?>(null) }
    var rowNotice by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(sessionId, dir, reloadTick) {
        state = DirState.Loading
        rowNotice = null
        state = try {
            DirState.Loaded(withContext(Dispatchers.IO) { loadDir(sessionId, dir) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DirState.Failed(e.message?.takeIf { it.isNotBlank() } ?: L.fileTreeLoadFailed)
        }
    }

    fun copyPath(path: String) {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("workspace path", path))
    }

    fun openFile(entry: WorkspaceDirEntry) {
        val path = childWorkspacePath(dir, entry.name)
        val kind = producedFileKind(entry.name)
        val sniff = kind == ProducedFileKind.OTHER && (entry.size ?: Long.MAX_VALUE) <= SNIFF_MAX_BYTES
        if (kind == ProducedFileKind.OTHER && !sniff) {
            copyPath(path)
            rowNotice = entry.name to L.fileTreePathCopied
            return
        }
        openingName = entry.name
        rowNotice = null
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { fetchFile(sessionId, path) } }
            openingName = null
            result.fold(
                onSuccess = { (mime, bytes) ->
                    val shown = when {
                        kind == ProducedFileKind.IMAGE || mime.startsWith("image/") ->
                            decodeSampledBitmap(bytes)?.let { ProducedPreview.Image(it) }
                        kind == ProducedFileKind.TEXT || isProducedTextMime(mime) || looksLikeText(bytes) ->
                            ProducedPreview.Text(path, decodeProducedText(bytes))
                        else -> null
                    }
                    if (shown != null) {
                        preview = shown
                    } else {
                        copyPath(path)
                        rowNotice = entry.name to L.fileTreePathCopied
                    }
                },
                onFailure = { rowNotice = entry.name to L.openFileFailed },
            )
        }
    }

    DshSheet(
        onDismiss = onDismiss,
        title = L.browseFiles,
        subtitle = if (dir.isEmpty()) L.fileTreeRoot else dir,
        showClose = true,
        skipPartiallyExpanded = true,
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            if (dir.isNotEmpty()) {
                item(key = "..") {
                    DshListRow(
                        title = L.fileTreeUp,
                        icon = ArrowLeftOutline16,
                        trailing = DshListTrailing.None,
                        onClick = { dir = parentWorkspacePath(dir) },
                    )
                }
            }
            when (val s = state) {
                DirState.Loading -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(DshSpace.s24), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Dsh.labelSecondary,
                        )
                    }
                }
                is DirState.Failed -> item(key = "failed") {
                    DshListRow(
                        title = L.fileTreeLoadFailed,
                        error = s.message,
                        onRetry = { reloadTick++ },
                        trailing = DshListTrailing.None,
                    )
                }
                is DirState.Loaded -> {
                    if (s.listing.entries.isEmpty()) {
                        item(key = "empty") { EmptyNote(L.fileTreeEmpty) }
                    }
                    items(s.listing.entries, key = { it.name }) { entry ->
                        FileEntryRow(
                            entry = entry,
                            opening = openingName == entry.name,
                            notice = rowNotice?.takeIf { it.first == entry.name }?.second,
                            onOpenDir = { dir = childWorkspacePath(dir, entry.name) },
                            onOpenFile = { openFile(entry) },
                        )
                    }
                    if (s.listing.truncated) {
                        item(key = "truncated") {
                            EmptyNote(L.fileTreeTruncated.format(s.listing.entries.size, s.listing.total))
                        }
                    }
                }
            }
        }
    }

    preview?.let { current -> ProducedPreviewDialog(current) { preview = null } }
}

@Composable
private fun FileEntryRow(
    entry: WorkspaceDirEntry,
    opening: Boolean,
    notice: String?,
    onOpenDir: () -> Unit,
    onOpenFile: () -> Unit,
) {
    val subtitle = when {
        opening -> L.loadingFile
        notice != null -> notice
        entry.outside -> L.fileTreeOutside
        entry.isFile -> entry.size?.let(::formatFileSize)
        else -> null
    }
    DshListRow(
        title = entry.name,
        subtitle = subtitle,
        icon = if (entry.isDir) FolderOpenOutline16 else FileOutline16,
        iconTint = if (entry.isDir) Dsh.labelSecondary else Dsh.labelTertiary,
        enabled = !entry.outside && !opening,
        trailing = if (entry.isDir) DshListTrailing.Chevron else DshListTrailing.None,
        onClick = when {
            entry.outside -> null
            entry.isDir -> onOpenDir
            entry.isFile -> onOpenFile
            else -> null
        },
    )
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        color = Dsh.labelTertiary,
        style = DshType.caption,
        modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpace.s16, vertical = DshSpace.s12),
    )
}
