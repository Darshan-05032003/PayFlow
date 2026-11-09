package com.darshan.payflow.stress

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.profile.SecureUpiProfileRepository
import com.darshan.payflow.data.security.BindingStatus
import com.darshan.payflow.data.security.BiometricAvailability
import com.darshan.payflow.data.security.DeviceBindingManager
import com.darshan.payflow.data.security.DeviceIdentity
import com.darshan.payflow.data.security.FakeIntegrityVerifier
import com.darshan.payflow.data.security.FakeOtpSender
import com.darshan.payflow.data.security.InMemorySecureStore
import com.darshan.payflow.data.security.IntegrityChecker
import com.darshan.payflow.data.security.IntegrityResult
import com.darshan.payflow.data.security.IntegrityVerdict
import com.darshan.payflow.data.security.PinManager
import com.darshan.payflow.data.security.TamperDetector
import com.darshan.payflow.data.security.TamperReport
import com.darshan.payflow.data.settings.InMemoryPaymentModeRepository
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.model.UpiProfile
import com.darshan.payflow.domain.usecase.RecordCheckoutResultUseCase
import com.darshan.payflow.testutil.FakeBiometricAuthenticator
import com.darshan.payflow.testutil.FakePaymentRepository
import com.darshan.payflow.testutil.FakeUpiAppLauncher
import com.darshan.payflow.testutil.MainDispatcherRule
import com.darshan.payflow.testutil.TestDispatcherProvider
import com.darshan.payflow.testutil.fixedClock
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.checkout.CheckoutViewModel
import com.darshan.payflow.ui.designsystem.effects.InMemoryUiPreferences
import com.darshan.payflow.ui.designsystem.effects.ThemeMode
import com.darshan.payflow.ui.designsystem.recompositionCycles
import com.darshan.payflow.ui.designsystem.setDs
import com.darshan.payflow.ui.me.MeViewModel
import com.darshan.payflow.ui.receive.ReceiveCallbacks
import com.darshan.payflow.ui.receive.ReceiveScreen
import com.darshan.payflow.ui.receive.ReceiveUiState
import com.darshan.payflow.ui.security.CheckReveal
import com.darshan.payflow.ui.security.IntegrityStatus
import com.darshan.payflow.ui.security.PinRemovalError
import com.darshan.payflow.ui.security.SecurityCallbacks
import com.darshan.payflow.ui.security.SecurityChecks
import com.darshan.payflow.ui.security.SecurityScreen
import com.darshan.payflow.ui.security.SecurityUiState
import com.darshan.payflow.ui.security.SecurityViewModel
import com.google.common.truth.Truth.assertThat
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Security centre and Receive layouts at scale: reveal recomposition cost, the longest name with the largest amount. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SettingsUiStressTest {
    @get:Rule val rule = createComposeRule()

    /** The reveal recomposes once per tick, not once per frame: 5 checks over ~1.4 s should cost a handful of cycles. */
    @Test
    fun theTickSequenceRecomposesPerTickNotPerFrame() {
        val state = SecurityUiState(loading = false, pinConfigured = true, binding = BindingStatus.Bound("fp", "+91 ****3210"), tamper = TamperReport.CLEAN,
            integrity = IntegrityStatus.NotConfigured, biometric = BiometricAvailability.AVAILABLE)
        rule.mainClock.autoAdvance = false
        rule.setDs { SecurityScreen(state, SecurityCallbacks()) }
        rule.mainClock.advanceTimeByFrame()
        val before = recompositionCycles()
        val frames = (CheckReveal.STEP_MS * 6 / 16).toInt()
        repeat(frames) { rule.mainClock.advanceTimeByFrame() }
        val cycles = recompositionCycles() - before
        println("STRESS [security-reveal-recompositions] frames=$frames cycles=$cycles")
        assertThat(cycles).isLessThan(frames / 2L)
    }

    @Test
    fun theLongestNameAndTheLargestAmountFitTheReceiveCard() {
        val name = "Shrimati Venkatalakshmi Ramanathapuram Subrahmanyam"
        rule.setDs(fontScale = 1.3f, reduceMotion = true) {
            ReceiveScreen(ReceiveUiState(loading = false, profile = UpiProfile(name.take(UpiProfile.MAX_NAME_LENGTH), "venkatalakshmi.subrahmanyam@okhdfcbank"), amountText = "100000"), ReceiveCallbacks())
        }
        rule.waitForIdle()
        rule.onNodeWithTag(PayFlowTestTags.RECEIVE_QR).performScrollTo()
        rule.onNodeWithTag(PayFlowTestTags.RECEIVE_SHARE_IMAGE).performScrollTo().performClick()
        println("STRESS [receive-longest-name-max-amount] rendered at 1.3x")
    }
}
