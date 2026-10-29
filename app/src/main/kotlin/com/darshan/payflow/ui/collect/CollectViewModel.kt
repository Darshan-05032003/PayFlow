package com.darshan.payflow.ui.collect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darshan.payflow.core.DispatcherProvider
import com.darshan.payflow.domain.model.CollectRequest
import com.darshan.payflow.domain.repository.CollectRepository
import com.darshan.payflow.domain.usecase.CollectActionResult
import com.darshan.payflow.domain.usecase.RefreshCollectRequestsUseCase
import com.darshan.payflow.domain.usecase.RespondToCollectUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A request plus whether an answer for it is in flight. The displayed status derives expiry from the clock. */
data class CollectItem(val request: CollectRequest, val busy: Boolean)

sealed interface CollectUiState {
    data object Loading : CollectUiState
    data object Empty : CollectUiState
    data class Error(val detail: String?) : CollectUiState
    data class Content(val requests: List<CollectItem>, val refreshing: Boolean) : CollectUiState
}

sealed interface CollectEvent {
    data class Answered(val result: CollectActionResult) : CollectEvent
    data object RefreshFailed : CollectEvent
}

private enum class RefreshPhase { INITIAL, REFRESHING, IDLE }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CollectViewModel @Inject constructor(
    private val collects: CollectRepository,
    private val refreshRequests: RefreshCollectRequestsUseCase,
    private val respond: RespondToCollectUseCase,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val reload = MutableStateFlow(0)
    private val refreshPhase = MutableStateFlow(RefreshPhase.INITIAL)
    private val busyIds = MutableStateFlow<Set<String>>(emptySet())

    private val _events = MutableSharedFlow<CollectEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<CollectEvent> = _events.asSharedFlow()

    val uiState: StateFlow<CollectUiState> = reload
        .flatMapLatest {
            combine(collects.observeAll(), refreshPhase, busyIds) { requests, phase, busy ->
                when {
                    phase == RefreshPhase.INITIAL -> CollectUiState.Loading
                    requests.isEmpty() -> CollectUiState.Empty
                    else -> CollectUiState.Content(
                        requests = requests.map { r -> CollectItem(r.copy(status = r.effectiveStatus(clock.instant())), r.id in busy) },
                        refreshing = phase == RefreshPhase.REFRESHING,
                    )
                }
            }.catch { emit(CollectUiState.Error(it.message)) }
        }
        .flowOn(dispatchers.io)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectUiState.Loading)

    init {
        refresh(initial = true)
    }

    fun refresh() = refresh(initial = true)

    private fun refresh(initial: Boolean) {
        if (refreshPhase.value == RefreshPhase.REFRESHING) return
        refreshPhase.value = if (initial) RefreshPhase.INITIAL else RefreshPhase.REFRESHING
        if (initial) reload.value++
        viewModelScope.launch {
            val result = withContext(dispatchers.io) { refreshRequests() }
            refreshPhase.value = RefreshPhase.IDLE
            if (result.isFailure) _events.tryEmit(CollectEvent.RefreshFailed)
        }
    }

    fun approve(id: String) = answer(id, approve = true)
    fun decline(id: String) = answer(id, approve = false)

    private fun answer(id: String, approve: Boolean) {
        if (id in busyIds.value) return
        busyIds.update { it + id }
        viewModelScope.launch {
            val result = withContext(dispatchers.io) { respond(id, approve) }
            busyIds.update { it - id }
            _events.tryEmit(CollectEvent.Answered(result))
        }
    }
}
