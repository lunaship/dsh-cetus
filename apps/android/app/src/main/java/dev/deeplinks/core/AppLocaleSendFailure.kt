package dev.deeplinks.core

/**
 * 发送 / 作答失败的**分场景**文案（方案 §7 C03 要求 4 + §18「错误不是空状态」）。
 *
 * 单独成文件而不是塞进 `AppLocale.kt`：那条文件的体积由 `CodeHygieneTest` 守着
 * （超预算必须拆解、不许上调基线）。这里与 `AppLocaleControl.kt` 同一思路 ——
 * 一组相关文案就近扩展 `DshStrings`。
 */
val DshStrings.sendOutcomeUnknown: String get() = translation("sendOutcomeUnknown")
val DshStrings.sendFailedNetwork: String get() = translation("sendFailedNetwork")
val DshStrings.sendFailedUnauthorized: String get() = translation("sendFailedUnauthorized")
val DshStrings.sendFailedTargetGone: String get() = translation("sendFailedTargetGone")
val DshStrings.sendFailedForbidden: String get() = translation("sendFailedForbidden")
val DshStrings.sendFailedTooLarge: String get() = translation("sendFailedTooLarge")
val DshStrings.sendFailedBusy: String get() = translation("sendFailedBusy")

// 方案 §18「离线」：写操作被禁止时解释原因，不只是显示状态。
val DshStrings.sendOfflineBlocked: String get() = translation("sendOfflineBlocked")
val DshStrings.approveOfflineBlocked: String get() = translation("approveOfflineBlocked")
