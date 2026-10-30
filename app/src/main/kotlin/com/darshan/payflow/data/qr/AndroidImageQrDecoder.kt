package com.darshan.payflow.data.qr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.darshan.payflow.core.DispatcherProvider
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Gallery QR decoding on the device. The picked image is read through the content resolver only (no copy, no persistence, no logs),
 * decoded downsampled so its longest side is at most [ImageSampling.MAX_SIDE] and turned upright from its EXIF orientation; then ML
 * Kit (QR only) reads it, with the zxing-core decoder as a fallback when ML Kit finds nothing. Everything runs on the IO dispatcher.
 * ML Kit needs Play services, so the happy path is verified on the emulator; the failure paths are covered on the JVM.
 */
class AndroidImageQrDecoder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) : ImageQrDecoder {

    override suspend fun decode(uri: String): ImageQrResult = withContext(dispatchers.io) {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()?.takeIf { it.scheme in READABLE_SCHEMES } ?: return@withContext ImageQrResult.Unreadable
        val bitmap = runCatching { loadUpright(parsed) }.getOrNull() ?: return@withContext ImageQrResult.Unreadable
        try {
            val texts = runCatching { mlKit(bitmap) }.getOrDefault(emptyList()).ifEmpty { zxing(bitmap) }
            ImageQrResults.fromTexts(texts)
        } finally {
            bitmap.recycle()
        }
    }

    private fun loadUpright(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageSampling.sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = ImageSampling.rotationDegrees(orientation)
        if (degrees == 0) return decoded
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    private suspend fun mlKit(bitmap: Bitmap): List<String> {
        val client = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        return try {
            suspendCancellableCoroutine { cont ->
                client.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { barcodes -> cont.resume(barcodes.sortedWith(readingOrder).mapNotNull { it.rawValue }) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(emptyList()) }
                    .addOnCanceledListener { if (cont.isActive) cont.resume(emptyList()) }
            }
        } finally {
            client.close()
        }
    }

    private fun zxing(bitmap: Bitmap): List<String> {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return ZxingQrImageDecoder.decode(pixels, bitmap.width, bitmap.height)
    }

    private companion object {
        val READABLE_SCHEMES = setOf("content", "file")
        val readingOrder = compareBy<Barcode>({ it.boundingBox?.left ?: 0 }, { it.boundingBox?.top ?: 0 })
    }
}
