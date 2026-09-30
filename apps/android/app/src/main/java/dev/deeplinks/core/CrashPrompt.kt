package dev.deeplinks.core

import java.security.MessageDigest

/**
 * 首页是否再提示同一次崩溃。纯函数：崩溃文本变了才再提示，忽略或查看之后不再重复。
 */
fun crashTextFingerprint(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

fun shouldPromptForCrash(crashText: String?, lastFingerprint: String?): Boolean {
    if (crashText.isNullOrBlank()) return false
    return crashTextFingerprint(crashText) != lastFingerprint
}
