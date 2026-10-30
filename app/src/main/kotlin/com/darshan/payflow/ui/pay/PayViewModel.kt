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
import com.darshan.payflow.data.upi.UpiApp
import com.darshan.payflow.data.upi.UpiAppLauncher
import com.darshan.payflow.data.upi.UpiAppResponse
import com.darshan.payflow.data.upi.UpiLinkBuilder
import com.darshan.payflow.data.upi.UpiLaunchResult
import com.darshan.payflow.data.upi.UpiResponseParser
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.UpiPayRequest
import com.darshan.payflow.domain.model.VpaValidation
import com.darshan.payflow.domain.model.VpaValidator
import com.darshan.payflow.domain.repository.PaymentModeRepository
import com.darshan.payflow.domain.usecase.ConfirmPaymentUseCase
import com.darshan.payflow.domain.usecase.ConfirmResult
import com.darshan.payflow.domain.usecase.ManualMarkResult
import com.darshan.payflow.domain.usecase.MarkPaymentManuallyUseCase
import com.darshan.payflow.domain.usecase.PayViaUpiIntentUseCase
import com.darshan.payflow.domain.usecase.PollPaymentStatusUseCase
import com.darshan.payflow.domain.usecase.RecordUpiAppResultUseCase
import com.darshan.payflow.domain.usecase.UpiAppResult
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
    /** UPI-apps mode: there is no directory to ask; the UPI app shows the registered name before the user confirms. */
    data object Skipped : VpaVerificationState
}

data class PayFormErrors(val vpa: PayFieldError? = null, val name: PayFieldError? = null, val amount: PayFieldError? = null) {
    val hasErrors: Boolean get() = vpa != null || name != null || amount != null
}

sealed interface PayPhase {
    data object Idle : PayPhase
    data object Submitting : PayPhase
    /** UPI-apps mode: PayFlow's own sheet lists the installed UPI apps; nothing is stored until one is picked. */
    data class ChoosingApp(val apps: List<UpiApp>) : PayPhase
    data class AwaitingApp(val paymentId: String) : PayPhase
    data class Confirming(val paymentId: String) : PayPhase
    /**
     * The attempt has an outcome. [payment] is the stored row (receipt details, status source, handler app); it is null only when
     * the attempt failed before a row existed. [handlerLabel] is the UPI app's display name when known.
     */
    data class Done(
        val paymentId: String,
        val status: PaymentStatus,
        val reason: String?,
        val payment: Payment? = null,
        val handlerLabel: String? = null,
    ) : PayPhase
    data class Unresolved(val paymentId: String) : PayPhase
}

data class PayUiState(
    val vpa: String = "",
    val name: String = "",
    val amountText: String = "",
    val note: String = "",
    val errors: PayFormErrors = PayFormErrors(),
    val phase: PayPhase = PayPhase.Idle,
    /** From a scanned QR or an incoming link: payee VPA and name cannot be edited. */
    val payeeLocked: Boolean = false,
    /** From a merchant QR / link with a fixed amount. */
    val amountLocked: Boolean = false,
    val verification: VpaVerificationState = VpaVerificationState.Idle,
    /** A pending result is being polled with backoff. */
    val polling: Boolean = false,
    /** Null until the persisted setting has been read. */
    val paymentMode: PaymentMode? = null,
) {
    val isBusy: Boolean get() = phase is PayPhase.Submitting || phase is PayPhase.Confirming
    /** The registered name replaces whatever was typed or scanned; it cannot be edited. */
    val nameLocked: Boolean get() = payeeLocked || verification is VpaVerificationState.Verified
    /** Paise for the typed amount, when it parses. */
    val amountPaise: Long? get() = Money.parseInrToPaise(amountText)
}

sealed interface PayEvent {
    /** Open the UPI app: [packageName] pins the app the user picked, null means the system chooser. */
    data class LaunchUpiApp(val upiUri: String, val packageName: String? = null) : PayEvent
    data object NoUpiApp : PayEvent
    /** Device checks (root / hooking / tampered build / integrity) refused the payment. */
    data class Blocked(val reasons: List<GuardReason>) : PayEvent
    data class Warning(val reasons: List<GuardReason>) : PayEvent
    /** Plain-text receipt for the share sheet. */
    data class ShareReceipt(val text: String) : PayEvent
}

