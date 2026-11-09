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
import com.darshan.payflow.ui.components.AmountVisibility
import com.darshan.payflow.ui.designsystem.effects.InMemoryUiPreferences
import com.darshan.payflow.ui.designsystem.effects.UiEffect
import com.darshan.payflow.ui.designsystem.effects.UiEffectChannel
import com.darshan.payflow.ui.designsystem.effects.UiPreferences
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.atomic.AtomicBoolean

sealed interface HistoryUiState {
    data object Loading : HistoryUiState
    data class Empty(val filter: HistoryFilter, val query: String) : HistoryUiState
    data class Error(val detail: String?) : HistoryUiState
    /** [entries] is [payments] in day sections, built off the main thread by the ViewModel. */
    data class Content(
        val payments: List<Payment>,
        val filter: HistoryFilter,
        val query: String,
        val entries: List<HistoryEntry> = HistorySections.build(payments, java.time.LocalDate.now(), ZoneId.systemDefault()),
    ) : HistoryUiState
}

/** One-off results of a row action. */
sealed interface HistoryEffect : UiEffect {
    data class Checked(val checked: Int, val settled: Int) : HistoryEffect
    data object CheckFailed : HistoryEffect
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    repository: PaymentRepository,
    private val reconcilePending: ReconcilePendingPaymentsUseCase,
    private val dispatchers: DispatcherProvider,
    private val savedState: SavedStateHandle,
    preferences: UiPreferences = InMemoryUiPreferences(),
    private val clock: Clock = Clock.systemUTC(),
    /** The zone days are counted in; the screen formats times in the same one. */
    val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val visibility = AmountVisibility(preferences, viewModelScope)

    /** Home's hide-amounts choice: history masks every amount with it. */
    val amountsHidden: StateFlow<Boolean> = visibility.hidden

    private val effectChannel = UiEffectChannel()
    val effects: Flow<UiEffect> = effectChannel.effects
    private val checking = AtomicBoolean(false)

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
                        if (list.isEmpty()) {
                            HistoryUiState.Empty(f, q)
                        } else {
                            HistoryUiState.Content(list, f, q, HistorySections.build(list, clock.instant().atZone(zone).toLocalDate(), zone))
                        }
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

    fun toggleAmountsHidden() = visibility.toggle()

    /**
     * A row's "Check status": asks the provider about every pending sandbox payment now, however new (that row included), and
     * says what happened. One check at a time; taps while it runs are dropped.
     */
    fun checkPending() {
        if (!checking.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val summary = try {
                    withContext(dispatchers.io) { reconcilePending(Duration.ZERO) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                effectChannel.send(if (summary == null) HistoryEffect.CheckFailed else HistoryEffect.Checked(summary.checked, summary.settled))
            } finally {
                checking.set(false)
            }
        }
    }

    companion object {
        const val KEY_FILTER = "filter"
        const val KEY_QUERY = "query"
        const val MAX_QUERY_LENGTH = 64
    }
}
