package com.darshan.payflow.ui.scan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darshan.payflow.core.DispatcherProvider
import com.darshan.payflow.data.qr.ImageQrDecoder
import com.darshan.payflow.data.qr.ImageQrResult
import com.darshan.payflow.data.qr.QrError
import com.darshan.payflow.data.qr.QrParseResult
import com.darshan.payflow.data.qr.UpiQrPayload
import com.darshan.payflow.data.qr.UpiQrPayloadParser
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Why a gallery image could not be paid. */
enum class ImageError { NO_QR, NOT_UPI, UNREADABLE }

sealed interface ScanUiState {
    /** Loading: the route is checking the runtime permission. */
    data object CheckingPermission : ScanUiState
    data class PermissionRequired(val showRationale: Boolean) : ScanUiState
    /** Denied with "don't ask again": only Settings can fix it (the gallery still works). */
    data object PermissionDenied : ScanUiState
    data class Scanning(val torchOn: Boolean, val hint: QrError?) : ScanUiState
    data class Found(val payload: UpiQrPayload) : ScanUiState
    data class Error(val detail: String?) : ScanUiState
    /** A picked gallery image is being decoded. */
    data object DecodingImage : ScanUiState
    /** The picked image held no payable QR. [cameraAvailable] decides whether "Scan with camera" or "Back" is offered. */
    data class ImageFailed(val error: ImageError, val cameraAvailable: Boolean) : ScanUiState
}

sealed interface ScanEvent {
    data class PayloadReady(val payload: UpiQrPayload) : ScanEvent
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val imageQrDecoder: ImageQrDecoder,
    private val dispatchers: DispatcherProvider,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(restoreImageError() ?: ScanUiState.CheckingPermission)
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<ScanEvent> = _events.asSharedFlow()

    private var torchOn = false
    /** What the camera side of the screen should show (from the last permission result); null until it is known. */
    private var cameraState: ScanUiState? = null

    fun onPermissionResult(granted: Boolean, canAskAgain: Boolean) {
        val camera = when {
            !granted && canAskAgain -> ScanUiState.PermissionRequired(showRationale = true)
            !granted -> ScanUiState.PermissionDenied
            else -> ScanUiState.Scanning(torchOn, hint = null)
        }
        cameraState = camera
        _uiState.value = when (val current = _uiState.value) {
            // A gallery decode or its error outlives a rotation; the permission re-check must not wipe it.
            ScanUiState.DecodingImage -> current
            is ScanUiState.ImageFailed -> current.copy(cameraAvailable = granted).also { savedState[KEY_IMAGE_CAMERA] = granted }
            else -> restoreFound() ?: camera
        }
    }

    /** A decoded frame. Ignored unless we are actively scanning. */
    fun onBarcode(raw: String) {
        if (_uiState.value !is ScanUiState.Scanning) return
        when (val result = UpiQrPayloadParser.parse(raw)) {
            is QrParseResult.Invalid -> _uiState.update { (it as? ScanUiState.Scanning)?.copy(hint = result.error) ?: it }
            is QrParseResult.Valid -> found(result.payload)
        }
    }

    /**
     * The Photo Picker returned [uri] (null when the user backed out). The image is decoded on the IO dispatcher; the URI itself is
     * never saved, only the resulting payload, exactly as for a camera scan.
     */
    fun onImagePicked(uri: String?) {
        if (uri.isNullOrBlank() || _uiState.value == ScanUiState.DecodingImage) return
        savedState.remove<String>(KEY_IMAGE_ERROR)
        _uiState.value = ScanUiState.DecodingImage
        viewModelScope.launch {
            when (val result = withContext(dispatchers.io) { imageQrDecoder.decode(uri) }) {
                is ImageQrResult.Found -> found(result.payload)
                is ImageQrResult.MultipleFound -> found(result.first)
                is ImageQrResult.NotUpi -> imageFailed(ImageError.NOT_UPI)
                ImageQrResult.NoQrFound -> imageFailed(ImageError.NO_QR)
                ImageQrResult.Unreadable -> imageFailed(ImageError.UNREADABLE)
            }
        }
    }

    /** "Scan with camera" / "Back" on an image error: return to whatever the camera side was showing. */
    fun dismissImageError() {
        if (_uiState.value !is ScanUiState.ImageFailed) return
        savedState.remove<String>(KEY_IMAGE_ERROR)
        savedState.remove<Boolean>(KEY_IMAGE_CAMERA)
        _uiState.value = cameraState ?: ScanUiState.CheckingPermission
    }

    private fun found(payload: UpiQrPayload) {
        savedState[KEY_FOUND_RAW] = payload.raw
        _uiState.value = ScanUiState.Found(payload)
        _events.tryEmit(ScanEvent.PayloadReady(payload))
    }

    private fun imageFailed(error: ImageError) {
        val camera = cameraState is ScanUiState.Scanning
        savedState[KEY_IMAGE_ERROR] = error.name
        savedState[KEY_IMAGE_CAMERA] = camera
        _uiState.value = ScanUiState.ImageFailed(error, camera)
    }

    fun onCameraError(error: Throwable) {
        _uiState.value = ScanUiState.Error(error.message)
    }

    fun rescan() {
        savedState.remove<String>(KEY_FOUND_RAW)
        // Without the camera permission there is nothing to scan with: go back to the permission state instead of a dead preview.
        _uiState.value = cameraState?.takeIf { it !is ScanUiState.Scanning } ?: ScanUiState.Scanning(torchOn, hint = null)
    }

    fun toggleTorch() {
        torchOn = !torchOn
        _uiState.update { (it as? ScanUiState.Scanning)?.copy(torchOn = torchOn) ?: it }
    }

    private fun restoreFound(): ScanUiState? {
        val raw = savedState.get<String>(KEY_FOUND_RAW) ?: return null
        return (UpiQrPayloadParser.parse(raw) as? QrParseResult.Valid)?.let { ScanUiState.Found(it.payload) }
    }

    private fun restoreImageError(): ScanUiState? {
        val error = savedState.get<String>(KEY_IMAGE_ERROR)?.let { name -> ImageError.entries.firstOrNull { it.name == name } } ?: return null
        return ScanUiState.ImageFailed(error, savedState.get<Boolean>(KEY_IMAGE_CAMERA) ?: false)
    }

    companion object {
        const val KEY_FOUND_RAW = "foundRaw"
        const val KEY_IMAGE_ERROR = "imageError"
        const val KEY_IMAGE_CAMERA = "imageErrorCamera"
    }
}
