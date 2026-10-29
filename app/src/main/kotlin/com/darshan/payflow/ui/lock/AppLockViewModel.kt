package com.darshan.payflow.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darshan.payflow.core.DispatcherProvider
import com.darshan.payflow.data.security.AppLock
import com.darshan.payflow.data.security.BiometricAuthenticator
import com.darshan.payflow.data.security.BiometricAvailability
import com.darshan.payflow.data.security.BiometricResult
import com.darshan.payflow.data.security.PinManager
import com.darshan.payflow.data.security.PinRule
import com.darshan.payflow.data.security.PinVerification
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SetupStep { ENTER, CONFIRM }
enum class LockError { WRONG_PIN, LOCKED_OUT }

sealed interface LockUiState {
    data object Checking : LockUiState
    data class Unlocked(val pinConfigured: Boolean) : LockUiState
    data class Setup(val step: SetupStep, val error: PinRule?) : LockUiState
    data class Locked(
        val enteredDigits: Int,
        val remainingAttempts: Int,
        val lockoutRemainingMillis: Long,
        val biometricAvailable: Boolean,
        val biometricMessage: String?,
        val error: LockError?,
    ) : LockUiState
}

@HiltViewModel
class AppLockViewModel @Inject constructor(
    private val appLock: AppLock,
    private val pins: PinManager,
    private val biometric: BiometricAuthenticator,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock,
) : ViewModel() {
    private val _uiState = MutableStateFlow<LockUiState>(LockUiState.Checking)
    val uiState: StateFlow<LockUiState> = _uiState.asStateFlow()

    /** The hosting activity for BiometricPrompt; set by the route, never retained beyond its lifetime. */
    var host: Any? = null

    private val digits = StringBuilder()
    private var firstSetupPin: String? = null
    private var pinConfigured = false

    init {
        viewModelScope.launch {
            withContext(dispatchers.io) { appLock.refresh() }
            pinConfigured = withContext(dispatchers.io) { pins.isSet() }
            _uiState.value = if (appLock.isLocked.value) lockedState() else LockUiState.Unlocked(pinConfigured)
            appLock.isLocked.collect { locked ->
                if (locked && _uiState.value is LockUiState.Unlocked) {
                    digits.clear()
                    _uiState.value = lockedState()
                }
            }
        }
    }

    private suspend fun lockedState(error: LockError? = null, biometricMessage: String? = null): LockUiState.Locked = LockUiState.Locked(
        enteredDigits = digits.length,
        remainingAttempts = withContext(dispatchers.io) { pins.remainingAttempts() },
        lockoutRemainingMillis = withContext(dispatchers.io) { pins.lockoutRemainingMillis() },
        biometricAvailable = biometric.availability() == BiometricAvailability.AVAILABLE,
        biometricMessage = biometricMessage,
        error = error,
    )

    // ---- setup

    fun startSetup() {
        firstSetupPin = null
        _uiState.value = LockUiState.Setup(SetupStep.ENTER, error = null)
    }

    fun cancelSetup() {
        firstSetupPin = null
        _uiState.value = LockUiState.Unlocked(pinConfigured)
    }

    fun onSetupPin(pin: String) {
        val state = _uiState.value as? LockUiState.Setup ?: return
        when (state.step) {
            SetupStep.ENTER -> {
                val rule = PinManager.validateNewPin(pin)
                if (rule != PinRule.Ok) { _uiState.value = LockUiState.Setup(SetupStep.ENTER, rule); return }
                firstSetupPin = pin
                _uiState.value = LockUiState.Setup(SetupStep.CONFIRM, error = null)
            }
            SetupStep.CONFIRM -> {
                if (pin != firstSetupPin) { firstSetupPin = null; _uiState.value = LockUiState.Setup(SetupStep.ENTER, PinRule.Mismatch); return }
                viewModelScope.launch {
                    withContext(dispatchers.io) { pins.set(pin) }
                    pinConfigured = true
                    appLock.unlock()
                    _uiState.value = LockUiState.Unlocked(pinConfigured = true)
                }
            }
        }
    }

    // ---- unlock

    fun onPinDigit(digit: String) {
        if (_uiState.value !is LockUiState.Locked || digit.length != 1 || !digit[0].isDigit() || digits.length >= PinManager.PIN_LENGTH) return
        digits.append(digit)
        _uiState.update { (it as? LockUiState.Locked)?.copy(enteredDigits = digits.length, error = null) ?: it }
        if (digits.length == PinManager.PIN_LENGTH) onPinEntered(digits.toString())
    }

    fun onPinBackspace() {
        if (digits.isNotEmpty()) digits.setLength(digits.length - 1)
        _uiState.update { (it as? LockUiState.Locked)?.copy(enteredDigits = digits.length) ?: it }
    }

    fun onPinEntered(pin: String) {
        if (_uiState.value !is LockUiState.Locked) return
        viewModelScope.launch {
            val result = withContext(dispatchers.io) { pins.verify(pin) }
            digits.clear()
            when (result) {
                PinVerification.Ok -> { appLock.unlock(); _uiState.value = LockUiState.Unlocked(pinConfigured = true) }
                is PinVerification.Wrong -> _uiState.value = lockedState(error = LockError.WRONG_PIN)
                is PinVerification.LockedOut -> _uiState.value = lockedState(error = LockError.LOCKED_OUT)
                PinVerification.NotSet -> { appLock.unlock(); _uiState.value = LockUiState.Unlocked(pinConfigured = false) }
            }
        }
    }

    /** Called by the screen once a second while locked out, so the countdown is clock-driven (no ticker inside the ViewModel). */
    fun onTick() {
        if (_uiState.value !is LockUiState.Locked) return
        viewModelScope.launch { _uiState.update { (it as? LockUiState.Locked)?.copy(lockoutRemainingMillis = pins.lockoutRemainingMillis()) ?: it } }
    }

    fun onBiometricRequested() {
        if (_uiState.value !is LockUiState.Locked) return
        viewModelScope.launch {
            when (val result = biometric.authenticate(host, title = "PayFlow", subtitle = "")) {
                BiometricResult.Success -> { appLock.unlock(); _uiState.value = LockUiState.Unlocked(pinConfigured = true) }
                is BiometricResult.Failed -> _uiState.value = lockedState(biometricMessage = result.message)
                BiometricResult.Cancelled, BiometricResult.Unavailable -> _uiState.value = lockedState()
            }
        }
    }

    @Suppress("unused")
    private fun nowMillis() = clock.millis()
}
