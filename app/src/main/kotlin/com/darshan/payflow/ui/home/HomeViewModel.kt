package com.darshan.payflow.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darshan.payflow.core.DispatcherProvider
import com.darshan.payflow.domain.model.HistorySummary
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.RecentPayee
import com.darshan.payflow.domain.repository.PaymentRepository
import com.darshan.payflow.domain.usecase.ReconcilePendingPaymentsUseCase
import com.darshan.payflow.ui.designsystem.effects.HapticEvent
import com.darshan.payflow.ui.designsystem.effects.InMemoryUiPreferences
import com.darshan.payflow.ui.designsystem.effects.UiEffect
import com.darshan.payflow.ui.designsystem.effects.UiEffectChannel
import com.darshan.payflow.ui.designsystem.effects.UiPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One-off results of a pull to refresh. The screen words them. */
sealed interface HomeEffect : UiEffect {
    data class Refreshed(val checked: Int, val settled: Int) : HomeEffect
    data object RefreshFailed : HomeEffect
}

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
    private val reconcilePending: ReconcilePendingPaymentsUseCase,
    private val dispatchers: DispatcherProvider,
    clock: Clock = Clock.systemUTC(),
    zone: ZoneId = ZoneId.systemDefault(),
    private val preferences: UiPreferences = InMemoryUiPreferences(),
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

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val effectChannel = UiEffectChannel()
    val effects: Flow<UiEffect> = effectChannel.effects

    /** What this visit has already shown. Lives here so it outlasts the screen without entering saved state. */
    val entranceMemory = HomeEntranceMemory()

    /**
     * Masked from the first value until the stored choice has been read, so a hidden amount can't flash on launch. The splash
     * normally covers that wait; this holds if it times out.
     */
    val amountsHidden: StateFlow<Boolean> = combine(preferences.settings, preferences.loaded) { settings, loaded -> settings.amountsHidden || !loaded }
        .stateIn(viewModelScope, SharingStarted.Eagerly, preferences.settings.value.amountsHidden || !preferences.loaded.value)

    fun toggleAmountsHidden() {
        val next = !preferences.settings.value.amountsHidden
        viewModelScope.launch { preferences.setAmountsHidden(next) }
    }

    fun retry() {
        reload.value++
    }

    /** Asks the provider about pending payments again. One at a time: requests while one runs are dropped. */
    fun refresh() {
        if (!_refreshing.compareAndSet(expect = false, update = true)) return
        if (uiState.value is HomeUiState.Error) retry()
        viewModelScope.launch {
            try {
                val shown = launch { delay(MIN_REFRESH_MS) }
                val summary = try {
                    withContext(dispatchers.io) { reconcilePending() }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                shown.join()
                if (summary != null) {
                    effectChannel.send(HomeEffect.Refreshed(summary.checked, summary.settled))
                } else {
                    effectChannel.send(UiEffect.Haptic(HapticEvent.Reject))
                    effectChannel.send(HomeEffect.RefreshFailed)
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    companion object {
        const val RECENT_LIMIT = 5
        const val PAYEE_LIMIT = 8

        /** Long enough for the coin to turn at least once, so an instant answer doesn't read as nothing having happened. */
        const val MIN_REFRESH_MS = 700L
    }
}
