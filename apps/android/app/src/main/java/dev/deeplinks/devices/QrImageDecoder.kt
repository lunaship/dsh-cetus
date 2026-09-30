package dev.deeplinks.devices

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import java.io.IOException

/**
 * 从相册图片里识别连接二维码（第三轮 M1）。
 *
 * 用来图选照片（Photo Picker，无需存储权限）→ 下采样到最长边 [MAX_DIM] → ZXing 限定 QR_CODE 解码。
 * 先 HybridBinarizer、失败再 GlobalHistogramBinarizer 试一次。解码在调用方 IO 线程执行。
 */
object QrImageDecoder {
    /** 下采样上限：太长边会拖慢 binarizer，1600 足够识别截图与翻拍。 */
    const val MAX_DIM = 1600

    /** 从相册 Uri 解码出二维码文本；没有二维码 / 读不了图都返回 null。 */
    fun decodeUri(context: Context, uri: Uri): String? {
        val bitmap = decodeDownsampled(context, uri, MAX_DIM) ?: return null
        return try {
            decodeBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** 解码一张（已下采样的）Bitmap。 */
    fun decodeBitmap(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return decodePixels(pixels, width, height)
    }

    /**
     * 纯 JVM 可测的解码入口：ARGB 像素数组 → 二维码文本，失败返回 null。
     * 不依赖任何 Android 类型（[Bitmap] 版只是它的一层壳）。
     */
    internal fun decodePixels(pixels: IntArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        val luminance = ByteArray(width * height)
        for (i in luminance.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            luminance[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255).toByte()
        }
        val source = RGBLuminanceSource(width, height, toIntLuminance(luminance))
        return tryDecode(HybridBinarizer(source)) ?: tryDecode(GlobalHistogramBinarizer(source))
    }

    private fun tryDecode(binarizer: com.google.zxing.Binarizer): String? {
        val reader = MultiFormatReader()
        reader.setHints(
            mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            ),
        )
        return try {
            reader.decodeWithState(BinaryBitmap(binarizer)).text
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun toIntLuminance(luminance: ByteArray): IntArray {
        val out = IntArray(luminance.size)
        for (i in luminance.indices) out[i] = luminance[i].toInt() and 0xFF
        return out
    }

    private fun decodeDownsampled(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (_: IOException) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: Exception) {
            null
        }
    }

    /** 2 的幂次下采样倍数，使最长边 ≤ [maxDim]。纯函数。 */
    internal fun sampleSize(width: Int, height: Int, maxDim: Int): Int {
        if (maxDim <= 0) return 1
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / sample > maxDim) sample *= 2
        return sample
    }
}