@HiltViewModel
class PayViewModel @Inject constructor(
    private val payViaUpiIntent: PayViaUpiIntentUseCase,
    private val confirmPayment: ConfirmPaymentUseCase,
    private val recordUpiAppResult: RecordUpiAppResultUseCase,
    private val markPaymentManually: MarkPaymentManuallyUseCase,
    private val verifyVpa: VerifyVpaUseCase,
    private val pollPaymentStatus: PollPaymentStatusUseCase,
    private val paymentGuard: PaymentGuard,
    private val paymentModes: PaymentModeRepository,
    private val upiAppLauncher: UpiAppLauncher,
    private val dispatchers: DispatcherProvider,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    // Declared before _uiState: Kotlin initialises properties in order and restore() reads these.
    private val source: String? = savedState.get<String>(ARG_SOURCE)
    private val fromExternal: Boolean = source == SOURCE_QR || source == SOURCE_LINK
    /** A payee handed in by the route (QR, link, recent payee) is verified as soon as the mode is known. */
    private val prefilledPayee: Boolean = source != null && !savedState.get<String>(KEY_VPA).isNullOrBlank()
    private val method: PaymentMethod = when (source) {
        SOURCE_QR -> PaymentMethod.QR_SCAN
        SOURCE_LINK -> PaymentMethod.DEEP_LINK
        else -> PaymentMethod.UPI_INTENT
    }

    private val _uiState = MutableStateFlow(restore())
    val uiState: StateFlow<PayUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<PayEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<PayEvent> = _events.asSharedFlow()

    private var verifyJob: Job? = null

    init {
        viewModelScope.launch {
            val mode = withContext(dispatchers.io) { paymentModes.current() }
            _uiState.update { it.copy(paymentMode = mode) }
            val state = _uiState.value
            when {
                state.phase != PayPhase.Idle -> Unit
                // No directory in UPI-apps mode: the UPI app itself shows the registered payee before the user confirms.
                mode == PaymentMode.UPI_APPS -> _uiState.update { it.copy(verification = VpaVerificationState.Skipped) }
                // A prefilled payee (QR, link, recent payee) is verified immediately so the registered name replaces the given text.
                prefilledPayee && VpaValidator.isValid(state.vpa) && state.verification == VpaVerificationState.Idle -> verifyJob = launch { runVerification() }
            }
        }
    }

    /** Form fields and the in-flight payment id live in [SavedStateHandle]; route arguments (QR / link prefill) share the same keys. */
    private fun restore(): PayUiState {
        val inFlight = savedState.get<String>(KEY_IN_FLIGHT)
        val prefillAmount = savedState.get<Long>(ARG_PREFILL_AMOUNT)?.takeIf { it > 0 }
        val vpa = savedState.get<String>(KEY_VPA) ?: ""
        return PayUiState(
            vpa = vpa,
            name = savedState.get<String>(KEY_NAME)?.takeIf { it.isNotBlank() } ?: if (fromExternal) vpa else "",
            amountText = savedState.get<String>(KEY_AMOUNT) ?: prefillAmount?.let(UpiLinkBuilder::formatAmount) ?: "",
            note = savedState[KEY_NOTE] ?: "",
            phase = if (inFlight != null) PayPhase.AwaitingApp(inFlight) else PayPhase.Idle,
            payeeLocked = fromExternal && vpa.isNotBlank(),
            amountLocked = fromExternal && prefillAmount != null,
        )
    }

    fun onVpaChange(value: String) {
        if (_uiState.value.payeeLocked) return
        verifyJob?.cancel()
        val skipped = _uiState.value.paymentMode == PaymentMode.UPI_APPS
        updateForm { copy(vpa = value.take(MAX_FIELD), errors = errors.copy(vpa = null), verification = if (skipped) VpaVerificationState.Skipped else VpaVerificationState.Idle) }
        savedState[KEY_VPA] = _uiState.value.vpa
        if (!skipped && VpaValidator.isValid(value)) {
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

    /**
     * Validates the form. Sandbox: verify the payee at the PSP, then register and launch. UPI apps: list the installed apps in
     * PayFlow's own sheet; the payment is created only once an app is picked ([onAppChosen]).
     */
    fun submit() {
        if (_uiState.value.phase != PayPhase.Idle) return // double tap / already in flight
        _uiState.update { it.copy(phase = PayPhase.Submitting) }
        viewModelScope.launch {
            val mode = currentMode()
            // Make sure the payee has been looked up before money moves (the debounce may not have fired yet).
            val vpaOk = VpaValidator.isValid(_uiState.value.vpa)
            if (mode == PaymentMode.SANDBOX && vpaOk && _uiState.value.verification.let { it is VpaVerificationState.Idle || it is VpaVerificationState.Verifying }) {
                verifyJob?.cancel()
                runVerification()
            }
            val errors = validate(_uiState.value, mode)
            if (errors.hasErrors) {
                _uiState.update { it.copy(errors = errors, phase = PayPhase.Idle) }
                return@launch
            }
            if (!upiAppLauncher.isUpiAppInstalled()) {
                _uiState.update { it.copy(phase = PayPhase.Idle) }
                _events.tryEmit(PayEvent.NoUpiApp)
                return@launch
            }
            if (mode == PaymentMode.UPI_APPS) {
                val apps = upiAppLauncher.installedUpiApps()
                // Package visibility can hide the list while a handler still exists: fall back to the system chooser.
                if (apps.isEmpty()) startPayment(mode, packageName = null) else _uiState.update { it.copy(phase = PayPhase.ChoosingApp(apps)) }
            } else {
                startPayment(mode, packageName = null)
            }
        }
    }

    /** The user picked an app in PayFlow's sheet. */
    fun onAppChosen(packageName: String) {
        val phase = _uiState.value.phase as? PayPhase.ChoosingApp ?: return
        if (phase.apps.none { it.packageName == packageName }) return
        _uiState.update { it.copy(phase = PayPhase.Submitting) }
        viewModelScope.launch { startPayment(PaymentMode.UPI_APPS, packageName) }
    }

    fun dismissChooser() {
        if (_uiState.value.phase is PayPhase.ChoosingApp) _uiState.update { it.copy(phase = PayPhase.Idle) }
    }

    private suspend fun startPayment(mode: PaymentMode, packageName: String?) {
        val state = _uiState.value
        val vpa = (VpaValidator.validate(state.vpa) as VpaValidation.Valid).normalized
        val request = UpiPayRequest(
            payeeVpa = vpa,
            // UPI apps show the registered name themselves; a blank name falls back to the handle so the link stays valid.
            payeeName = state.name.ifBlank { vpa.substringBefore('@') },
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
                return
            }
            is GuardDecision.Warn -> _events.tryEmit(PayEvent.Warning(guard.reasons))
            GuardDecision.Allow -> Unit
        }
        val result = withContext(dispatchers.io) { payViaUpiIntent(request, method, idempotencyKey, mode, packageName) }
        result.onSuccess { launch ->
            savedState[KEY_IN_FLIGHT] = launch.paymentId
            launch.packageName?.let { savedState[KEY_HANDLER] = it }
            _uiState.update { it.copy(phase = PayPhase.AwaitingApp(launch.paymentId)) }
            _events.tryEmit(PayEvent.LaunchUpiApp(launch.upiUri, launch.packageName))
        }.onFailure { e ->
            savedState.remove<String>(KEY_IDEMPOTENCY) // this attempt is spent; the next one needs a fresh key
            _uiState.update { it.copy(phase = PayPhase.Done("", PaymentStatus.FAILED, e.message)) }
        }
    }

    /** The UPI app returned. Sandbox: whatever it said, the PSP decides. UPI apps: its answer is recorded as *reported*. */
    fun onUpiAppResult(result: UpiLaunchResult) {
        val paymentId = (_uiState.value.phase as? PayPhase.AwaitingApp)?.paymentId ?: return
        _uiState.update { it.copy(phase = PayPhase.Confirming(paymentId)) }
        viewModelScope.launch {
            if (currentMode() == PaymentMode.UPI_APPS) {
                val recorded = withContext(dispatchers.io) { recordUpiAppResult(paymentId, result, appLabelFor()) }
                finishAttempt()
                _uiState.update { it.copy(phase = recorded.toPhase(paymentId)) }
            } else {
                val appResponse = (result as? UpiLaunchResult.Responded)?.let { UpiResponseParser.parse(it.raw) }
                confirmNow(paymentId, appResponse)
            }
        }
    }

    /** Display name of the app that was launched for the in-flight attempt (kept in saved state so it survives process death). */
    private suspend fun appLabelFor(): String? =
        savedState.get<String>(KEY_HANDLER)?.let { pkg -> withContext(dispatchers.io) { upiAppLauncher.appLabel(pkg) } }

    /** Re-asks the PSP for a pending / unresolved payment (sandbox only; UPI-apps payments have no PSP to ask). */
    fun checkStatus() {
        val paymentId = when (val phase = _uiState.value.phase) {
            is PayPhase.Done -> phase.paymentId.takeIf { phase.status == PaymentStatus.PENDING }
            is PayPhase.Unresolved -> phase.paymentId
            is PayPhase.AwaitingApp -> phase.paymentId
            else -> null
        } ?: return
        if ((_uiState.value.phase as? PayPhase.Done)?.payment?.handlerPackage != null) return
        _uiState.update { it.copy(phase = PayPhase.Confirming(paymentId)) }
        viewModelScope.launch { confirmNow(paymentId, null) }
    }

    private suspend fun confirmNow(paymentId: String, appResponse: UpiAppResponse?) {
        val result = withContext(dispatchers.io) { confirmPayment(paymentId, appResponse) }
        finishAttempt()
        _uiState.update { it.copy(phase = result.toPhase(paymentId)) }
        if (result is ConfirmResult.Pending || (result is ConfirmResult.Unresolved && result.error.retryable)) startPolling(paymentId)
    }

    /** UPI-apps mode: the user checked their UPI app and settles a pending attempt by hand. */
    fun markManually(paid: Boolean) {
        val done = _uiState.value.phase as? PayPhase.Done ?: return
        if (done.payment?.canBeMarkedManually != true) return
        viewModelScope.launch {
            val result = withContext(dispatchers.io) { markPaymentManually(done.paymentId, paid) }
            val payment = when (result) {
                is ManualMarkResult.Marked -> result.payment
                is ManualMarkResult.NotAllowed -> result.payment
                ManualMarkResult.NotFound -> return@launch
            }
            _uiState.update { it.copy(phase = PayPhase.Done(payment.id, payment.status, payment.failureReason, payment, done.handlerLabel)) }
        }
    }

    fun shareReceipt() {
        val done = _uiState.value.phase as? PayPhase.Done ?: return
        val payment = done.payment ?: return
        _events.tryEmit(PayEvent.ShareReceipt(receiptText(payment, done.handlerLabel)))
    }

    /** Keeps the payee and amount, drops the attempt: a new idempotency key and reference will be used. */
    fun payAgain() {
        if (_uiState.value.phase !is PayPhase.Done) return
        finishAttempt()
        val mode = _uiState.value.paymentMode
        _uiState.update {
            it.copy(
                phase = PayPhase.Idle, errors = PayFormErrors(), polling = false,
                verification = if (mode == PaymentMode.UPI_APPS) VpaVerificationState.Skipped else it.verification,
            )
        }
    }

    private fun finishAttempt() {
        savedState.remove<String>(KEY_IN_FLIGHT)
        savedState.remove<String>(KEY_IDEMPOTENCY)
        savedState.remove<String>(KEY_HANDLER)
    }

    private suspend fun currentMode(): PaymentMode =
        _uiState.value.paymentMode ?: withContext(dispatchers.io) { paymentModes.current() }.also { mode -> _uiState.update { it.copy(paymentMode = mode) } }

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
        is ConfirmResult.Success -> PayPhase.Done(paymentId, PaymentStatus.SUCCESS, null, payment)
        is ConfirmResult.Failed -> PayPhase.Done(paymentId, PaymentStatus.FAILED, payment.failureReason, payment)
        is ConfirmResult.Pending -> PayPhase.Done(paymentId, PaymentStatus.PENDING, null, payment)
        is ConfirmResult.Unresolved, is ConfirmResult.Mismatch -> PayPhase.Unresolved(paymentId)
        ConfirmResult.NotFound -> PayPhase.Done(paymentId, PaymentStatus.FAILED, null)
    }

    private suspend fun UpiAppResult.toPhase(paymentId: String): PayPhase {
        val payment = this.payment ?: return PayPhase.Done(paymentId, PaymentStatus.FAILED, null)
        val label = payment.handlerPackage?.let { pkg -> withContext(dispatchers.io) { upiAppLauncher.appLabel(pkg) } }
        return when (this) {
            is UpiAppResult.ReportedSuccess, is UpiAppResult.ReportedFailure, is UpiAppResult.AlreadySettled, is UpiAppResult.Unknown ->
                PayPhase.Done(paymentId, payment.status, payment.failureReason, payment, label)
            is UpiAppResult.Mismatch -> PayPhase.Done(paymentId, payment.status, MISMATCH_REASON, payment, label)
            UpiAppResult.NotFound -> PayPhase.Done(paymentId, PaymentStatus.FAILED, null)
        }
    }

    fun reset() {
        finishAttempt()
        listOf(KEY_VPA, KEY_NAME, KEY_AMOUNT, KEY_NOTE, ARG_PREFILL_AMOUNT, ARG_SOURCE).forEach { savedState.remove<Any>(it) }
        val mode = _uiState.value.paymentMode
        _uiState.value = PayUiState(paymentMode = mode, verification = if (mode == PaymentMode.UPI_APPS) VpaVerificationState.Skipped else VpaVerificationState.Idle)
    }

    private fun validate(state: PayUiState, mode: PaymentMode): PayFormErrors {
        val vpaError = when (VpaValidator.validate(state.vpa)) {
            is VpaValidation.Valid -> if (state.verification is VpaVerificationState.NotFound) PayFieldError.VPA_NOT_FOUND else null
            is VpaValidation.Invalid -> if (state.vpa.isBlank()) PayFieldError.REQUIRED else PayFieldError.INVALID_VPA
        }
        // The UPI app shows the bank-registered name; PayFlow's name field is optional there.
        val nameError = if (state.name.isBlank() && mode == PaymentMode.SANDBOX) PayFieldError.REQUIRED else null
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
        const val KEY_HANDLER = "handlerPackage"
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
        /** An incoming `upi://pay` link from another app (browser, chat): payee locked like a QR. */
        const val SOURCE_LINK = "link"
        /** A recent payee tapped on Home: prefilled, not locked. */
        const val SOURCE_PAYEE = "payee"
        const val VERIFY_DEBOUNCE_MS = 600L
        const val MISMATCH_REASON = "The UPI app answered for a different payment reference; that answer was ignored."
        private val AMOUNT_INPUT = Regex("""^\d{0,7}(\.\d{0,2})?$""")

        /** Plain-text receipt. Says who reported the status; never claims verification PayFlow cannot provide. */
        fun receiptText(payment: Payment, handlerLabel: String?): String = buildString {
            append("PayFlow receipt\n")
            append("To: ").append(payment.payeeName).append(" (").append(payment.payeeVpa).append(")\n")
            append("Amount: ").append(Money.formatInr(payment.amountPaise)).append('\n')
            append("Status: ").append(payment.status.name.lowercase().replaceFirstChar(Char::uppercase))
            when (payment.statusSource) {
                com.darshan.payflow.domain.model.StatusSource.UPI_APP -> append(" (reported by ").append(handlerLabel ?: payment.handlerPackage ?: "UPI app").append(")")
                com.darshan.payflow.domain.model.StatusSource.USER -> append(" (marked by you)")
                com.darshan.payflow.domain.model.StatusSource.PSP -> if (payment.handlerPackage == null && payment.status != PaymentStatus.PENDING) append(" (sandbox provider)")
            }
            append('\n')
            append("Reference: ").append(payment.txnRef).append('\n')
            payment.upiTxnId?.let { append("UPI transaction: ").append(it).append('\n') }
            if (payment.note.isNotBlank()) append("Note: ").append(payment.note).append('\n')
            append("Time: ").append(payment.createdAt)
        }
    }
}
