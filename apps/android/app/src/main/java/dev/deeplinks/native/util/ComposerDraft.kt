package dev.deeplinks.native.util

import org.json.JSONObject

/**
 * 输入条草稿跟会话走（对照 OpenClaw：附件/语音不得泄漏到切走后的会话）。
 * 进程内按会话键暂存；正文另按主机落盘（见 [StoredDraft]），附件不落盘。
 */
data class ComposerDraft(
    val text: String = "",
    val images: List<Pair<String, String>> = emptyList(),
) {
    val isEmpty: Boolean get() = text.isBlank() && images.isEmpty()
}

const val COMPOSER_MAX_IMAGES = SHARE_IMAGE_LIMIT

fun composerDraftKey(sessionId: String?): String = sessionId.orEmpty()

fun stashComposerDraft(
    drafts: Map<String, ComposerDraft>,
    fromKey: String,
    toKey: String,
    current: ComposerDraft,
): Pair<Map<String, ComposerDraft>, ComposerDraft> {
    if (fromKey == toKey) return drafts to current
    val stored = if (current.isEmpty) drafts - fromKey else drafts + (fromKey to current)
    return stored to (stored[toKey] ?: ComposerDraft())
}

fun mergeComposerText(draft: ComposerDraft, addition: String): ComposerDraft {
    val t = addition.trim()
    if (t.isBlank()) return draft
    return draft.copy(text = if (draft.text.isBlank()) t else "${draft.text.trimEnd()} $t")
}

fun appendComposerImage(
    draft: ComposerDraft,
    image: Pair<String, String>,
    maxImages: Int = COMPOSER_MAX_IMAGES,
): ComposerDraft {
    if (draft.images.size >= maxImages) return draft
    return draft.copy(images = draft.images + image)
}

fun putComposerDraft(
    drafts: Map<String, ComposerDraft>,
    key: String,
    draft: ComposerDraft,
): Map<String, ComposerDraft> =
    if (draft.isEmpty) drafts - key else drafts + (key to draft)

fun putComposerDraftError(
    errors: Map<String, String>,
    key: String,
    message: String,
): Map<String, String> {
    val text = message.trim()
    return if (text.isEmpty()) errors - key else errors + (key to text)
}

/**
 * 切会话时把当前输入槽错误停在来源会话，并取出目标会话停着的错误。
 * 状态跟操作走，不 toast 到正在看的另一条会话。
 */
fun switchComposerErrors(
    errors: Map<String, String>,
    fromKey: String,
    toKey: String,
    currentError: String?,
): Pair<Map<String, String>, String?> {
    if (fromKey == toKey) return errors to currentError
    var next = errors
    val parked = currentError?.trim().orEmpty()
    if (parked.isNotEmpty()) next = next + (fromKey to parked)
    val restored = next[toKey]
    return if (restored == null) next to null else (next - toKey) to restored
}

/**
 * 相册/拍照/语音失败绑在发起时的会话：当前正在看就写进输入槽，否则停着等切回去再显示。
 */
fun liveOrParkedComposerError(
    errors: Map<String, String>,
    ownerKey: String,
    liveKey: String,
    message: String,
): Pair<Map<String, String>, String?> {
    val text = message.trim()
    if (text.isEmpty()) return errors to null
    return if (ownerKey == liveKey) errors to text else putComposerDraftError(errors, ownerKey, text) to null
}

/**
 * 落盘的草稿正文（进程被杀后恢复用）。只存文字：图片是 base64，体积大且可重新选。
 * [savedAt] 是正文最后一次变化的时间，用于过期与条数淘汰。
 */
data class StoredDraft(val text: String, val savedAt: Long)

const val STORED_DRAFT_MAX_ENTRIES = 30
const val STORED_DRAFT_MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000
const val STORED_DRAFT_MAX_CHARS = 20_000

/**
 * 进程内草稿 + 当前输入槽 → 待落盘集合。输入槽覆盖同键的旧暂存（清空输入即删除落盘草稿）；
 * 已删除会话不落盘；正文未变的沿用旧 [StoredDraft.savedAt]。
 */
fun storedDraftsFrom(
    drafts: Map<String, ComposerDraft>,
    liveKey: String,
    live: ComposerDraft,
    previous: Map<String, StoredDraft>,
    deletedSessionIds: Set<String>,
    now: Long,
): Map<String, StoredDraft> {
    val merged = putComposerDraft(drafts, liveKey, live)
    val out = LinkedHashMap<String, StoredDraft>()
    for ((key, draft) in merged) {
        if (key in deletedSessionIds || draft.text.isBlank()) continue
        val text = draft.text.take(STORED_DRAFT_MAX_CHARS)
        val prev = previous[key]
        out[key] = StoredDraft(text, if (prev?.text == text) prev.savedAt else now)
    }
    return pruneStoredDrafts(out, now)
}

/** 丢掉过期项，再按最近修改保留 [maxEntries] 条。 */
fun pruneStoredDrafts(
    stored: Map<String, StoredDraft>,
    now: Long,
    maxEntries: Int = STORED_DRAFT_MAX_ENTRIES,
    maxAgeMs: Long = STORED_DRAFT_MAX_AGE_MS,
): Map<String, StoredDraft> =
    stored.entries
        .filter { it.value.text.isNotBlank() && now - it.value.savedAt <= maxAgeMs }
        .sortedByDescending { it.value.savedAt }
        .take(maxEntries)
        .associate { it.key to it.value }

/** 冷启动合并：进程内（含 SavedState 恢复的）草稿优先，落盘正文只补缺。 */
fun restoreComposerDrafts(
    drafts: Map<String, ComposerDraft>,
    stored: Map<String, StoredDraft>,
): Map<String, ComposerDraft> {
    var next = drafts
    for ((key, s) in stored) {
        if (key !in next) next = next + (key to ComposerDraft(s.text))
    }
    return next
}

fun encodeStoredDrafts(stored: Map<String, StoredDraft>): String {
    val root = JSONObject()
    stored.forEach { (key, s) -> root.put(key, JSONObject().put("t", s.text).put("at", s.savedAt)) }
    return root.toString()
}

fun decodeStoredDrafts(raw: String?): Map<String, StoredDraft> {
    if (raw.isNullOrBlank()) return emptyMap()
    return try {
        val root = JSONObject(raw)
        buildMap {
            for (key in root.keys()) {
                val obj = root.optJSONObject(key) ?: continue
                val text = obj.optString("t")
                if (text.isNotBlank()) put(key, StoredDraft(text, obj.optLong("at", 0L)))
            }
        }
    } catch (_: Exception) {
        emptyMap()
    }
}
