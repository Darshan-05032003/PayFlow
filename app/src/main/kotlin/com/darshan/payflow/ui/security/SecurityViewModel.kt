package com.darshan.payflow.ui.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darshan.payflow.core.DispatcherProvider
import com.darshan.payflow.data.security.BindingStatus
import com.darshan.payflow.data.security.BiometricAuthenticator
import com.darshan.payflow.data.security.BiometricAvailability
import com.darshan.payflow.data.security.BiometricResult
import com.darshan.payflow.data.security.DeviceBindingManager
import com.darshan.payflow.data.security.FakeOtpSender
import com.darshan.payflow.data.security.IntegrityChecker
import com.darshan.payflow.data.security.IntegrityResult
import com.darshan.payflow.data.security.IntegrityVerdict
import com.darshan.payflow.data.security.IntegrityVerifier
import com.darshan.payflow.data.security.OtpRequest
import com.darshan.payflow.data.security.OtpVerification
import com.darshan.payflow.data.security.PinManager
import com.darshan.payflow.data.security.PinVerification
import com.darshan.payflow.data.security.TamperDetector
import com.darshan.payflow.data.security.TamperReport
import com.darshan.payflow.data.upi.UpiAppLauncher
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.repository.PaymentModeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
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

enum class PinRemovalError { WRONG_PIN, LOCKED_OUT, BIOMETRIC_FAILED }

/** "Turn off app lock" in progress: it only finishes with the current PIN or a biometric. */
data class PinRemovalState(
    val biometricAvailable: Boolean,
    val remainingAttempts: Int,
    val lockoutRemainingMillis: Long = 0,
    val error: PinRemovalError? = null,
    val verifying: Boolean = false,
    /** Moves with every refused attempt, so the field clears and shakes each time. */
    val attempts: Int = 0,
)

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
    /** Null until asked. */
    val biometric: BiometricAvailability? = null,
    /** Non-null while "turn off app lock" waits for proof. */
    val removal: PinRemovalState? = null,
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
    private val biometric: BiometricAuthenticator,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SecurityUiState())
    val uiState: StateFlow<SecurityUiState> = _uiState.asStateFlow()

    /** The hosting activity for BiometricPrompt; set by the route, never kept beyond its lifetime. */
    var host: Any? = null

    init {
        refresh()
        checkIntegrity()
        viewModelScope.launch {
            val available = withContext(dispatchers.io) { upiAppLauncher.isUpiAppInstalled() }
            _uiState.update { it.copy(upiAppAvailable = available) }
            paymentModes.mode.flowOn(dispatchers.io).collect { mode -> _uiState.update { it.copy(paymentMode = mode) } }
        }
        // A PIN set from the lock's setup, or removed here, shows at once.
        viewModelScope.launch {
            pins.changes.drop(1).collect {
                val configured = withContext(dispatchers.io) { pins.isSet() }
                _uiState.update { it.copy(pinConfigured = configured, removal = it.removal?.takeIf { configured }) }
            }
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
            val availability = withContext(dispatchers.io) { biometric.availability() }
            _uiState.update { it.copy(loading = false, pinConfigured = pin, binding = status, tamper = report, biometric = availability) }
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

    // ---- turning the app lock off. The PIN *is* the lock, so removing it must prove the owner is holding the phone.

    fun startPinRemoval() {
        val state = _uiState.value
        if (!state.pinConfigured || state.removal != null) return
        viewModelScope.launch {
            val (remaining, lockout) = withContext(dispatchers.io) { pins.remainingAttempts() to pins.lockoutRemainingMillis() }
            val bio = withContext(dispatchers.io) { biometric.availability() } == BiometricAvailability.AVAILABLE
            _uiState.update {
                if (!it.pinConfigured || it.removal != null) it
                else it.copy(removal = PinRemovalState(bio, remaining, lockout, error = if (lockout > 0) PinRemovalError.LOCKED_OUT else null))
            }
        }
    }

    fun cancelPinRemoval() = _uiState.update { it.copy(removal = null) }

    /** One verification at a time: PBKDF2 takes a moment, and a second tap would burn another attempt on the same digits. */
    fun confirmPinRemoval(pin: String) {
        if (!beginVerifying()) return
        viewModelScope.launch {
            val result = withContext(dispatchers.io) { pins.verify(pin) }
            when (result) {
                PinVerification.Ok, PinVerification.NotSet -> removePin()
                is PinVerification.Wrong -> refuseRemoval(PinRemovalError.WRONG_PIN)
                is PinVerification.LockedOut -> refuseRemoval(PinRemovalError.LOCKED_OUT)
            }
        }
    }

    fun confirmPinRemovalWithBiometric(title: String) {
        if (_uiState.value.removal?.biometricAvailable != true || !beginVerifying()) return
        viewModelScope.launch {
            when (biometric.authenticate(host, title = title, subtitle = "")) {
                BiometricResult.Success -> removePin()
                is BiometricResult.Failed, BiometricResult.Unavailable -> refuseRemoval(PinRemovalError.BIOMETRIC_FAILED)
                BiometricResult.Cancelled -> _uiState.update { s -> s.copy(removal = s.removal?.copy(verifying = false)) }
            }
        }
    }

    /** Marks the removal busy synchronously, so racing taps can't start a second check. */
    private fun beginVerifying(): Boolean {
        var started = false
        _uiState.update { s ->
            val removal = s.removal
            if (removal == null || removal.verifying) s else s.copy(removal = removal.copy(verifying = true)).also { started = true }
        }
        return started
    }

    private suspend fun removePin() {
        withContext(dispatchers.io) { pins.clear() }
        _uiState.update { it.copy(pinConfigured = false, removal = null) }
    }

    private suspend fun refuseRemoval(error: PinRemovalError) {
        val (remaining, lockout) = withContext(dispatchers.io) { pins.remainingAttempts() to pins.lockoutRemainingMillis() }
        _uiState.update { s ->
            val removal = s.removal ?: return@update s
            val reported = if (lockout > 0 && error == PinRemovalError.WRONG_PIN) PinRemovalError.LOCKED_OUT else error
            s.copy(
                removal = removal.copy(
                    verifying = false,
                    error = reported,
                    remainingAttempts = remaining,
                    lockoutRemainingMillis = lockout,
                    attempts = removal.attempts + 1,
                ),
            )
        }
    }
}
