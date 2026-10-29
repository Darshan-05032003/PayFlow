package com.darshan.payflow.data.qr

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * CameraX preview + image analysis feeding [BarcodeScanner]. Frames are analysed one at a time
 * (`STRATEGY_KEEP_ONLY_LATEST`), so a slow decode never queues stale frames. Device-only; compile-only in this build.
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
class CameraXController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: BarcodeScanner,
) : CameraController {
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var executor: ExecutorService? = null
    private var scope: CoroutineScope? = null
    private val analysing = AtomicBoolean(false)

    override val hasTorch: Boolean get() = camera?.cameraInfo?.hasFlashUnit() == true

    override fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView?, onBarcode: (String) -> Unit, onError: (Throwable) -> Unit) {
        stop()
        val localExecutor = Executors.newSingleThreadExecutor().also { executor = it }
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get().also { provider = it }
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView?.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(localExecutor) { proxy -> analyse(proxy, localScope, onBarcode, onError) }
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                onError(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyse(proxy: ImageProxy, scope: CoroutineScope, onBarcode: (String) -> Unit, onError: (Throwable) -> Unit) {
        val media = proxy.image
        if (media == null || !analysing.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scope.launch {
            try {
                scanner.scan(input).firstOrNull()?.let { raw -> ContextCompat.getMainExecutor(context).execute { onBarcode(raw) } }
            } catch (e: Exception) {
                ContextCompat.getMainExecutor(context).execute { onError(e) }
            } finally {
                proxy.close()
                analysing.set(false)
            }
        }
    }

    override fun stop() {
        provider?.unbindAll()
        provider = null
        camera = null
        scope?.cancel()
        scope = null
        executor?.shutdown()
        executor = null
        analysing.set(false)
    }

    override fun setTorch(on: Boolean) {
        camera?.cameraControl?.enableTorch(on)
    }
}
