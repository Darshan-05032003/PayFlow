package com.darshan.payflow.ui.me

import com.darshan.payflow.data.profile.SecureUpiProfileRepository
import com.darshan.payflow.data.security.InMemorySecureStore
import com.darshan.payflow.data.security.PinManager
import com.darshan.payflow.domain.model.UpiProfile
import com.darshan.payflow.testutil.MainDispatcherRule
import com.darshan.payflow.testutil.TestDispatcherProvider
import com.darshan.payflow.testutil.fixedClock
import com.darshan.payflow.ui.designsystem.effects.InMemoryUiPreferences
import com.darshan.payflow.ui.designsystem.effects.ThemeMode
import com.darshan.payflow.ui.designsystem.effects.UiSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val mainRule = MainDispatcherRule(dispatcher)
    private val store = InMemorySecureStore()
    private val pins = PinManager(store, fixedClock, iterations = 1_000)
    private val profiles = SecureUpiProfileRepository(store)
    private val prefs = InMemoryUiPreferences()

    private fun vm() = MeViewModel(profiles, pins, prefs, TestDispatcherProvider(dispatcher))

    @Test
    fun `loads the profile, the app lock and the look-and-feel settings`() = runTest(dispatcher) {
        profiles.save(UpiProfile("Ira Kulkarni", "ira@okaxis"))
        pins.set("493027")
        val vm = vm()
        assertThat(vm.uiState.value.loading).isTrue()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertThat(s.loading).isFalse()
        assertThat(s.profile).isEqualTo(UpiProfile("Ira Kulkarni", "ira@okaxis"))
        assertThat(s.pinConfigured).isTrue()
        assertThat(s.settings).isEqualTo(UiSettings())
    }

    @Test
    fun `each toggle writes the shared setting the whole app reads`() = runTest(dispatcher) {
        val vm = vm(); advanceUntilIdle()
        vm.setHaptics(false); vm.setSound(true); vm.setReduceMotion(true); vm.setTheme(ThemeMode.Dark)
        advanceUntilIdle()
        assertThat(prefs.settings.value).isEqualTo(UiSettings(hapticsEnabled = false, soundEnabled = true, reduceMotion = true, theme = ThemeMode.Dark))
        assertThat(vm.uiState.value.settings).isEqualTo(prefs.settings.value)
    }

    @Test
    fun `a pin or profile changed on another screen shows here at once`() = runTest(dispatcher) {
        val vm = vm(); advanceUntilIdle()
        assertThat(vm.uiState.value.pinConfigured).isFalse()
        assertThat(vm.uiState.value.profile).isNull()
        pins.set("493027"); profiles.save(UpiProfile("Ira Kulkarni", "ira@okaxis")); advanceUntilIdle()
        assertThat(vm.uiState.value.pinConfigured).isTrue()
        assertThat(vm.uiState.value.profile?.vpa).isEqualTo("ira@okaxis")
        pins.clear(); advanceUntilIdle()
        assertThat(vm.uiState.value.pinConfigured).isFalse()
    }

    @Test
    fun `hide amounts is left alone by the toggles`() = runTest(dispatcher) {
        prefs.setAmountsHidden(true)
        val vm = vm(); advanceUntilIdle()
        vm.setTheme(ThemeMode.Light); advanceUntilIdle()
        assertThat(prefs.settings.value.amountsHidden).isTrue()
    }
}
