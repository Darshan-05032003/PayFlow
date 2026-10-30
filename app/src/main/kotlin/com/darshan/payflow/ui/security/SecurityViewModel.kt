package com.darshan.payflow.ui.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darshan.payflow.core.DispatcherProvider
import com.darshan.payflow.data.security.BindingStatus
import com.darshan.payflow.data.security.DeviceBindingManager
import com.darshan.payflow.data.security.FakeOtpSender
import com.darshan.payflow.data.security.IntegrityChecker
import com.darshan.payflow.data.security.IntegrityResult
import com.darshan.payflow.data.security.IntegrityVerdict
import com.darshan.payflow.data.security.IntegrityVerifier
import com.darshan.payflow.data.security.OtpRequest
import com.darshan.payflow.data.security.OtpVerification
import com.darshan.payflow.data.security.PinManager
import com.darshan.payflow.data.security.TamperDetector
import com.darshan.payflow.data.security.TamperReport
import com.darshan.payflow.data.upi.UpiAppLauncher
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.repository.PaymentModeRepository
import kotlinx.coroutines.flow.flowOn
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface IntegrityStatus {
    data object NotChecked : IntegrityStatus
    data object Checking : IntegrityStatus
    data object NotConfigured : IntegrityStatus
    data class Result(val verdict: IntegrityVerdict, val sandboxVerifier: Boolean) : IntegrityStatus
    data class Error(val code: String) : IntegrityStatus
}

data class SecurityUiState(
    val loading: Boolean = true,
    val pinConfigured: Boolean = false,
    val binding: BindingStatus = BindingStatus.Unbound,
    val phoneInput: String = "",
    val otpInput: String = "",
    val sandboxOtp: String? = null,
    val bindingError: BindingError? = null,
    val tamper: TamperReport? = null,
    val integrity: IntegrityStatus = IntegrityStatus.NotChecked,
    val busy: Boolean = false,
    /** Null until read from DataStore. */
    val paymentMode: PaymentMode? = null,
    /** Whether UPI-apps mode can be used at all on this device. */
    val upiAppAvailable: Boolean = true,
)

enum class BindingError { INVALID_PHONE, WRONG_OTP, OTP_EXPIRED }

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val pins: PinManager,
    private val binding: DeviceBindingManager,
    private val otpSender: FakeOtpSender,
    private val tamper: TamperDetector,
    private val integrityChecker: IntegrityChecker,
    private val integrityVerifier: IntegrityVerifier,
    private val paymentModes: PaymentModeRepository,
    private val upiAppLauncher: UpiAppLauncher,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SecurityUiState())
    val uiState: StateFlow<SecurityUiState> = _uiState.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            val available = withContext(dispatchers.io) { upiAppLauncher.isUpiAppInstalled() }
            _uiState.update { it.copy(upiAppAvailable = available) }
            paymentModes.mode.flowOn(dispatchers.io).collect { mode -> _uiState.update { it.copy(paymentMode = mode) } }
        }
    }

    /** Only reachable from this screen, which sits behind the app lock once a PIN is set. */
    fun setPaymentMode(mode: PaymentMode) {
        if (mode == PaymentMode.UPI_APPS && !_uiState.value.upiAppAvailable) return
        viewModelScope.launch { withContext(dispatchers.io) { paymentModes.set(mode) } }
    }

    fun refresh() {
        viewModelScope.launch {
            val (pin, status, report) = withContext(dispatchers.io) { Triple(pins.isSet(), binding.status(), tamper.scan()) }
            _uiState.update { it.copy(loading = false, pinConfigured = pin, binding = status, tamper = report) }
        }
    }

    fun onPhoneChange(value: String) = _uiState.update { it.copy(phoneInput = value.take(16), bindingError = null) }
    fun onOtpChange(value: String) = _uiState.update { it.copy(otpInput = value.filter(Char::isDigit).take(6), bindingError = null) }

    fun requestOtp() {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = withContext(dispatchers.io) { binding.requestOtp(_uiState.value.phoneInput) }
            _uiState.update {
                when (result) {
                    OtpRequest.InvalidPhone -> it.copy(busy = false, bindingError = BindingError.INVALID_PHONE)
                    is OtpRequest.Sent -> it.copy(busy = false, binding = BindingStatus.OtpPending(result.maskedPhone), sandboxOtp = otpSender.lastCode, otpInput = "")
                }
            }
        }
    }

    fun verifyOtp() {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = withContext(dispatchers.io) { binding.verifyOtp(_uiState.value.otpInput) }
            val status = withContext(dispatchers.io) { binding.status() }
            _uiState.update {
                when (result) {
                    OtpVerification.Bound -> it.copy(busy = false, binding = status, sandboxOtp = null, otpInput = "", bindingError = null)
                    is OtpVerification.Wrong -> it.copy(busy = false, bindingError = BindingError.WRONG_OTP, otpInput = "")
                    OtpVerification.Expired, OtpVerification.NoPending -> it.copy(busy = false, binding = status, sandboxOtp = null, bindingError = BindingError.OTP_EXPIRED, otpInput = "")
                }
            }
        }
    }

    fun unbind() {
        viewModelScope.launch {
            withContext(dispatchers.io) { binding.unbind() }
            _uiState.update { it.copy(binding = BindingStatus.Unbound, sandboxOtp = null, bindingError = null) }
        }
    }

    fun checkIntegrity() {
        if (_uiState.value.integrity == IntegrityStatus.Checking) return
        _uiState.update { it.copy(integrity = IntegrityStatus.Checking) }
        viewModelScope.launch {
            val status = withContext(dispatchers.io) {
                when (val result = integrityChecker.requestToken(requestHash = "security-screen")) {
                    IntegrityResult.NotConfigured -> IntegrityStatus.NotConfigured
                    is IntegrityResult.Error -> IntegrityStatus.Error(result.code)
                    is IntegrityResult.Token -> IntegrityStatus.Result(integrityVerifier.verify(result.token), integrityVerifier.isSandbox)
                }
            }
            _uiState.update { it.copy(integrity = status) }
        }
    }

    fun removePin() {
        viewModelScope.launch {
            withContext(dispatchers.io) { pins.clear() }
            _uiState.update { it.copy(pinConfigured = false) }
        }
    }
}
