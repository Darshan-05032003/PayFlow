package com.darshan.payflow.ui.designsystem

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import com.darshan.payflow.ui.designsystem.effects.DataStoreUiPreferences
import com.darshan.payflow.ui.designsystem.effects.InMemoryUiPreferences
import com.darshan.payflow.ui.designsystem.effects.ThemeMode
import com.darshan.payflow.ui.designsystem.effects.UiSettings
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UiPreferencesTest {
    @get:Rule val folder = TemporaryFolder()

    private fun TestScope.store(file: File = folder.newFile("prefs.preferences_pb").also { it.delete() }): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { file }

    @Test
    fun defaults_are_haptics_on_sound_off_motion_full_theme_system() {
        val s = UiSettings()
        assertThat(s.hapticsEnabled).isTrue()
        assertThat(s.soundEnabled).isFalse()
        assertThat(s.reduceMotion).isFalse()
        assertThat(s.theme).isEqualTo(ThemeMode.System)
    }

    @Test
    fun the_state_flow_starts_with_defaults_before_the_file_is_read() = runTest {
        val prefs = DataStoreUiPreferences(store(), backgroundScope)
        assertThat(prefs.settings.value).isEqualTo(UiSettings())
    }

    @Test
    fun loaded_turns_true_once_the_stored_values_arrive() = runTest(UnconfinedTestDispatcher()) {
        val prefs = DataStoreUiPreferences(store(), backgroundScope)
        prefs.loaded.test {
            var value = awaitItem()
            while (!value) value = awaitItem()
            assertThat(value).isTrue()
        }
    }

    @Test
    fun every_setting_is_written_and_read_back() = runTest(UnconfinedTestDispatcher()) {
        val prefs = DataStoreUiPreferences(store(), backgroundScope)
        prefs.settings.test {
            assertThat(awaitItem()).isEqualTo(UiSettings())
            prefs.setHapticsEnabled(false)
            assertThat(awaitItem().hapticsEnabled).isFalse()
            prefs.setSoundEnabled(true)
            assertThat(awaitItem().soundEnabled).isTrue()
            prefs.setReduceMotion(true)
            assertThat(awaitItem().reduceMotion).isTrue()
            prefs.setTheme(ThemeMode.Dark)
            assertThat(awaitItem().theme).isEqualTo(ThemeMode.Dark)
        }
    }

    @Test
    fun values_survive_a_restart_of_the_store() = runTest(UnconfinedTestDispatcher()) {
        val file = folder.newFile("restart.preferences_pb").also { it.delete() }
        val job = Job()
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job)
        val first = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        DataStoreUiPreferences(first, firstScope).apply {
            setTheme(ThemeMode.Light)
            setReduceMotion(true)
        }
        job.cancelAndJoin()
        val prefs = DataStoreUiPreferences(PreferenceDataStoreFactory.create(scope = backgroundScope) { file }, backgroundScope)
        prefs.settings.test {
            var s = awaitItem()
            while (s == UiSettings()) s = awaitItem()
            assertThat(s.theme).isEqualTo(ThemeMode.Light)
            assertThat(s.reduceMotion).isTrue()
        }
    }

    @Test
    fun an_unknown_theme_value_falls_back_to_system_instead_of_crashing() = runTest(UnconfinedTestDispatcher()) {
        val dataStore = store()
        dataStore.edit { it[stringPreferencesKey("ui_theme")] = "solarized<script>" }
        val prefs = DataStoreUiPreferences(dataStore, backgroundScope)
        prefs.loaded.test {
            var value = awaitItem()
            while (!value) value = awaitItem()
        }
        assertThat(prefs.settings.value.theme).isEqualTo(ThemeMode.System)
    }

    @Test
    fun a_read_failure_keeps_the_defaults_and_still_reports_loaded() = runTest(UnconfinedTestDispatcher()) {
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("disk gone") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("disk gone")
        }
        val prefs = DataStoreUiPreferences(broken, backgroundScope)
        prefs.loaded.test {
            var value = awaitItem()
            while (!value) value = awaitItem()
        }
        assertThat(prefs.settings.value).isEqualTo(UiSettings())
    }

    @Test
    fun a_failed_write_does_not_crash_the_settings_screen() = runTest(UnconfinedTestDispatcher()) {
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("disk gone") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("disk gone")
        }
        DataStoreUiPreferences(broken, backgroundScope).setReduceMotion(true)
    }

    @Test
    fun the_in_memory_version_for_tests_behaves_the_same() = runTest {
        val prefs = InMemoryUiPreferences()
        prefs.setTheme(ThemeMode.Dark)
        prefs.setHapticsEnabled(false)
        assertThat(prefs.settings.value.theme).isEqualTo(ThemeMode.Dark)
        assertThat(prefs.settings.value.hapticsEnabled).isFalse()
        assertThat(prefs.loaded.value).isTrue()
    }
}
