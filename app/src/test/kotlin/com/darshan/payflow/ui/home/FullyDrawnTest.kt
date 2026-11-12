package com.darshan.payflow.ui.home

import androidx.activity.FullyDrawnReporter
import androidx.activity.FullyDrawnReporterOwner
import androidx.activity.compose.LocalFullyDrawnReporterOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.domain.model.HistorySummary
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.ui.lock.LockCallbacks
import com.darshan.payflow.ui.lock.LockScreen
import com.darshan.payflow.ui.lock.LockUiState
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Time to full display: the system's "fully drawn" mark comes when the first screen shows real content, not a skeleton. It is
 * what a startup benchmark's `timeToFullDisplay` measures, next to the first frame.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FullyDrawnTest {
    @get:Rule val rule = createComposeRule()

    /** The activity's reporter waits for a real draw, which Robolectric doesn't run; this one reports at once. */
    private var reported = false
    private val owner = object : FullyDrawnReporterOwner {
        override val fullyDrawnReporter = FullyDrawnReporter({ it.run() }) { reported = true }
    }

    private fun show(content: @Composable () -> Unit) = rule.setContent {
        CompositionLocalProvider(LocalFullyDrawnReporterOwner provides owner) { PayFlowTheme { content() } }
    }

    @Test
    fun homeReportsOnceItsPaymentsAreShownNotWhileLoading() {
        var state: HomeUiState by mutableStateOf(HomeUiState.Loading)
        show { HomeScreen(state = state, callbacks = HomeCallbacks()) }
        rule.waitForIdle()
        assertThat(reported).isFalse()
        val payments = listOf(testPayment("a"))
        state = HomeUiState.Content(payments, HistorySummary.of(payments))
        rule.waitForIdle()
        assertThat(reported).isTrue()
    }

    @Test
    fun homeAlsoReportsAnEmptyOrFailedFirstLoad() {
        var state: HomeUiState by mutableStateOf(HomeUiState.Loading)
        show { HomeScreen(state = state, callbacks = HomeCallbacks()) }
        state = HomeUiState.Error("x")
        rule.waitForIdle()
        assertThat(reported).isTrue()
    }

    @Test
    fun aLockedAppIsFullyDrawnWhenItsLockShowsButNotWhileChecking() {
        var state: LockUiState by mutableStateOf(LockUiState.Checking)
        show { LockScreen(state, LockCallbacks()) }
        rule.waitForIdle()
        assertThat(reported).isFalse()
        state = LockUiState.Locked(0, 5, 0, biometricAvailable = false, biometricMessage = null, error = null)
        rule.waitForIdle()
        assertThat(reported).isTrue()
    }
}
