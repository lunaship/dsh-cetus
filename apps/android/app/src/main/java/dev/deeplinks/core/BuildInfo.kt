package dev.deeplinks.core

import dev.deeplinks.BuildConfig

/**
 * 内部构建元数据（方案 §4 C00）。
 *
 * 值来自 Gradle `buildConfigField`（见 app/build.gradle.kts），让一张截图能对应到具体提交。
 * 只在「关于」页展示，**不进日常首页**。营销版本与版本号仍走 `BuildConfig.VERSION_NAME` /
 * `VERSION_CODE`，由维护者按发布阶段处理。
 */
object BuildInfo {
    /** 短提交 SHA；工作树脏时带 `-dirty`。取不到时为 `unknown`。 */
    val commit: String = BuildConfig.BUILD_COMMIT

    /** 构建日期（UTC，`yyyy-MM-dd`）。 */
    val date: String = BuildConfig.BUILD_DATE

    /** 手机同步合同版本（`docs/MOBILE_SYNC_CONTRACT.md`）。 */
    val contractVersion: String = BuildConfig.BUILD_CONTRACT_VERSION

    /** 营销版本，例如 `0.5.0-beta.31`。 */
    val marketingVersion: String = BuildConfig.VERSION_NAME

    /** 构建号，例如 `39`。 */
    val buildNumber: String = BuildConfig.VERSION_CODE.toString()

    /** 「关于」页展示的营销版本 + 构建号，例如 `0.5.0-beta.31 (39)`。 */
    val versionLine: String get() = "$marketingVersion ($buildNumber)"

    /** 诊断展示的一行：提交 / 日期 / 合同版本。 */
    val diagnosticLine: String get() = "$commit · $date · contract v$contractVersion"
}
