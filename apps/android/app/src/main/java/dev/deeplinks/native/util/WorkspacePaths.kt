package dev.deeplinks.native.util

/**
 * Host 路径展示与本轮产出文件分类。
 * 对标 DSH Web：HOME 缩写成 `~`；产出文件按后缀决定预览方式。
 */
private val POSIX_HOME = Regex("^/(?:Users|home)/[^/]+")

fun abbreviateHomePath(path: String?): String {
    if (path.isNullOrBlank()) return ""
    return POSIX_HOME.replace(path.trim(), "~")
}

fun producedFileName(path: String): String {
    val trimmed = path.trim()
    return trimmed.substringAfterLast('/').ifBlank { trimmed }
}

enum class ProducedFileKind { IMAGE, TEXT, OTHER }

private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp")
private val TEXT_EXT = setOf(
    "md", "txt", "json", "kt", "kts", "js", "ts", "tsx", "jsx",
    "py", "yml", "yaml", "xml", "csv", "html", "css", "sh", "toml", "svg",
)

fun producedFileKind(path: String): ProducedFileKind {
    val ext = path.trim().substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when (ext) {
        in IMAGE_EXT -> ProducedFileKind.IMAGE
        in TEXT_EXT -> ProducedFileKind.TEXT
        else -> ProducedFileKind.OTHER
    }
}

fun decodeProducedText(bytes: ByteArray, maxChars: Int = 32_768): String {
    val text = String(bytes, Charsets.UTF_8)
    if (text.length <= maxChars) return text
    return text.take(maxChars) + "…"
}

fun isProducedTextMime(mime: String): Boolean {
    val kind = mime.substringBefore(';').trim().lowercase()
    return kind.startsWith("text/") ||
        kind == "application/json" ||
        kind == "application/xml" ||
        kind.endsWith("+json") ||
        kind.endsWith("+xml")
}

/** 无后缀或未知后缀的文件：前 8KB 无 NUL 且是合法 UTF-8 即按文本预览（Makefile、.env、go/rs 源码等）。 */
fun looksLikeText(bytes: ByteArray): Boolean {
    val head = if (bytes.size > 8192) bytes.copyOf(8192) else bytes
    if (head.any { it == 0.toByte() }) return false
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    val out = java.nio.CharBuffer.allocate(head.size)
    // 截断时 endOfInput=false：尾部被切开的多字节字符算「未完」而不是非法
    val result = decoder.decode(java.nio.ByteBuffer.wrap(head), out, head.size == bytes.size)
    return !result.isError
}

/** 文件大小：B / KB / MB，一位小数。 */
fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
}
