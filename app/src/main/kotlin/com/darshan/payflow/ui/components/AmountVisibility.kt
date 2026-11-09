package com.darshan.payflow.ui.components

import com.darshan.payflow.ui.designsystem.effects.UiPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Home's hide-amounts choice, for every screen that shows amounts. Masked from the first value until the stored choice has
 * been read, so a hidden amount can't flash on the way in.
 */
class AmountVisibility(private val preferences: UiPreferences, private val scope: CoroutineScope) {
    val hidden: StateFlow<Boolean> = combine(preferences.settings, preferences.loaded) { settings, loaded -> settings.amountsHidden || !loaded }
        .stateIn(scope, SharingStarted.Eagerly, preferences.settings.value.amountsHidden || !preferences.loaded.value)

    fun toggle() {
        val next = !preferences.settings.value.amountsHidden
        scope.launch { preferences.setAmountsHidden(next) }
    }
}
