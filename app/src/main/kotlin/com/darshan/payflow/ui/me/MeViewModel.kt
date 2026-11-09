package com.darshan.payflow.ui.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darshan.payflow.core.DispatcherProvider
import com.darshan.payflow.data.security.PinManager
import com.darshan.payflow.domain.model.UpiProfile
import com.darshan.payflow.domain.model.UpiProfileRepository
import com.darshan.payflow.ui.designsystem.effects.ThemeMode
import com.darshan.payflow.ui.designsystem.effects.UiPreferences
import com.darshan.payflow.ui.designsystem.effects.UiSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MeUiState(
    val loading: Boolean = true,
    val profile: UpiProfile? = null,
    val pinConfigured: Boolean = false,
    val settings: UiSettings = UiSettings(),
)

/**
 * The Me tab: who you are on UPI, a door to Security and Receive, and the look-and-feel settings. The toggles write the same
 * [UiPreferences] the activity feeds into the theme, so each one takes effect across the app the moment it's flipped.
 */
@HiltViewModel
class MeViewModel @Inject constructor(
    private val profiles: UpiProfileRepository,
    private val pins: PinManager,
    private val preferences: UiPreferences,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MeUiState(settings = preferences.settings.value))
    val uiState: StateFlow<MeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            profiles.profile.flowOn(dispatchers.io).collect { profile -> _uiState.update { it.copy(profile = profile, loading = false) } }
        }
        viewModelScope.launch {
            // changes moves on every set or clear, so its first value reads the starting state too.
            pins.changes.map { withContext(dispatchers.io) { pins.isSet() } }.collect { set -> _uiState.update { it.copy(pinConfigured = set) } }
        }
        viewModelScope.launch {
            preferences.settings.collect { settings -> _uiState.update { it.copy(settings = settings) } }
        }
    }

    fun setHaptics(enabled: Boolean) = write { preferences.setHapticsEnabled(enabled) }
    fun setSound(enabled: Boolean) = write { preferences.setSoundEnabled(enabled) }
    fun setReduceMotion(enabled: Boolean) = write { preferences.setReduceMotion(enabled) }
    fun setTheme(theme: ThemeMode) = write { preferences.setTheme(theme) }

    private fun write(change: suspend () -> Unit) {
        viewModelScope.launch { withContext(dispatchers.io) { change() } }
    }
}
