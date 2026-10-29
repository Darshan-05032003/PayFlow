package com.darshan.payflow.testutil

import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.darshan.payflow.data.qr.CameraController

class FakeCameraController : CameraController {
    var started = false
    var torchOn = false
    var onBarcode: ((String) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null
    override val hasTorch: Boolean = true

    override fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView?, onBarcode: (String) -> Unit, onError: (Throwable) -> Unit) {
        started = true; this.onBarcode = onBarcode; this.onError = onError
    }

    override fun stop() { started = false }
    override fun setTorch(on: Boolean) { torchOn = on }
    fun emit(raw: String) = onBarcode?.invoke(raw)
}

fun com.darshan.payflow.ui.navigation.PayDestination.toSavedStateHandle() = androidx.lifecycle.SavedStateHandle(
    buildMap {
        vpa?.let { put(com.darshan.payflow.ui.pay.PayViewModel.ARG_PREFILL_VPA, it) }
        name?.let { put(com.darshan.payflow.ui.pay.PayViewModel.ARG_PREFILL_NAME, it) }
        put(com.darshan.payflow.ui.pay.PayViewModel.ARG_PREFILL_AMOUNT, amountPaise)
        note?.let { put(com.darshan.payflow.ui.pay.PayViewModel.ARG_PREFILL_NOTE, it) }
        source?.let { put(com.darshan.payflow.ui.pay.PayViewModel.ARG_SOURCE, it) }
    },
)
