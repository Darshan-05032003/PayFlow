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
import com.darshan.payflow.ui.designsystem.effects.HapticEvent
import com.darshan.payflow.ui.designsystem.effects.UiEffect
import com.darshan.payflow.ui.designsystem.effects.UiEffectChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SetupStep { ENTER, CONFIRM }
enum class LockError { WRONG_PIN, LOCKED_OUT }
enum class UnlockMethod { Pin, Biometric }

sealed interface LockUiState {
    data object Checking : LockUiState
    data class Unlocked(val pinConfigured: Boolean) : LockUiState
    data class Setup(val step: SetupStep, val error: PinRule?, val enteredDigits: Int = 0) : LockUiState
    data class Locked(
        val enteredDigits: Int,
        val remainingAttempts: Int,
        val lockoutRemainingMillis: Long,
        val biometricAvailable: Boolean,
        val biometricMessage: String?,
        val error: LockError?,
        /** Moves with every refused attempt, so the same "wrong PIN" twice still plays its feedback twice. */
        val rejections: Int = 0,
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

    private val effects = UiEffectChannel()
    val uiEffects: Flow<UiEffect> get() = effects.effects

    /** The hosting activity for BiometricPrompt; set by the route, never retained beyond its lifetime. */
    var host: Any? = null

    /** How the last unlock happened. Set before the state changes, so the screen reads it together with [LockUiState.Unlocked]. */
    var lastUnlockMethod: UnlockMethod? = null
        private set

    // Digits stay here rather than in the screen's saved state: a saved-instance bundle leaves the process.
    private val digits = StringBuilder()
    private val setupDigits = StringBuilder()
    private var firstSetupPin: String? = null
    private var pinConfigured = false
    private var verifying = false
    private var biometricInFlight = false
    private var rejections = 0

    init {
        viewModelScope.launch {
            withContext(dispatchers.io) { appLock.refresh() }
            pinConfigured = withContext(dispatchers.io) { pins.isSet() }
            _uiState.value = if (appLock.isLocked.value) lockedState() else LockUiState.Unlocked(pinConfigured)
            appLock.isLocked.collect { locked ->
                val current = _uiState.value
                if (locked && current !is LockUiState.Locked) {
                    digits.clear()
                    setupDigits.clear()
                    firstSetupPin = null
                    // Off the app before anything suspends: the details below take a moment, and a frame can land in between.
                    _uiState.value = LockUiState.Checking
                    _uiState.value = lockedState()
                } else if (!locked && (current is LockUiState.Locked || current is LockUiState.Checking)) {
                    // Only reachable when the PIN turned out to be gone: every real unlock has already moved the state.
                    pinConfigured = withContext(dispatchers.io) { pins.isSet() }
                    _uiState.value = LockUiState.Unlocked(pinConfigured)
                }
            }
        }
        // A PIN set or removed from Security reaches Home's banner at once. Only an unlocked state follows it: opening a lock
        // is the lock flow's job alone.
        viewModelScope.launch {
            pins.changes.drop(1).collect {
                val configured = withContext(dispatchers.io) { pins.isSet() }
                pinConfigured = configured
                _uiState.update { state -> if (state is LockUiState.Unlocked) LockUiState.Unlocked(configured) else state }
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
        rejections = rejections,
    )

    private fun haptic(event: HapticEvent) = effects.send(UiEffect.Haptic(event))

    private fun open(method: UnlockMethod?, configured: Boolean) {
        lastUnlockMethod = method
        pinConfigured = configured
        _uiState.value = LockUiState.Unlocked(configured)
        appLock.unlock()
    }

    // ---- setup

    fun startSetup() {
        if (_uiState.value !is LockUiState.Unlocked) return
        firstSetupPin = null
        setupDigits.clear()
        _uiState.value = LockUiState.Setup(SetupStep.ENTER, error = null)
    }

    fun cancelSetup() {
        if (_uiState.value !is LockUiState.Setup || appLock.isLocked.value) return
        firstSetupPin = null
        setupDigits.clear()
        _uiState.value = LockUiState.Unlocked(pinConfigured)
    }

    fun onSetupDigit(digit: String) {
        if (_uiState.value !is LockUiState.Setup || verifying || digit.length != 1 || !digit[0].isDigit() || setupDigits.length >= PinManager.PIN_LENGTH) return
        setupDigits.append(digit)
        haptic(HapticEvent.Tick)
        _uiState.update { (it as? LockUiState.Setup)?.copy(enteredDigits = setupDigits.length, error = null) ?: it }
        if (setupDigits.length == PinManager.PIN_LENGTH) {
            val pin = setupDigits.toString()
            setupDigits.clear()
            onSetupPin(pin)
        }
    }

    fun onSetupBackspace() {
        if (_uiState.value !is LockUiState.Setup || setupDigits.isEmpty()) return
        setupDigits.setLength(setupDigits.length - 1)
        haptic(HapticEvent.Tick)
        _uiState.update { (it as? LockUiState.Setup)?.copy(enteredDigits = setupDigits.length) ?: it }
    }

    fun onSetupPin(pin: String) {
        val state = _uiState.value as? LockUiState.Setup ?: return
        if (verifying) return
        when (state.step) {
            SetupStep.ENTER -> {
                val rule = PinManager.validateNewPin(pin)
                if (rule != PinRule.Ok) {
                    haptic(HapticEvent.Reject)
                    _uiState.value = LockUiState.Setup(SetupStep.ENTER, rule)
                    return
                }
                firstSetupPin = pin
                _uiState.value = LockUiState.Setup(SetupStep.CONFIRM, error = null)
            }
            SetupStep.CONFIRM -> {
                if (pin != firstSetupPin) {
                    firstSetupPin = null
                    haptic(HapticEvent.Reject)
                    _uiState.value = LockUiState.Setup(SetupStep.ENTER, PinRule.Mismatch)
                    return
                }
                verifying = true
                viewModelScope.launch {
                    try {
                        withContext(dispatchers.io) { pins.set(pin) }
                        haptic(HapticEvent.Confirm)
                        open(method = null, configured = true)
                    } finally {
                        verifying = false
                    }
                }
            }
        }
    }

    // ---- unlock

    fun onPinDigit(digit: String) {
        if (_uiState.value !is LockUiState.Locked || verifying || digit.length != 1 || !digit[0].isDigit() || digits.length >= PinManager.PIN_LENGTH) return
        digits.append(digit)
        haptic(HapticEvent.Tick)
        _uiState.update { (it as? LockUiState.Locked)?.copy(enteredDigits = digits.length, error = null, biometricMessage = null) ?: it }
        if (digits.length == PinManager.PIN_LENGTH) onPinEntered(digits.toString())
    }

    fun onPinBackspace() {
        if (verifying || digits.isEmpty()) return
        digits.setLength(digits.length - 1)
        haptic(HapticEvent.Tick)
        _uiState.update { (it as? LockUiState.Locked)?.copy(enteredDigits = digits.length) ?: it }
    }

    /** One verification at a time: PBKDF2 takes a moment, and a second one would burn another attempt on the same taps. */
    fun onPinEntered(pin: String) {
        if (_uiState.value !is LockUiState.Locked || verifying) return
        verifying = true
        viewModelScope.launch {
            try {
                val result = withContext(dispatchers.io) { pins.verify(pin) }
                digits.clear()
                when (result) {
                    PinVerification.Ok -> {
                        haptic(HapticEvent.Confirm)
                        open(UnlockMethod.Pin, configured = true)
                    }
                    is PinVerification.Wrong -> refuse(LockError.WRONG_PIN)
                    is PinVerification.LockedOut -> refuse(LockError.LOCKED_OUT)
                    PinVerification.NotSet -> open(method = null, configured = false)
                }
            } finally {
                verifying = false
            }
        }
    }

    private suspend fun refuse(error: LockError) {
        rejections++
        haptic(HapticEvent.Reject)
        _uiState.value = lockedState(error = error)
    }

    /** Called by the screen once a second while locked out, so the countdown is clock-driven (no ticker inside the ViewModel). */
    fun onTick() {
        if (_uiState.value !is LockUiState.Locked) return
        viewModelScope.launch {
            val remaining = withContext(dispatchers.io) { pins.lockoutRemainingMillis() }
            _uiState.update { (it as? LockUiState.Locked)?.copy(lockoutRemainingMillis = remaining) ?: it }
        }
    }

    fun onBiometricRequested() {
        if (_uiState.value !is LockUiState.Locked || biometricInFlight) return
        biometricInFlight = true
        viewModelScope.launch {
            try {
                when (val result = biometric.authenticate(host, title = "PayFlow", subtitle = "")) {
                    BiometricResult.Success -> {
                        digits.clear()
                        haptic(HapticEvent.Confirm)
                        open(UnlockMethod.Biometric, configured = true)
                    }
                    is BiometricResult.Failed -> {
                        haptic(HapticEvent.Reject)
                        _uiState.value = lockedState(biometricMessage = result.message)
                    }
                    BiometricResult.Cancelled, BiometricResult.Unavailable -> _uiState.value = lockedState()
                }
            } finally {
                biometricInFlight = false
            }
        }
    }

    @Suppress("unused")
    private fun nowMillis() = clock.millis()
}
