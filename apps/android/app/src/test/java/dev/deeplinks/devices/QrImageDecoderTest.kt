package dev.deeplinks.devices

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** M1：从相册图片解码二维码（纯像素路径，不需要 Android Bitmap）。 */
class QrImageDecoderTest {

    private fun qrPixels(text: String, size: Int = 300): IntArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return pixels
    }

    @Test
    fun `程序生成的二维码能解码往返`() {
        val text = "{\"type\":\"dsh-link\",\"pairingCode\":\"ABCD1234\",\"urls\":[\"https://10.0.0.2:18640\"]}"
        val pixels = qrPixels(text)
        assertEquals(text, QrImageDecoder.decodePixels(pixels, 300, 300))
    }

    @Test
    fun `没有二维码的纯白图返回 null`() {
        val pixels = IntArray(300 * 300) { 0xFFFFFFFF.toInt() }
        assertNull(QrImageDecoder.decodePixels(pixels, 300, 300))
    }

    @Test
    fun `尺寸不合法返回 null`() {
        assertNull(QrImageDecoder.decodePixels(IntArray(0), 0, 0))
    }

    @Test
    fun `下采样倍数让最长边不超过上限`() {
        assertEquals(1, QrImageDecoder.sampleSize(1600, 1200, 1600))
        assertEquals(2, QrImageDecoder.sampleSize(3200, 2400, 1600))
        assertEquals(4, QrImageDecoder.sampleSize(6400, 4800, 1600))
    }
}
