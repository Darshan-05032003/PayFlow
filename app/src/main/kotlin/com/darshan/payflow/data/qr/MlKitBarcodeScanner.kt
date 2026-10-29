package com.darshan.payflow.data.qr

import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** ML Kit on-device barcode scanning, QR format only. Not exercised on the JVM (needs Play services): compile-only here. */
@Singleton
class MlKitBarcodeScanner @Inject constructor() : BarcodeScanner {
    private val client by lazy {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }

    override suspend fun scan(image: Any): List<String> {
        val input = image as? InputImage ?: return emptyList()
        return suspendCancellableCoroutine { cont ->
            client.process(input)
                .addOnSuccessListener { barcodes -> cont.resume(barcodes.mapNotNull { it.rawValue }.filter { it.isNotBlank() }) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
                .addOnCanceledListener { if (cont.isActive) cont.resume(emptyList()) }
        }
    }

    override fun close() = client.close()
}
