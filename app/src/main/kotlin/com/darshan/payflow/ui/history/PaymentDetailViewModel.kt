package com.darshan.payflow.ui.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darshan.payflow.core.DispatcherProvider
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.repository.PaymentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

sealed interface PaymentDetailUiState {
    data object Loading : PaymentDetailUiState
    data object NotFound : PaymentDetailUiState
    data class Error(val detail: String?) : PaymentDetailUiState
    data class Content(val payment: Payment) : PaymentDetailUiState
}

@HiltViewModel
class PaymentDetailViewModel @Inject constructor(
    repository: PaymentRepository,
    dispatchers: DispatcherProvider,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val id: String = savedState.get<String>(ARG_ID).orEmpty()

    val uiState: StateFlow<PaymentDetailUiState> = repository.observeById(id)
        .map<Payment?, PaymentDetailUiState> { if (it == null) PaymentDetailUiState.NotFound else PaymentDetailUiState.Content(it) }
        .onStart { emit(PaymentDetailUiState.Loading) }
        .catch { emit(PaymentDetailUiState.Error(it.message)) }
        .flowOn(dispatchers.io)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaymentDetailUiState.Loading)

    companion object {
        /** Matches the `id` field of `PaymentDetailRoute`. */
        const val ARG_ID = "id"
    }
}
