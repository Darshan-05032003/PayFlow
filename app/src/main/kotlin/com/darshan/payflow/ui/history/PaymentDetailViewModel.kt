package com.darshan.payflow.ui.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darshan.payflow.core.DispatcherProvider
import com.darshan.payflow.data.upi.UpiAppLauncher
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.repository.PaymentRepository
import com.darshan.payflow.domain.usecase.MarkPaymentManuallyUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PaymentDetailUiState {
    data object Loading : PaymentDetailUiState
    data object NotFound : PaymentDetailUiState
    data class Error(val detail: String?) : PaymentDetailUiState
    /** [handlerLabel] is the display name of the UPI app that handled a UPI-apps payment; [marking] while a manual mark is saved. */
    data class Content(val payment: Payment, val handlerLabel: String? = null, val marking: Boolean = false) : PaymentDetailUiState
}

@HiltViewModel
class PaymentDetailViewModel @Inject constructor(
    repository: PaymentRepository,
    private val markPaymentManually: MarkPaymentManuallyUseCase,
    private val upiAppLauncher: UpiAppLauncher,
    private val dispatchers: DispatcherProvider,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val id: String = savedState.get<String>(ARG_ID).orEmpty()
    private val marking = MutableStateFlow(false)

    val uiState: StateFlow<PaymentDetailUiState> = combine(repository.observeById(id), marking) { payment, busy ->
        if (payment == null) {
            PaymentDetailUiState.NotFound
        } else {
            PaymentDetailUiState.Content(payment, payment.handlerPackage?.let(upiAppLauncher::appLabel), busy)
        }
    }
        .onStart<PaymentDetailUiState> { emit(PaymentDetailUiState.Loading) }
        .catch { emit(PaymentDetailUiState.Error(it.message)) }
        .flowOn(dispatchers.io)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaymentDetailUiState.Loading)

    /** UPI-apps mode: the user checked the UPI app and settles the pending payment by hand (recorded as "marked by you"). */
    fun markManually(paid: Boolean) {
        if (marking.value) return
        marking.value = true
        viewModelScope.launch {
            withContext(dispatchers.io) { markPaymentManually(id, paid) }
            marking.value = false
        }
    }

    companion object {
        /** Matches the `id` field of `PaymentDetailRoute`. */
        const val ARG_ID = "id"
    }
}
