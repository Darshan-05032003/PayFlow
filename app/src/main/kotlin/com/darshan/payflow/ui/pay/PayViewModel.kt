package com.darshan.payflow.ui.pay

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darshan.payflow.core.DispatcherProvider
import com.darshan.payflow.core.Money
import com.darshan.payflow.data.security.GuardDecision
import com.darshan.payflow.data.security.GuardReason
import com.darshan.payflow.data.security.PaymentGuard
import com.darshan.payflow.data.upi.TxnRefGenerator
import com.darshan.payflow.data.upi.UpiAppLauncher
import com.darshan.payflow.data.upi.UpiLinkBuilder
import com.darshan.payflow.data.upi.UpiLaunchResult
import com.darshan.payflow.data.upi.UpiResponseParser
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.UpiPayRequest
import com.darshan.payflow.domain.model.VpaValidation
import com.darshan.payflow.domain.model.VpaValidator
import com.darshan.payflow.domain.usecase.ConfirmPaymentUseCase
import com.darshan.payflow.domain.usecase.ConfirmResult
import com.darshan.payflow.domain.usecase.PayViaUpiIntentUseCase
import com.darshan.payflow.domain.usecase.PollPaymentStatusUseCase
import com.darshan.payflow.domain.usecase.VerifyVpaUseCase
import com.darshan.payflow.domain.usecase.VpaVerifyResult
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PayFieldError { REQUIRED, INVALID_VPA, VPA_NOT_FOUND, INVALID_AMOUNT, AMOUNT_TOO_LARGE }

/** Result of the PSP directory lookup for the typed VPA. */
sealed interface VpaVerificationState {
    data object Idle : VpaVerificationState
    data object Verifying : VpaVerificationState
    data class Verified(val payeeName: String) : VpaVerificationState
    data object NotFound : VpaVerificationState
    /** PSP unreachable: paying is allowed with the typed name, with a warning. */
    data object Unavailable : VpaVerificationState
}

data class PayFormErrors(val vpa: PayFieldError? = null, val name: PayFieldError? = null, val amount: PayFieldError? = null) {
    val hasErrors: Boolean get() = vpa != null || name != null || amount != null
}

sealed interface PayPhase {
    data object Idle : PayPhase
    data object Submitting : PayPhase
    data class AwaitingApp(val paymentId: String) : PayPhase
    data class Confirming(val paymentId: String) : PayPhase
    data class Done(val paymentId: String, val status: PaymentStatus, val reason: String?) : PayPhase
    data class Unresolved(val paymentId: String) : PayPhase
}

data class PayUiState(
    val vpa: String = "",
    val name: String = "",
    val amountText: String = "",
    val note: String = "",
    val errors: PayFormErrors = PayFormErrors(),
    val phase: PayPhase = PayPhase.Idle,
    /** From a scanned QR: payee VPA and name cannot be edited. */
    val payeeLocked: Boolean = false,
    /** From a merchant QR with a fixed amount. */
    val amountLocked: Boolean = false,
    val verification: VpaVerificationState = VpaVerificationState.Idle,
    /** A pending result is being polled with backoff. */
    val polling: Boolean = false,
) {
    val isBusy: Boolean get() = phase is PayPhase.Submitting || phase is PayPhase.Confirming
    /** The registered name replaces whatever was typed or scanned; it cannot be edited. */
    val nameLocked: Boolean get() = payeeLocked || verification is VpaVerificationState.Verified
}

sealed interface PayEvent {
    data class LaunchUpiApp(val upiUri: String) : PayEvent
    data object NoUpiApp : PayEvent
    /** Device checks (root / hooking / tampered build / integrity) refused the payment. */
    data class Blocked(val reasons: List<GuardReason>) : PayEvent
    data class Warning(val reasons: List<GuardReason>) : PayEvent
}

