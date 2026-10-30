package com.darshan.payflow.ui.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darshan.payflow.core.DispatcherProvider
import com.darshan.payflow.domain.model.HistoryFilter
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.repository.PaymentRepository
import com.darshan.payflow.domain.usecase.ReconcilePendingPaymentsUseCase
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

sealed interface HistoryUiState {
    data object Loading : HistoryUiState
    data class Empty(val filter: HistoryFilter, val query: String) : HistoryUiState
    data class Error(val detail: String?) : HistoryUiState
    data class Content(val payments: List<Payment>, val filter: HistoryFilter, val query: String) : HistoryUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    repository: PaymentRepository,
    private val reconcilePending: ReconcilePendingPaymentsUseCase,
    private val dispatchers: DispatcherProvider,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val filter: StateFlow<String> = savedState.getStateFlow(KEY_FILTER, HistoryFilter.ALL.name)
    private val query: StateFlow<String> = savedState.getStateFlow(KEY_QUERY, "")
    private val reload = MutableStateFlow(0)

    private var lastReload = -1

    val uiState: StateFlow<HistoryUiState> =
        combine(filter, query, reload) { f, q, r -> Triple(HistoryFilter.fromNameOrAll(f), q, r) }
            .flatMapLatest { (f, q, r) ->
                // Show the spinner on first load and on retry only; changing a chip must not flicker.
                val showLoading = r != lastReload
                lastReload = r
                repository.observeHistory(f, q)
                    .map<List<Payment>, HistoryUiState> { list ->
                        if (list.isEmpty()) HistoryUiState.Empty(f, q) else HistoryUiState.Content(list, f, q)
                    }
                    .onStart { if (showLoading) emit(HistoryUiState.Loading) }
                    .catch { emit(HistoryUiState.Error(it.message)) }
            }
            .flowOn(dispatchers.io)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState.Loading)

    fun setFilter(filter: HistoryFilter) {
        savedState[KEY_FILTER] = filter.name
    }

    fun setQuery(query: String) {
        savedState[KEY_QUERY] = query.filterNot { it.isISOControl() }.trim().take(MAX_QUERY_LENGTH)
    }

    fun retry() {
        reload.value++
    }

    /** Pull-to-refresh: settle stale pending sandbox payments with the PSP, then reload the list. */
    fun refresh() {
        viewModelScope.launch {
            withContext(dispatchers.io) { runCatching { reconcilePending() } }
            reload.value++
        }
    }

    companion object {
        const val KEY_FILTER = "filter"
        const val KEY_QUERY = "query"
        const val MAX_QUERY_LENGTH = 64
    }
}
