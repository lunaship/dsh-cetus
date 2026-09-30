package dev.deeplinks.devices

import dev.deeplinks.core.Host
import dev.deeplinks.core.L
import dev.deeplinks.core.PairClient
import dev.deeplinks.core.PinnedSsl
import java.util.UUID

/** 一次「从二维码文本配对」的结果（扫码与相册识别共用同一套判定）。 */
internal sealed interface PairQrOutcome {
    /** 配对成功、电脑已就绪。 */
    data class Paired(val host: Host) : PairQrOutcome

    /** 电脑要求面板确认：配对已登记，等用户在电脑上点批准。 */
    data class Pending(val host: Host) : PairQrOutcome

    /** 没能配对，[message] 可直接展示。 */
    data class Failed(val message: String) : PairQrOutcome
}

/**
 * 把二维码文本走完整配对（第三轮 M1）：解析 → [PairClient.pairWithQr]（局域网与远程两条路共用同一幂等键）
 * → [hostFromPair]。扫码与「从相册识别」都调用它，保证远程首配、pending 批准、错误提示完全一致。
 *
 * 调用方只负责保存 [Host] 与 UI 导航（pending 的批准提示等）。
 */
internal fun pairFromQrText(
    text: String,
    deviceName: String,
    requestId: String = UUID.randomUUID().toString(),
): PairQrOutcome = when (val parsed = parsePairingQr(text)) {
    PairingQrResult.NotDsh -> PairQrOutcome.Failed(L.notDshQr)
    PairingQrResult.Invalid -> PairQrOutcome.Failed(L.qrIncomplete)
    is PairingQrResult.Ok -> {
        val qr = parsed.qr
        try {
            val result = PairClient.pairWithQr(qr, deviceName, requestId)
            val host = hostFromPair(qr.name, result)
            if (result.pending) PairQrOutcome.Pending(host) else PairQrOutcome.Paired(host)
        } catch (e: Exception) {
            val unwrapped = PinnedSsl.unwrap(e)
            PairQrOutcome.Failed(unwrapped.message?.takeIf { it.isNotBlank() } ?: L.allAddressesFailed)
        }
    }
}
