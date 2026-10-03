package dev.deeplinks.native

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import dev.deeplinks.core.fileTreeReadOnly
import dev.deeplinks.core.fileTreeFolder
import dev.deeplinks.native.ui.v4.DlBottomSheet
import dev.deeplinks.native.ui.v4.DlListRow
import dev.deeplinks.native.ui.v4.DlRowTrailing
import dev.deeplinks.native.ui.v4.DlSpinner
import dev.deeplinks.native.ui.v4.DlTone
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceFileBrowserSheet(
    sessionId: String,
    loadDir: (sessionId: String, path: String) -> WorkspaceDirListing,
    fetchFile: (sessionId: String, path: String) -> Pair<String, ByteArray>,
    onDismiss: () -> Unit,
    /** 面包屑第一段：工作区目录名；null 时写「工作区根目录」。 */
    rootName: String? = null,
    /** 6.4「引用到对话」：带着文件路径回输入框；null 时不出这个动作。 */
    onQuote: ((String) -> Unit)? = null,
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

    fun copyPath(path: String) = copyWorkspacePath(context, path)

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

    DlBottomSheet(onDismissRequest = onDismiss, title = L.browseFiles) {
        Breadcrumb(
            root = rootName?.takeIf { it.isNotBlank() } ?: L.fileTreeRoot,
            dir = dir,
            onOpen = { dir = it },
        )
        // 固定为屏高比例：切换目录时弹层不随条目数跳动，矮屏也放得下
        LazyColumn(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.66f)) {
            when (val s = state) {
                DirState.Loading -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(DshSpace.s24), contentAlignment = Alignment.Center) { DlSpinner() }
                }
                is DirState.Failed -> item(key = "failed") {
                    DlListRow(
                        title = L.fileTreeLoadFailed,
                        subtitle = s.message,
                        leading = WarningOutline16,
                        leadingTint = DlTone.Err,
                        trailing = DlRowTrailing.TextAction(L.retry) { reloadTick++ },
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
        EmptyNote(L.fileTreeReadOnly)
    }

    preview?.let { current -> ProducedPreviewDialog(current, { preview = null }, onQuote) }
}

/** 6.3 面包屑：根名 / 各级目录，等宽；点任一级回到那一级。 */
@Composable
private fun Breadcrumb(root: String, dir: String, onOpen: (String) -> Unit) {
    val parts = dir.split('/').filter { it.isNotEmpty() }
    val scroll = rememberScrollState()
    LaunchedEffect(dir) { scroll.scrollTo(scroll.maxValue) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = DshSpace.s12, vertical = DshSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = DshType.supporting.copy(fontFamily = FontFamily.Monospace)
        val segments = listOf(root to "") + parts.mapIndexed { i, name -> name to parts.take(i + 1).joinToString("/") }
        segments.forEachIndexed { i, (name, path) ->
            if (i > 0) Text("/", style = style, color = Dsh.tertiaryText)
            val last = i == segments.lastIndex
            Text(
                name,
                style = style,
                color = if (last) Dsh.labelPrimary else Dsh.labelSecondary,
                maxLines = 1,
                modifier = Modifier
                    .clickable(enabled = !last, role = Role.Button) { onOpen(path) }
                    .padding(horizontal = DshSpace.s4, vertical = DshSpace.s8),
            )
        }
    }
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
        entry.isDir -> L.fileTreeFolder
        else -> null
    }
    DlListRow(
        title = entry.name,
        subtitle = subtitle,
        leading = if (entry.isDir) FolderOpenOutline16 else FileOutline16,
        enabled = !entry.outside && !opening,
        trailing = if (entry.isDir) DlRowTrailing.Chevron else DlRowTrailing.None,
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
        color = Dsh.tertiaryText,
        style = DshType.supporting,
        modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpace.s20, vertical = DshSpace.s12),
    )
}
