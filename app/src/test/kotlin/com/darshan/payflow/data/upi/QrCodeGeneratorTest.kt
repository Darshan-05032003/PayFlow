package com.darshan.payflow.data.upi

import com.google.common.truth.Truth.assertThat
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Test

class QrCodeGeneratorTest {
    private fun decode(matrix: QrMatrix): String {
        val scale = 4
        val px = matrix.size * scale
        val pixels = IntArray(px * px) { i -> if (matrix[(i % px) / scale, (i / px) / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(px, px, pixels)))).text
    }

    @Test
    fun `a receive link round trips through encode and decode`() {
        val content = "upi://pay?pa=darshan%40okaxis&pn=Darshan%20Sonawane&am=150.00&cu=INR"
        val matrix = QrCodeGenerator.encode(content)
        assertThat(matrix.size).isGreaterThan(21)
        assertThat(decode(matrix)).isEqualTo(content)
    }

    @Test
    fun `blank or oversized content is refused before zxing sees it`() {
        assertThat(runCatching { QrCodeGenerator.encode("") }.isFailure).isTrue()
        assertThat(runCatching { QrCodeGenerator.encode("x".repeat(QrCodeGenerator.MAX_CONTENT_LENGTH + 1)) }.isFailure).isTrue()
    }
}
