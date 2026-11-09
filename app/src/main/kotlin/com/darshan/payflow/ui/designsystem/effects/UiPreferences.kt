package com.darshan.payflow.ui.designsystem.effects

import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

enum class ThemeMode { System, Light, Dark }

@Immutable
data class UiSettings(
    val hapticsEnabled: Boolean = true,
    val soundEnabled: Boolean = false,
    val reduceMotion: Boolean = false,
    val theme: ThemeMode = ThemeMode.System,
    /** Amounts on Home show as a mask until the user asks to see them. */
    val amountsHidden: Boolean = false,
)

/** App look and feel settings. Nothing here is sensitive; secrets go through `SecureStore`. */
interface UiPreferences {
    val settings: StateFlow<UiSettings>

    /** True once the stored values have been read. A splash can wait on it to avoid flashing the wrong theme. */
    val loaded: StateFlow<Boolean>

    suspend fun setHapticsEnabled(enabled: Boolean)
    suspend fun setSoundEnabled(enabled: Boolean)
    suspend fun setReduceMotion(enabled: Boolean)
    suspend fun setTheme(theme: ThemeMode)
    suspend fun setAmountsHidden(hidden: Boolean)
}

/**
 * DataStore version. A failed read keeps the defaults, and a failed write is dropped: a look-and-feel setting is never
 * worth crashing a payments app over.
 */
class DataStoreUiPreferences(
    private val store: DataStore<Preferences>,
    scope: CoroutineScope,
) : UiPreferences {
    private val _loaded = MutableStateFlow(false)
    override val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    override val settings: StateFlow<UiSettings> = store.data
        .catch { failure -> if (failure is IOException) emit(emptyPreferences()) else throw failure }
        .map(::read)
        .distinctUntilChanged()
        .onEach { _loaded.value = true }
        .stateIn(scope, SharingStarted.Eagerly, UiSettings())

    override suspend fun setHapticsEnabled(enabled: Boolean) = write { it[HAPTICS] = enabled }
    override suspend fun setSoundEnabled(enabled: Boolean) = write { it[SOUND] = enabled }
    override suspend fun setReduceMotion(enabled: Boolean) = write { it[REDUCE_MOTION] = enabled }
    override suspend fun setTheme(theme: ThemeMode) = write { it[THEME] = theme.name }
    override suspend fun setAmountsHidden(hidden: Boolean) = write { it[AMOUNTS_HIDDEN] = hidden }

    private suspend fun write(change: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        try {
            store.edit(change)
        } catch (_: IOException) {
            // Keep the current value on screen; the setting simply did not persist.
        }
    }

    private fun read(prefs: Preferences): UiSettings {
        val defaults = UiSettings()
        return UiSettings(
            hapticsEnabled = prefs[HAPTICS] ?: defaults.hapticsEnabled,
            soundEnabled = prefs[SOUND] ?: defaults.soundEnabled,
            reduceMotion = prefs[REDUCE_MOTION] ?: defaults.reduceMotion,
            theme = ThemeMode.entries.firstOrNull { it.name == prefs[THEME] } ?: defaults.theme,
            amountsHidden = prefs[AMOUNTS_HIDDEN] ?: defaults.amountsHidden,
        )
    }

    private companion object {
        val HAPTICS = booleanPreferencesKey("ui_haptics")
        val SOUND = booleanPreferencesKey("ui_sound")
        val REDUCE_MOTION = booleanPreferencesKey("ui_reduce_motion")
        val THEME = stringPreferencesKey("ui_theme")
        val AMOUNTS_HIDDEN = booleanPreferencesKey("ui_amounts_hidden")
    }
}

/** For tests, previews and the debug catalog. */
class InMemoryUiPreferences(initial: UiSettings = UiSettings()) : UiPreferences {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<UiSettings> = state.asStateFlow()
    override val loaded: StateFlow<Boolean> = MutableStateFlow(true)

    override suspend fun setHapticsEnabled(enabled: Boolean) = state.update { it.copy(hapticsEnabled = enabled) }
    override suspend fun setSoundEnabled(enabled: Boolean) = state.update { it.copy(soundEnabled = enabled) }
    override suspend fun setReduceMotion(enabled: Boolean) = state.update { it.copy(reduceMotion = enabled) }
    override suspend fun setTheme(theme: ThemeMode) = state.update { it.copy(theme = theme) }
    override suspend fun setAmountsHidden(hidden: Boolean) = state.update { it.copy(amountsHidden = hidden) }
}
