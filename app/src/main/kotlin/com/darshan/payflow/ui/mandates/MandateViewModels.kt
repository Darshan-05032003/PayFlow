package com.darshan.payflow.ui.mandates

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darshan.payflow.core.DispatcherProvider
import com.darshan.payflow.core.Money
import com.darshan.payflow.domain.model.ExecutionCheck
import com.darshan.payflow.domain.model.Mandate
import com.darshan.payflow.domain.model.MandateAction
import com.darshan.payflow.domain.model.MandateFrequency
import com.darshan.payflow.domain.model.MandateStateMachine
import com.darshan.payflow.domain.model.MandateValidation
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.VpaValidator
import com.darshan.payflow.domain.repository.MandateRepository
import com.darshan.payflow.domain.repository.PaymentRepository
import com.darshan.payflow.domain.usecase.CreateMandateRequest
import com.darshan.payflow.domain.usecase.CreateMandateResult
import com.darshan.payflow.domain.usecase.CreateMandateUseCase
import com.darshan.payflow.domain.usecase.ExecuteMandateResult
import com.darshan.payflow.domain.usecase.ExecuteMandateUseCase
import com.darshan.payflow.domain.usecase.UpdateMandateResult
import com.darshan.payflow.domain.usecase.UpdateMandateUseCase
import com.darshan.payflow.ui.pay.PayFieldError
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- list

sealed interface MandatesUiState {
    data object Loading : MandatesUiState
    data object Empty : MandatesUiState
    data class Error(val detail: String?) : MandatesUiState
    data class Content(val mandates: List<Mandate>) : MandatesUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MandatesViewModel @Inject constructor(
    repository: MandateRepository,
    private val clock: Clock,
    private val zone: ZoneId,
    dispatchers: DispatcherProvider,
) : ViewModel() {
    private val reload = MutableStateFlow(0)

    val uiState: StateFlow<MandatesUiState> = reload
        .flatMapLatest {
            repository.observeAll()
                .map<List<Mandate>, MandatesUiState> { list ->
                    val today = clock.instant().atZone(zone).toLocalDate()
                    if (list.isEmpty()) MandatesUiState.Empty else MandatesUiState.Content(list.map { it.copy(status = it.effectiveStatus(today)) })
                }
                .onStart { emit(MandatesUiState.Loading) }
                .catch { emit(MandatesUiState.Error(it.message)) }
        }
        .flowOn(dispatchers.io)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MandatesUiState.Loading)

    fun retry() {
        reload.value++
    }
}

// ---------------------------------------------------------------- create

data class CreateMandateErrors(val vpa: PayFieldError? = null, val name: PayFieldError? = null, val amount: PayFieldError? = null) {
    val hasErrors: Boolean get() = vpa != null || name != null || amount != null
}

data class CreateMandateUiState(
    val vpa: String = "",
    val name: String = "",
    val amountText: String = "",
    val frequency: MandateFrequency = MandateFrequency.MONTHLY,
    val validityMonths: Int = 12,
    val note: String = "",
    val errors: CreateMandateErrors = CreateMandateErrors(),
    val ruleError: MandateValidation? = null,
    val submitting: Boolean = false,
)

sealed interface CreateMandateEvent {
    data class Created(val mandateId: String) : CreateMandateEvent
    data object Unavailable : CreateMandateEvent
}

@HiltViewModel
class CreateMandateViewModel @Inject constructor(
    private val createMandate: CreateMandateUseCase,
    private val clock: Clock,
    private val zone: ZoneId,
    private val dispatchers: DispatcherProvider,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        CreateMandateUiState(
            vpa = savedState[KEY_VPA] ?: "",
            name = savedState[KEY_NAME] ?: "",
            amountText = savedState[KEY_AMOUNT] ?: "",
            frequency = savedState.get<String>(KEY_FREQUENCY)?.let { f -> MandateFrequency.entries.firstOrNull { it.name == f } } ?: MandateFrequency.MONTHLY,
            validityMonths = savedState[KEY_VALIDITY] ?: 12,
            note = savedState[KEY_NOTE] ?: "",
        ),
    )
    val uiState: StateFlow<CreateMandateUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<CreateMandateEvent>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<CreateMandateEvent> = _events.asSharedFlow()

    fun onVpaChange(v: String) = edit { copy(vpa = v.take(256), errors = errors.copy(vpa = null), ruleError = null) }.also { savedState[KEY_VPA] = _uiState.value.vpa }
    fun onNameChange(v: String) = edit { copy(name = v.take(99), errors = errors.copy(name = null)) }.also { savedState[KEY_NAME] = _uiState.value.name }
    fun onNoteChange(v: String) = edit { copy(note = v.take(99)) }.also { savedState[KEY_NOTE] = _uiState.value.note }
    fun onFrequencyChange(f: MandateFrequency) = edit { copy(frequency = f) }.also { savedState[KEY_FREQUENCY] = f.name }
    fun onValidityMonthsChange(months: Int) = edit { copy(validityMonths = months.coerceIn(1, 120), ruleError = null) }.also { savedState[KEY_VALIDITY] = _uiState.value.validityMonths }

    fun onAmountChange(v: String) {
        if (v.isNotEmpty() && !AMOUNT_INPUT.matches(v)) return
        edit { copy(amountText = v, errors = errors.copy(amount = null), ruleError = null) }
        savedState[KEY_AMOUNT] = v
    }

    private fun edit(transform: CreateMandateUiState.() -> CreateMandateUiState) = _uiState.update { if (it.submitting) it else it.transform() }

    fun submit() {
        val state = _uiState.value
        if (state.submitting) return
        val paise = Money.parseInrToPaise(state.amountText)
        val errors = CreateMandateErrors(
            vpa = when {
                state.vpa.isBlank() -> PayFieldError.REQUIRED
                !VpaValidator.isValid(state.vpa) -> PayFieldError.INVALID_VPA
                else -> null
            },
            name = if (state.name.isBlank()) PayFieldError.REQUIRED else null,
            amount = when {
                state.amountText.isBlank() -> PayFieldError.REQUIRED
                paise == null || paise <= 0 -> PayFieldError.INVALID_AMOUNT
                paise > Payment.MAX_AMOUNT_PAISE -> PayFieldError.AMOUNT_TOO_LARGE
                else -> null
            },
        )
        if (errors.hasErrors) {
            _uiState.update { it.copy(errors = errors) }
            return
        }
        _uiState.update { it.copy(submitting = true, ruleError = null) }
        viewModelScope.launch {
            val today = clock.instant().atZone(zone).toLocalDate()
            val request = CreateMandateRequest(state.vpa.trim(), state.name.trim(), paise!!, state.frequency, today, today.plusMonths(state.validityMonths.toLong()), state.note.trim())
            val result = withContext(dispatchers.io) { createMandate(request) }
            _uiState.update { it.copy(submitting = false, ruleError = (result as? CreateMandateResult.Invalid)?.validation) }
            when (result) {
                is CreateMandateResult.Created -> _events.tryEmit(CreateMandateEvent.Created(result.mandate.id))
                is CreateMandateResult.Unavailable -> _events.tryEmit(CreateMandateEvent.Unavailable)
                is CreateMandateResult.Invalid -> Unit
            }
        }
    }

    companion object {
        const val KEY_VPA = "vpa"
        const val KEY_NAME = "name"
        const val KEY_AMOUNT = "amount"
        const val KEY_FREQUENCY = "frequency"
        const val KEY_VALIDITY = "validityMonths"
        const val KEY_NOTE = "note"
        private val AMOUNT_INPUT = Regex("""^\d{0,7}(\.\d{0,2})?$""")
    }
}

