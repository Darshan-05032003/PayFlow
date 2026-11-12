package com.darshan.payflow.integration

import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.MainActivity
import com.darshan.payflow.StartupPrefetch
import com.darshan.payflow.data.security.AppLock
import com.darshan.payflow.data.security.PinManager
import com.darshan.payflow.ui.lock.AppLockViewModel
import com.darshan.payflow.ui.lock.LockUiState
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * The real Hilt graph and activity: what the lock says by the time `onCreate` returns, which is what the splash's first
 * check reads. Answered early, it is final; unanswered, it is Checking and the splash holds. Never the app with a PIN set.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = HiltTestApplication::class)
class StartupGateTest {
    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var pins: PinManager
    @Inject lateinit var appLock: AppLock
    @Inject lateinit var startup: StartupPrefetch

    @Before
    fun setUp() = hiltRule.inject()

    /** What `PayFlowApp.onCreate` does, run to completion. HiltTestApplication doesn't run it. */
    private fun prefetch() {
        val scope = TestScope(UnconfinedTestDispatcher())
        startup.start(scope)
        scope.testScheduler.advanceUntilIdle()
        runBlocking { appLock.refresh() } // the prefetch's own read runs on the real IO pool: make sure it has landed
    }

    private fun lockStateAfterCreate(): LockUiState {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        return ViewModelProvider(activity)[AppLockViewModel::class.java].uiState.value
    }

    @Test
    fun `with a pin answered early, the lock is final before the first frame`() {
        runBlocking { pins.set(PIN); pins.lockStatus() }
        prefetch()
        assertThat(lockStateAfterCreate()).isInstanceOf(LockUiState.Locked::class.java)
    }

    @Test
    fun `with no answer yet, the activity waits on checking, never the app`() {
        runBlocking { pins.set(PIN) }
        assertThat(lockStateAfterCreate()).isEqualTo(LockUiState.Checking)
    }

    @Test
    fun `with no pin answered early, the app is open from the first frame`() {
        prefetch()
        assertThat(lockStateAfterCreate()).isEqualTo(LockUiState.Unlocked(pinConfigured = false))
    }

    private companion object {
        const val PIN = "493027"
    }
}
