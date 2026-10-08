package dev.deeplinks.native.util

import dev.deeplinks.core.L
import dev.deeplinks.core.sendFailedBusy
import dev.deeplinks.core.sendFailedForbidden
import dev.deeplinks.core.sendFailedNetwork
import dev.deeplinks.core.sendFailedTargetGone
import dev.deeplinks.core.sendFailedTooLarge
import dev.deeplinks.core.sendFailedUnauthorized
import dev.deeplinks.core.sendOutcomeUnknown

/**
 * 一次发送 / 作答失败的原因分类（方案 §7 C03 要求 4 + §18「错误不是空状态」）。
 *
 * 现状：Android 在失败时统一 `L.sendFailed.format(e.message)` —— 用户只看到一句原始
 * 网络错误，分不清「确定没发出去」和「可能已经发出去了」，也不知道该重试还是该先核对状态。
 * iOS 已有等价物 `SubmissionFailure`；这里补上两端一致的那一层。
 *
 * 分类是纯函数（不碰网络），所以能在单测里穷举。
 */
enum class SendFailure {
    /**
     * 超时 / 连接中断：请求可能已经到服务端。**不能自动重发**，先让用户核对状态。
     * 与 [NetworkDown] 的区别是关键 —— 后者能确定没发出去。
     */
    OutcomeUnknown,

    /** 电脑不可达（离线 / 拒绝连接）：可以确定没发出去，重试安全。 */
    NetworkDown,

    /** token 失效或设备未获批准：要让用户重新配对，重试无用。 */
    Unauthorized,

    /** 目标会话已不存在（被删除），或主机没有这个能力。 */
    TargetGone,

    /** 权限不足（插件拒绝）。 */
    Forbidden,

    /** 请求体 / 附件过大。 */
    TooLarge,

    /** 会话正忙（另一提交在跑）。 */
    Busy,

    /** 其它确定失败。 */
    Generic,
}

/**
 * 从异常与 HTTP 细节分类。`httpCode` / `body` 由 `MobileApiHttpException` 提供，
 * 其它异常传 null。
 *
 * 判定顺序很重要：超时类必须先于通用网络错误判断，否则会被误归成"可以安全重试"。
 */
fun classifySendFailure(
    throwable: Throwable? = null,
    httpCode: Int? = null,
    body: String? = null,
    message: String? = null,
): SendFailure {
    val text = (message ?: throwable?.message.orEmpty()).lowercase()
    val code = httpCode

    // 1. body 里的服务端错误码优先于状态码兜底：
    //    例如 400 + `session_busy` 必须归为 Busy，而不是落进 400 的 Generic 兜底。
    if (body != null) {
        when {
            body.contains("too_large") || body.contains("payload_too_large") -> return SendFailure.TooLarge
            body.contains("attachment_too_large") -> return SendFailure.TooLarge
            body.contains("session_not_found") -> return SendFailure.TargetGone
            body.contains("session_busy") -> return SendFailure.Busy
            body.contains("not_approved") || body.contains("pending_approval") -> return SendFailure.Unauthorized
        }
    }

    // 2. 服务端明确答复：按状态码分。
    if (code != null) {
        when (code) {
            401 -> return SendFailure.Unauthorized
            403 -> return SendFailure.Forbidden
            404, 410 -> return SendFailure.TargetGone
            409 -> return SendFailure.Busy
            413 -> return SendFailure.TooLarge
        }
    }

    // 2. 超时 / 连接中断 → 结果未知。必须排在网络不可达之前。
    if (text.contains("timeout") || text.contains("timed out") || text.contains("超时") ||
        text.contains("connection reset") || text.contains("connection lost") ||
        text.contains("broken pipe") || text.contains("连接中断")
    ) {
        return SendFailure.OutcomeUnknown
    }

    // 3. 确定连不上 → 可以安全重试。
    if (text.contains("connection refused") || text.contains("connect failed") ||
        text.contains("unreachable") || text.contains("no route") ||
        text.contains("unknownhost") || text.contains("failed to connect") ||
        text.contains("拒绝连接") || text.contains("无法连接") || text.contains("不可达")
    ) {
        return SendFailure.NetworkDown
    }

    // 4. 授权 / 目标 / 体积的关键词兜底。
    if (text.contains("unauthorized") || text.contains("401") || text.contains("token")) {
        return SendFailure.Unauthorized
    }
    if (text.contains("not found") || text.contains("不存在") || text.contains("已删除")) {
        return SendFailure.TargetGone
    }
    if (text.contains("too large") || text.contains("过大")) return SendFailure.TooLarge
    if (text.contains("busy") || text.contains("正忙")) return SendFailure.Busy

    return SendFailure.Generic
}

/** 本地化文案。每种失败都要能告诉用户下一步做什么（保留草稿 / 重试 / 先核对）。 */
fun sendFailureMessage(failure: SendFailure, detail: String? = null): String = when (failure) {
    SendFailure.OutcomeUnknown -> L.sendOutcomeUnknown
    SendFailure.NetworkDown -> L.sendFailedNetwork
    SendFailure.Unauthorized -> L.sendFailedUnauthorized
    SendFailure.TargetGone -> L.sendFailedTargetGone
    SendFailure.Forbidden -> L.sendFailedForbidden
    SendFailure.TooLarge -> L.sendFailedTooLarge
    SendFailure.Busy -> L.sendFailedBusy
    SendFailure.Generic -> L.sendFailed.format(detail?.takeIf { it.isNotBlank() } ?: L.unknownError)
}

/**
 * 失败后是否保留输入。**全部保留**：即使结果未知也不能丢用户写的内容
 * （方案 §7 要求 3/4）。这个函数存在的意义是把该约定写死并受测试守护，
 * 避免以后有人"顺手"在某个分支清空输入。
 */
fun failureKeepsDraft(@Suppress("UNUSED_PARAMETER") failure: SendFailure): Boolean = true

/** 结果未知时不得自动重发（方案 §7 要求 5）：服务端可能已经接受。 */
fun failureAllowsAutoResend(failure: SendFailure): Boolean =
    failure == SendFailure.NetworkDown
