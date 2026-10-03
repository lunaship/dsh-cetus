package dev.deeplinks.architecture

import java.io.File

/**
 * 架构测试共用的源码遍历工具，路径相对 `src/main/java`。
 * v4 迁移白名单已在阶段 4 清空删除：所有文件都按 v4 规则检查。
 */
internal object ArchitectureSources {
    fun mainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "找不到 src/main/java，user.dir=" + System.getProperty("user.dir") }
        return File(dir, "src/main/java")
    }

    fun relative(root: File, file: File): String =
        file.relativeTo(root).path.replace(File.separatorChar, '/')

    fun kotlinFiles(root: File): Sequence<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }

    /** 行注释和 KDoc 不参与门禁，避免说明文字里的类名被当成调用。 */
    fun codeLines(file: File): List<String> =
        file.readLines().filter { line ->
            val trimmed = line.trimStart()
            !trimmed.startsWith("//") && !trimmed.startsWith("*") && !trimmed.startsWith("/*")
        }
}