// ---------------------------------------------------------------- detail

sealed interface MandateDetailUiState {
    data object Loading : MandateDetailUiState
    data object NotFound : MandateDetailUiState
    data class Error(val detail: String?) : MandateDetailUiState
    data class Content(
        val mandate: Mandate,
        val executions: List<Payment>,
        val availableActions: List<MandateAction>,
        val busy: Boolean,
        val debitAmountText: String,
    ) : MandateDetailUiState
}

sealed interface MandateDetailEvent {
    data class ActionResult(val result: UpdateMandateResult) : MandateDetailEvent
    data class ExecutionResult(val result: ExecuteMandateResult) : MandateDetailEvent
}

@HiltViewModel
class MandateDetailViewModel @Inject constructor(
    mandates: MandateRepository,
    payments: PaymentRepository,
    private val updateMandate: UpdateMandateUseCase,
    private val executeMandate: ExecuteMandateUseCase,
    private val clock: Clock,
    private val zone: ZoneId,
    private val dispatchers: DispatcherProvider,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val id: String = savedState.get<String>(ARG_ID).orEmpty()
    private val busy = MutableStateFlow(false)
    private val debitAmount = savedState.getStateFlow(KEY_DEBIT_AMOUNT, "")

    private val _events = MutableSharedFlow<MandateDetailEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<MandateDetailEvent> = _events.asSharedFlow()

    val uiState: StateFlow<MandateDetailUiState> =
        combine(mandates.observeById(id), payments.observeByMandate(id), busy, debitAmount) { mandate, executions, isBusy, amountText ->
            if (mandate == null) {
                MandateDetailUiState.NotFound
            } else {
                val effective = mandate.copy(status = mandate.effectiveStatus(clock.instant().atZone(zone).toLocalDate()))
                MandateDetailUiState.Content(effective, executions, MandateStateMachine.userActions(effective.status), isBusy, amountText)
            }
        }
            .onStart<MandateDetailUiState> { emit(MandateDetailUiState.Loading) }
            .catch { emit(MandateDetailUiState.Error(it.message)) }
            .flowOn(dispatchers.io)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MandateDetailUiState.Loading)

    fun act(action: MandateAction) {
        if (!busy.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            val result = withContext(dispatchers.io) { updateMandate(id, action) }
            busy.value = false
            _events.tryEmit(MandateDetailEvent.ActionResult(result))
        }
    }

    fun onDebitAmountChange(text: String) {
        if (text.isNotEmpty() && !AMOUNT_INPUT.matches(text)) return
        savedState[KEY_DEBIT_AMOUNT] = text
    }

    /** Sandbox: plays the merchant presenting a debit under the mandate; the rules decide. */
    fun simulateDebit(amountText: String) {
        val paise = Money.parseInrToPaise(amountText)
        if (paise == null || paise <= 0) {
            _events.tryEmit(MandateDetailEvent.ExecutionResult(ExecuteMandateResult.Rejected(ExecutionCheck.InvalidAmount)))
            return
        }
        if (!busy.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            val result = withContext(dispatchers.io) { executeMandate(id, paise) }
            busy.value = false
            _events.tryEmit(MandateDetailEvent.ExecutionResult(result))
        }
    }

    companion object {
        const val ARG_ID = "id"
        const val KEY_DEBIT_AMOUNT = "debitAmount"
        private val AMOUNT_INPUT = Regex("""^\d{0,7}(\.\d{0,2})?$""")
    }
}
