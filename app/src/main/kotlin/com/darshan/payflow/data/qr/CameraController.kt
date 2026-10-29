package com.darshan.payflow.data.qr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Decodes QR codes from camera frames. Wrapped so ML Kit never leaks into ViewModels or JVM tests. */
interface BarcodeScanner {
    /** Returns the raw values of QR codes found in [image] (an `InputImage` on Android), or an empty list. */
    suspend fun scan(image: Any): List<String>
    fun close()
}

/** Owns the camera session (CameraX). The screen calls [start] with its preview view and gets raw QR strings back. */
interface CameraController {
    val hasTorch: Boolean
    fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView?, onBarcode: (String) -> Unit, onError: (Throwable) -> Unit)
    fun stop()
    fun setTorch(on: Boolean)
}

interface CameraPermission {
    fun isGranted(): Boolean
}

class AndroidCameraPermission @Inject constructor(@ApplicationContext private val context: Context) : CameraPermission {
    override fun isGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}
