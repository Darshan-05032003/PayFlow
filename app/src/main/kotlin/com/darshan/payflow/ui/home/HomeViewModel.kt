package com.darshan.payflow.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darshan.payflow.core.DispatcherProvider
import com.darshan.payflow.domain.model.HistorySummary
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.RecentPayee
import com.darshan.payflow.domain.repository.PaymentRepository
import com.darshan.payflow.domain.usecase.ReconcilePendingPaymentsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
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
import kotlinx.coroutines.launch

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data object Empty : HomeUiState
    data class Error(val detail: String?) : HomeUiState
    /**
     * [summary] is all-time; [month] covers payments created since the start of the current month (device zone);
     * [recentPayees] are distinct outgoing payees for the "pay again" row.
     */
    data class Content(
        val recent: List<Payment>,
        val summary: HistorySummary,
        val month: HistorySummary = summary,
        val recentPayees: List<RecentPayee> = emptyList(),
    ) : HomeUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    repository: PaymentRepository,
    reconcilePending: ReconcilePendingPaymentsUseCase,
    dispatchers: DispatcherProvider,
    clock: Clock = Clock.systemUTC(),
    zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val monthStart: Instant = YearMonth.now(clock.withZone(zone)).atDay(1).atStartOfDay(zone).toInstant()

    private val reload = MutableStateFlow(0)

    init {
        // App start / process death recovery: settle payments that were left pending with the PSP.
        viewModelScope.launch(dispatchers.io) { runCatching { reconcilePending() } }
    }

    val uiState: StateFlow<HomeUiState> = reload
        .flatMapLatest {
            combine(
                repository.observeRecent(RECENT_LIMIT),
                repository.observeSummary(),
                repository.observeSummarySince(monthStart),
                repository.observeRecentPayees(PAYEE_LIMIT),
            ) { recent, summary, month, payees ->
                if (summary.total == 0) HomeUiState.Empty else HomeUiState.Content(recent, summary, month, payees)
            }
                .onStart<HomeUiState> { emit(HomeUiState.Loading) }
                .catch { emit(HomeUiState.Error(it.message)) }
        }
        .flowOn(dispatchers.io)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)

    fun retry() {
        reload.value++
    }

    companion object {
        const val RECENT_LIMIT = 5
        const val PAYEE_LIMIT = 8
    }
}
