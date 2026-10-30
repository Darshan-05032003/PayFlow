package com.darshan.payflow.data.upi

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** A rendered QR as a square boolean grid (`true` = dark module), independent of Android graphics so it is testable on the JVM. */
class QrMatrix(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]
}

/** zxing wrapper. Error correction M is what UPI apps print; a 1-module quiet zone keeps the bitmap tight (the card adds padding). */
object QrCodeGenerator {
    const val MAX_CONTENT_LENGTH = 2_048

    fun encode(content: String): QrMatrix {
        require(content.isNotBlank() && content.length <= MAX_CONTENT_LENGTH) { "content length out of range" }
        val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
        val size = matrix.width
        val dark = BooleanArray(size * size) { i -> matrix.get(i % size, i / size) }
        return QrMatrix(size, dark)
    }
}
