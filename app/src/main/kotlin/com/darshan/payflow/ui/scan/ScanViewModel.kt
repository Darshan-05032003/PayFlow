package com.darshan.payflow.ui.scan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
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

sealed interface ScanUiState {
    /** Loading: the route is checking the runtime permission. */
    data object CheckingPermission : ScanUiState
    data class PermissionRequired(val showRationale: Boolean) : ScanUiState
    /** Denied with "don't ask again": only Settings can fix it. */
    data object PermissionDenied : ScanUiState
    data class Scanning(val torchOn: Boolean, val hint: QrError?) : ScanUiState
    data class Found(val payload: UpiQrPayload) : ScanUiState
    data class Error(val detail: String?) : ScanUiState
}

sealed interface ScanEvent {
    data class PayloadReady(val payload: UpiQrPayload) : ScanEvent
}

@HiltViewModel
class ScanViewModel @Inject constructor(private val savedState: SavedStateHandle) : ViewModel() {
    private val _uiState = MutableStateFlow<ScanUiState>(ScanUiState.CheckingPermission)
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<ScanEvent> = _events.asSharedFlow()

    private var torchOn = false

    fun onPermissionResult(granted: Boolean, canAskAgain: Boolean) {
        _uiState.value = when {
            !granted && canAskAgain -> ScanUiState.PermissionRequired(showRationale = true)
            !granted -> ScanUiState.PermissionDenied
            else -> restoreFound() ?: ScanUiState.Scanning(torchOn, hint = null)
        }
    }

    /** A decoded frame. Ignored unless we are actively scanning. */
    fun onBarcode(raw: String) {
        if (_uiState.value !is ScanUiState.Scanning) return
        when (val result = UpiQrPayloadParser.parse(raw)) {
            is QrParseResult.Invalid -> _uiState.update { (it as? ScanUiState.Scanning)?.copy(hint = result.error) ?: it }
            is QrParseResult.Valid -> {
                savedState[KEY_FOUND_RAW] = raw
                _uiState.value = ScanUiState.Found(result.payload)
                _events.tryEmit(ScanEvent.PayloadReady(result.payload))
            }
        }
    }

    fun onCameraError(error: Throwable) {
        _uiState.value = ScanUiState.Error(error.message)
    }

    fun rescan() {
        savedState.remove<String>(KEY_FOUND_RAW)
        _uiState.value = ScanUiState.Scanning(torchOn, hint = null)
    }

    fun toggleTorch() {
        torchOn = !torchOn
        _uiState.update { (it as? ScanUiState.Scanning)?.copy(torchOn = torchOn) ?: it }
    }

    private fun restoreFound(): ScanUiState? {
        val raw = savedState.get<String>(KEY_FOUND_RAW) ?: return null
        return (UpiQrPayloadParser.parse(raw) as? QrParseResult.Valid)?.let { ScanUiState.Found(it.payload) }
    }

    companion object {
        const val KEY_FOUND_RAW = "foundRaw"
    }
}
