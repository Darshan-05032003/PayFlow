package com.darshan.payflow.ui.security

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.security.DefaultPaymentGuard
import com.darshan.payflow.data.security.DeviceBindingManager
import com.darshan.payflow.data.security.DeviceIdentity
import com.darshan.payflow.data.security.FakeIntegrityVerifier
import com.darshan.payflow.data.security.FakeOtpSender
import com.darshan.payflow.data.security.GuardDecision
import com.darshan.payflow.data.security.GuardReason
import com.darshan.payflow.data.security.InMemorySecureStore
import com.darshan.payflow.data.security.IntegrityChecker
import com.darshan.payflow.data.security.IntegrityGate
import com.darshan.payflow.data.security.IntegrityResult
import com.darshan.payflow.data.security.IntegrityVerdict
import com.darshan.payflow.data.security.TamperDetector
import com.darshan.payflow.data.security.TamperFinding
import com.darshan.payflow.data.security.TamperReport
import com.darshan.payflow.testutil.fixedClock
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.setDs
import com.darshan.payflow.ui.pay.PayCallbacks
import com.darshan.payflow.ui.pay.PayScreen
import com.darshan.payflow.ui.pay.PayUiState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class TamperWarningTest {
    @get:Rule val rule = createComposeRule()

    private fun count(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    /** The screen must explain exactly what the guard refuses on: never more (scaring), never less (hiding a block). */
    @Test
    fun theWarningsReasonsMatchWhatThePaymentGuardBlocksForEveryFinding() = runBlocking {
        val verdicts = listOf(IntegrityVerdict.MEETS_DEVICE_INTEGRITY, IntegrityVerdict.FAILED, IntegrityVerdict.UNEVALUATED)
        for (finding in TamperFinding.entries + listOf<TamperFinding?>(null)) {
            for (verdict in verdicts) {
                val report = TamperReport(listOfNotNull(finding))
                val store = InMemorySecureStore()
                val binding = DeviceBindingManager(store, object : DeviceIdentity { override fun fingerprint() = "fp" }, FakeOtpSender(), fixedClock, kotlin.random.Random(1))
                val guard = DefaultPaymentGuard(
                    object : TamperDetector { override suspend fun scan() = report },
                    IntegrityGate(
                        object : IntegrityChecker { override val isConfigured = true; override suspend fun requestToken(requestHash: String) = IntegrityResult.Token("t") },
                        FakeIntegrityVerifier(verdict),
                    ),
                    binding,
                )
                val blocked = (guard.evaluate("p", 100, "a@b") as? GuardDecision.Block)?.reasons.orEmpty()
                val explained = TamperWarningLogic.blockReasons(report, IntegrityStatus.Result(verdict, sandboxVerifier = true))
                assertThat(explained).containsExactlyElementsIn(blocked)
            }
        }
    }

    @Test
    fun warningsNeverReachTheScreen() {
        assertThat(TamperWarningLogic.blocking(listOf(GuardReason.DEBUGGER, GuardReason.DEVICE_NOT_BOUND, GuardReason.ROOTED, GuardReason.ROOTED)))
            .containsExactly(GuardReason.ROOTED)
    }

    @Test
    fun aRefusedPaymentOpensTheWarningOverPayAndHidesTheFormFromTalkBack() {
        var blocked by mutableStateOf(listOf(GuardReason.HOOKING_FRAMEWORK, GuardReason.TAMPERED_SIGNATURE))
        rule.setDs(reduceMotion = true) { PayScreen(PayUiState(), PayCallbacks(), blocked = blocked, onCloseBlocked = { blocked = emptyList() }) }
        rule.waitForIdle()
        rule.onNodeWithTag(PayFlowTestTags.TAMPER_WARNING).assertIsDisplayed()
        rule.onNodeWithText("A tool that changes apps while they run (Frida) is active.").assertExists()
        rule.onNodeWithText("This copy of PayFlow isn't signed by its publisher.").assertExists()
        // Behind it, the form is out of reach for TalkBack: no second way to the Pay button.
        assertThat(count(PayFlowTestTags.PAY_VPA)).isEqualTo(0)
        // Closing only returns to the form. It offers no "pay anyway".
        assertThat(rule.onAllNodes(androidx.compose.ui.test.hasText("Pay anyway", substring = true, ignoreCase = true)).fetchSemanticsNodes()).isEmpty()
        rule.onNodeWithTag(PayFlowTestTags.TAMPER_CLOSE).performClick()
        rule.waitForIdle()
        assertThat(blocked).isEmpty()
        assertThat(count(PayFlowTestTags.TAMPER_WARNING)).isEqualTo(0)
    }

    @Test
    fun largeTextKeepsTheCloseActionReachable() {
        rule.setDs(fontScale = 1.3f, reduceMotion = true) { TamperWarning(listOf(GuardReason.ROOTED, GuardReason.INTEGRITY_FAILED), onClose = {}) }
        rule.waitForIdle()
        rule.onNodeWithTag(PayFlowTestTags.TAMPER_CLOSE).assertIsDisplayed()
    }
}
