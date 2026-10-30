package com.darshan.payflow.data.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader

/** Outcome of reading a QR from a gallery image. Only [Found] / [MultipleFound] can lead to a payment. */
sealed interface ImageQrResult {
    data class Found(val payload: UpiQrPayload) : ImageQrResult
    /** Several valid UPI QRs in one image; the first one (reading order) is used, the same way GPay does. */
    data class MultipleFound(val payloads: List<UpiQrPayload>) : ImageQrResult {
        init { require(payloads.size >= 2) { "use Found for a single payload" } }
        val first: UpiQrPayload get() = payloads.first()
    }
    /** The image has QR code(s) but none is a valid UPI payment QR; [reason] is the parser's verdict for the first one. */
    data class NotUpi(val reason: QrError) : ImageQrResult
    data object NoQrFound : ImageQrResult
    /** The file could not be opened or decoded as an image (corrupt, revoked grant, not an image). */
    data object Unreadable : ImageQrResult
}

/**
 * Decodes a QR from an image the user picked with the Photo Picker. [uri] is the picker's content URI as a string: it is read only
 * through the content resolver for the duration of the call, never copied, persisted or logged.
 */
interface ImageQrDecoder {
    suspend fun decode(uri: String): ImageQrResult
}

/** Maps decoded QR texts to a result through the same hostile-input-safe [UpiQrPayloadParser] a camera scan uses. Pure. */
object ImageQrResults {
    fun fromTexts(texts: List<String>): ImageQrResult {
        val distinct = texts.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return ImageQrResult.NoQrFound
        val parsed = distinct.map(UpiQrPayloadParser::parse)
        val payloads = parsed.filterIsInstance<QrParseResult.Valid>().map { it.payload }
        return when {
            payloads.size == 1 -> ImageQrResult.Found(payloads.single())
            payloads.size > 1 -> ImageQrResult.MultipleFound(payloads)
            else -> ImageQrResult.NotUpi((parsed.first() as QrParseResult.Invalid).error)
        }
    }
}

/** Downsampling maths for gallery images (a 50 MP photo must never be decoded at full size). Pure. */
object ImageSampling {
    const val MAX_SIDE = 2_048
    const val EXIF_ROTATE_180 = 3
    const val EXIF_ROTATE_90 = 6
    const val EXIF_ROTATE_270 = 8

    /** Smallest power of two that brings the longest side to at most [maxSide] (what `BitmapFactory.Options.inSampleSize` wants). */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        val longest = maxOf(width, height)
        if (longest <= 0) return 1
        var sample = 1
        while (longest / sample > maxSide) sample *= 2
        return sample
    }

    /** EXIF orientation tag → clockwise rotation in degrees (mirrored variants are rare in screenshots and treated as upright). */
    fun rotationDegrees(exifOrientation: Int): Int = when (exifOrientation) {
        EXIF_ROTATE_90 -> 90
        EXIF_ROTATE_180 -> 180
        EXIF_ROTATE_270 -> 270
        else -> 0
    }
}

/**
 * zxing-core fallback decoder over ARGB pixels (used when ML Kit finds nothing, and testable on the plain JVM). Tries the multi-QR
 * reader first so a screenshot with several codes yields all of them in reading order, then a single `MultiFormatReader` pass with
 * `TRY_HARDER`. Never throws.
 */
object ZxingQrImageDecoder {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    fun decode(argb: IntArray, width: Int, height: Int): List<String> {
        if (width <= 0 || height <= 0 || argb.size < width * height) return emptyList()
        val bitmap = runCatching { BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, argb))) }.getOrNull() ?: return emptyList()
        val multi = runCatching { QRCodeMultiReader().decodeMultiple(bitmap, hints).toList() }.getOrDefault(emptyList())
        if (multi.isNotEmpty()) return multi.sortedWith(readingOrder).mapNotNull { it.text?.takeIf(String::isNotBlank) }
        return runCatching { MultiFormatReader().decode(bitmap, hints) }.getOrNull()?.text?.takeIf { it.isNotBlank() }?.let(::listOf) ?: emptyList()
    }

    /** Left to right, then top to bottom, by the centre of each code's finder points. */
    private val readingOrder = compareBy<Result>({ r -> r.resultPoints?.map { it.x }?.average() ?: 0.0 }, { r -> r.resultPoints?.map { it.y }?.average() ?: 0.0 })
}