@HiltViewModel
class PayViewModel @Inject constructor(
    private val payViaUpiIntent: PayViaUpiIntentUseCase,
    private val confirmPayment: ConfirmPaymentUseCase,
    private val verifyVpa: VerifyVpaUseCase,
    private val pollPaymentStatus: PollPaymentStatusUseCase,
    private val paymentGuard: PaymentGuard,
    private val upiAppLauncher: UpiAppLauncher,
    private val dispatchers: DispatcherProvider,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    // Declared before _uiState: Kotlin initialises properties in order and restore() reads these.
    private val fromQr: Boolean = savedState.get<String>(ARG_SOURCE) == SOURCE_QR
    private val method: PaymentMethod = if (fromQr) PaymentMethod.QR_SCAN else PaymentMethod.UPI_INTENT

    private val _uiState = MutableStateFlow(restore())
    val uiState: StateFlow<PayUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<PayEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<PayEvent> = _events.asSharedFlow()

    private var verifyJob: Job? = null

    init {
        // A scanned payee is verified immediately so the registered name replaces the QR's self-declared text.
        if (fromQr && _uiState.value.vpa.isNotBlank() && _uiState.value.phase == PayPhase.Idle) verifyJob = viewModelScope.launch { runVerification() }
    }

    /** Form fields and the in-flight payment id live in [SavedStateHandle]; route arguments (QR prefill) share the same keys. */
    private fun restore(): PayUiState {
        val inFlight = savedState.get<String>(KEY_IN_FLIGHT)
        val prefillAmount = savedState.get<Long>(ARG_PREFILL_AMOUNT)?.takeIf { it > 0 }
        val vpa = savedState.get<String>(KEY_VPA) ?: ""
        return PayUiState(
            vpa = vpa,
            name = savedState.get<String>(KEY_NAME)?.takeIf { it.isNotBlank() } ?: if (fromQr) vpa else "",
            amountText = savedState.get<String>(KEY_AMOUNT) ?: prefillAmount?.let(UpiLinkBuilder::formatAmount) ?: "",
            note = savedState[KEY_NOTE] ?: "",
            phase = if (inFlight != null) PayPhase.AwaitingApp(inFlight) else PayPhase.Idle,
            payeeLocked = fromQr && vpa.isNotBlank(),
            amountLocked = fromQr && prefillAmount != null,
        )
    }

    fun onVpaChange(value: String) {
        if (_uiState.value.payeeLocked) return
        verifyJob?.cancel()
        updateForm { copy(vpa = value.take(MAX_FIELD), errors = errors.copy(vpa = null), verification = VpaVerificationState.Idle) }
        savedState[KEY_VPA] = _uiState.value.vpa
        if (VpaValidator.isValid(value)) {
            verifyJob = viewModelScope.launch {
                delay(VERIFY_DEBOUNCE_MS)
                runVerification()
            }
        }
    }

    /** Looks the VPA up at the PSP and applies the result to the form (registered name wins). */
    private suspend fun runVerification(): VpaVerificationState {
        val vpa = _uiState.value.vpa
        _uiState.update { it.copy(verification = VpaVerificationState.Verifying) }
        val result = withContext(dispatchers.io) { verifyVpa(vpa) }
        if (_uiState.value.vpa != vpa) return _uiState.value.verification // user kept typing; a newer job owns the field
        val state = when (result) {
            is VpaVerifyResult.Verified -> VpaVerificationState.Verified(result.payeeName)
            is VpaVerifyResult.NotFound -> VpaVerificationState.NotFound
            is VpaVerifyResult.Unavailable -> VpaVerificationState.Unavailable
            is VpaVerifyResult.InvalidFormat -> VpaVerificationState.Idle
        }
        _uiState.update {
            if (state is VpaVerificationState.Verified) it.copy(verification = state, name = state.payeeName, errors = it.errors.copy(name = null, vpa = null))
            else it.copy(verification = state)
        }
        if (state is VpaVerificationState.Verified) savedState[KEY_NAME] = state.payeeName
        return state
    }

    fun onNameChange(value: String) {
        if (_uiState.value.nameLocked) return
        updateForm { copy(name = value.take(MAX_FIELD), errors = errors.copy(name = null)) }
        savedState[KEY_NAME] = _uiState.value.name
    }
    fun onNoteChange(value: String) = updateForm { copy(note = value.take(UpiPayRequest.MAX_NOTE_LENGTH)) }.also { savedState[KEY_NOTE] = _uiState.value.note }

    /** Accepts digits with at most two decimals and at most 7 integer digits; anything else leaves the field unchanged. */
    fun onAmountChange(value: String) {
        if (_uiState.value.amountLocked) return
        if (value.isNotEmpty() && !AMOUNT_INPUT.matches(value)) return
        updateForm { copy(amountText = value, errors = errors.copy(amount = null)) }
        savedState[KEY_AMOUNT] = value
    }

    private fun updateForm(transform: PayUiState.() -> PayUiState) {
        _uiState.update { if (it.phase == PayPhase.Idle) it.transform() else it }
    }

    fun submit() {
        if (_uiState.value.phase != PayPhase.Idle) return // double tap / already in flight
        _uiState.update { it.copy(phase = PayPhase.Submitting) }
        viewModelScope.launch {
            // Make sure the payee has been looked up before money moves (the debounce may not have fired yet).
            val vpaOk = VpaValidator.isValid(_uiState.value.vpa)
            if (vpaOk && _uiState.value.verification.let { it is VpaVerificationState.Idle || it is VpaVerificationState.Verifying }) {
                verifyJob?.cancel()
                runVerification()
            }
            val state = _uiState.value
            val errors = validate(state)
            if (errors.hasErrors) {
                _uiState.update { it.copy(errors = errors, phase = PayPhase.Idle) }
                return@launch
            }
            if (!upiAppLauncher.isUpiAppInstalled()) {
                _uiState.update { it.copy(phase = PayPhase.Idle) }
                _events.tryEmit(PayEvent.NoUpiApp)
                return@launch
            }
            val request = UpiPayRequest(
                payeeVpa = (VpaValidator.validate(state.vpa) as VpaValidation.Valid).normalized,
                payeeName = state.name,
                amountPaise = Money.parseInrToPaise(state.amountText)!!,
                note = state.note,
                txnRef = TxnRefGenerator.next(),
            )
            // One idempotency key per attempt, persisted *before* anything is sent: a retry or a relaunch after process death
            // reuses it and can never create a second payment.
            val idempotencyKey = savedState.get<String>(KEY_IDEMPOTENCY) ?: UUID.randomUUID().toString().also { savedState[KEY_IDEMPOTENCY] = it }
            // Device checks before any money moves: a rooted / hooked / tampered device cannot pay; warnings are shown, not fatal.
            when (val guard = withContext(dispatchers.io) { paymentGuard.evaluate(idempotencyKey, request.amountPaise, request.payeeVpa) }) {
                is GuardDecision.Block -> {
                    _uiState.update { it.copy(phase = PayPhase.Idle) }
                    _events.tryEmit(PayEvent.Blocked(guard.reasons))
                    return@launch
                }
                is GuardDecision.Warn -> _events.tryEmit(PayEvent.Warning(guard.reasons))
                GuardDecision.Allow -> Unit
            }
            val result = withContext(dispatchers.io) { payViaUpiIntent(request, method, idempotencyKey) }
            result.onSuccess { launch ->
                savedState[KEY_IN_FLIGHT] = launch.paymentId
                _uiState.update { it.copy(phase = PayPhase.AwaitingApp(launch.paymentId)) }
                _events.tryEmit(PayEvent.LaunchUpiApp(launch.upiUri))
            }.onFailure { e ->
                savedState.remove<String>(KEY_IDEMPOTENCY) // this attempt is spent; the next one needs a fresh key
                _uiState.update { it.copy(phase = PayPhase.Done("", PaymentStatus.FAILED, e.message)) }
            }
        }
    }

    /** The UPI app returned. Whatever it said, the PSP decides. */
    fun onUpiAppResult(result: UpiLaunchResult) {
        val paymentId = (_uiState.value.phase as? PayPhase.AwaitingApp)?.paymentId ?: return
        val appResponse = (result as? UpiLaunchResult.Responded)?.let { UpiResponseParser.parse(it.raw) }
        confirm(paymentId, appResponse)
    }

    /** Re-asks the PSP for a pending / unresolved payment. */
    fun checkStatus() {
        val paymentId = when (val phase = _uiState.value.phase) {
            is PayPhase.Done -> phase.paymentId.takeIf { phase.status == PaymentStatus.PENDING }
            is PayPhase.Unresolved -> phase.paymentId
            is PayPhase.AwaitingApp -> phase.paymentId
            else -> null
        } ?: return
        confirm(paymentId, null)
    }

    private fun confirm(paymentId: String, appResponse: com.darshan.payflow.data.upi.UpiAppResponse?) {
        _uiState.update { it.copy(phase = PayPhase.Confirming(paymentId)) }
        viewModelScope.launch {
            val result = withContext(dispatchers.io) { confirmPayment(paymentId, appResponse) }
            savedState.remove<String>(KEY_IN_FLIGHT)
            savedState.remove<String>(KEY_IDEMPOTENCY)
            _uiState.update { it.copy(phase = result.toPhase(paymentId)) }
            if (result is ConfirmResult.Pending || (result is ConfirmResult.Unresolved && result.error.retryable)) startPolling(paymentId)
        }
    }

    /** Keeps asking the PSP with exponential backoff + jitter (single-flight in the use case) until the payment settles. */
    private fun startPolling(paymentId: String) {
        if (_uiState.value.polling) return
        _uiState.update { it.copy(polling = true) }
        viewModelScope.launch {
            val result = withContext(dispatchers.io) { pollPaymentStatus(paymentId) }
            _uiState.update { it.copy(phase = result.toPhase(paymentId), polling = false) }
        }
    }

    private fun ConfirmResult.toPhase(paymentId: String): PayPhase = when (this) {
        is ConfirmResult.Success -> PayPhase.Done(paymentId, PaymentStatus.SUCCESS, null)
        is ConfirmResult.Failed -> PayPhase.Done(paymentId, PaymentStatus.FAILED, payment.failureReason)
        is ConfirmResult.Pending -> PayPhase.Done(paymentId, PaymentStatus.PENDING, null)
        is ConfirmResult.Unresolved, is ConfirmResult.Mismatch -> PayPhase.Unresolved(paymentId)
        ConfirmResult.NotFound -> PayPhase.Done(paymentId, PaymentStatus.FAILED, null)
    }

    fun reset() {
        savedState.remove<String>(KEY_IN_FLIGHT)
        savedState.remove<String>(KEY_IDEMPOTENCY)
        listOf(KEY_VPA, KEY_NAME, KEY_AMOUNT, KEY_NOTE, ARG_PREFILL_AMOUNT, ARG_SOURCE).forEach { savedState.remove<Any>(it) }
        _uiState.value = PayUiState()
    }

    private fun validate(state: PayUiState): PayFormErrors {
        val vpaError = when (VpaValidator.validate(state.vpa)) {
            is VpaValidation.Valid -> if (state.verification is VpaVerificationState.NotFound) PayFieldError.VPA_NOT_FOUND else null
            is VpaValidation.Invalid -> if (state.vpa.isBlank()) PayFieldError.REQUIRED else PayFieldError.INVALID_VPA
        }
        val nameError = if (state.name.isBlank()) PayFieldError.REQUIRED else null
        val paise = Money.parseInrToPaise(state.amountText)
        val amountError = when {
            state.amountText.isBlank() -> PayFieldError.REQUIRED
            paise == null || paise <= 0 -> PayFieldError.INVALID_AMOUNT
            paise > Payment.MAX_AMOUNT_PAISE -> PayFieldError.AMOUNT_TOO_LARGE
            else -> null
        }
        return PayFormErrors(vpaError, nameError, amountError)
    }

    companion object {
        const val KEY_IN_FLIGHT = "inFlightPaymentId"
        const val KEY_IDEMPOTENCY = "idempotencyKey"
        const val KEY_VPA = "vpa"
        const val KEY_NAME = "name"
        const val KEY_AMOUNT = "amount"
        const val KEY_NOTE = "note"
        const val MAX_FIELD = 256
        // Route arguments of PayDestination (same keys as the form fields where they overlap).
        const val ARG_PREFILL_VPA = KEY_VPA
        const val ARG_PREFILL_NAME = KEY_NAME
        const val ARG_PREFILL_AMOUNT = "amountPaise"
        const val ARG_PREFILL_NOTE = KEY_NOTE
        const val ARG_SOURCE = "source"
        const val SOURCE_QR = "qr"
        const val VERIFY_DEBOUNCE_MS = 600L
        private val AMOUNT_INPUT = Regex("""^\d{0,7}(\.\d{0,2})?$""")
    }
}
