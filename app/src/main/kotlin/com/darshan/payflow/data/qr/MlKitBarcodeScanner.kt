package com.darshan.payflow.data.qr

import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** ML Kit on-device barcode scanning, QR format only. Not exercised on the JVM (needs Play services): compile-only here. */
@Singleton
class MlKitBarcodeScanner @Inject constructor() : BarcodeScanner {
    private val client by lazy {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }

    /**
     * Deliberately *not* cancellable: ML Kit keeps reading the frame natively until its Task completes. Resuming early on cancellation
     * let the camera close the `ImageProxy` under a running `recognize` (use-after-free, SIGSEGV in libbarhopper on the emulator when
     * the preview stopped mid-frame). The wait is one frame's decode at most.
     */
    override suspend fun scan(image: Any): List<String> {
        val input = image as? InputImage ?: return emptyList()
        return suspendCoroutine { cont ->
            client.process(input).addOnCompleteListener { task ->
                when {
                    task.isCanceled -> cont.resume(emptyList())
                    task.isSuccessful -> cont.resume(task.result.orEmpty().mapNotNull { it.rawValue }.filter { it.isNotBlank() })
                    else -> cont.resumeWithException(task.exception ?: IllegalStateException("barcode scan failed"))
                }
            }
        }
    }

    override fun close() = client.close()
}
