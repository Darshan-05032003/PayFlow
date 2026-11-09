package com.darshan.payflow.ui.security

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.security.BindingStatus
import com.darshan.payflow.data.security.BiometricAvailability
import com.darshan.payflow.data.security.IntegrityVerdict
import com.darshan.payflow.data.security.TamperFinding
import com.darshan.payflow.data.security.TamperReport
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.assertInside
import com.darshan.payflow.ui.designsystem.commitState
import com.darshan.payflow.ui.designsystem.setDs
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SecurityCentreTest {
    @get:Rule val rule = createComposeRule()

    private val allGood = SecurityUiState(
        loading = false,
        pinConfigured = true,
        binding = BindingStatus.Bound("fp", "+91 ****3210"),
        tamper = TamperReport.CLEAN,
        integrity = IntegrityStatus.Result(IntegrityVerdict.MEETS_DEVICE_INTEGRITY, sandboxVerifier = false),
        biometric = BiometricAvailability.AVAILABLE,
    )

    private fun rowSays(id: CheckId): String =
        rule.onNodeWithTag(PayFlowTestTags.securityCheck(id.name)).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()

    private fun verdicts(): List<String> = CheckId.entries.map { id -> rowSays(id).split(". ")[1] }

    @Test
    fun checksTickOneAfterAnotherOnTheFrameClock() {
        rule.mainClock.autoAdvance = false
        rule.setDs { SecurityScreen(allGood, SecurityCallbacks()) }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(32)
        assertThat(verdicts().count { it == "Passed" }).isEqualTo(1)
        rule.mainClock.advanceTimeBy(CheckReveal.STEP_MS)
        assertThat(verdicts().count { it == "Passed" }).isEqualTo(2)
        assertThat(verdicts().drop(2).toSet()).containsExactly("Checking")
        rule.mainClock.advanceTimeBy(CheckReveal.STEP_MS * 4)
        assertThat(verdicts()).containsExactly("Passed", "Passed", "Passed", "Passed", "Passed")
        rule.onNodeWithText("Everything checks out").assertExists()
    }

    @Test
    fun aCheckStillRunningHoldsTheSequenceAndIsNeverShownAsPassed() {
        var state by mutableStateOf(allGood.copy(integrity = IntegrityStatus.Checking))
        rule.mainClock.autoAdvance = false
        rule.setDs { SecurityScreen(state, SecurityCallbacks()) }
        rule.mainClock.advanceTimeBy(CheckReveal.STEP_MS * 10)
        assertThat(verdicts()).containsExactly("Passed", "Passed", "Checking", "Checking", "Checking").inOrder()
        rule.onNodeWithText("Checking this phone…").assertExists()
        commitState { state = allGood }
        rule.mainClock.advanceTimeBy(CheckReveal.STEP_MS * 4)
        assertThat(verdicts()).containsExactly("Passed", "Passed", "Passed", "Passed", "Passed")
    }

    @Test
    fun reducedMotionShowsEveryFinalStateAtOnceAndStillHidesUnknownOnes() {
        rule.mainClock.autoAdvance = false
        rule.setDs(reduceMotion = true) { SecurityScreen(allGood.copy(integrity = IntegrityStatus.Checking), SecurityCallbacks()) }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(32)
        assertThat(verdicts()).containsExactly("Passed", "Passed", "Checking", "Passed", "Passed").inOrder()
    }

    @Test
    fun sandboxAndUnconfiguredPartsSayNoteNeverPassed() {
        rule.setDs(reduceMotion = true) {
            SecurityScreen(
                allGood.copy(
                    integrity = IntegrityStatus.Result(IntegrityVerdict.MEETS_DEVICE_INTEGRITY, sandboxVerifier = true),
                    tamper = TamperReport(listOf(TamperFinding.DEBUGGABLE)),
                    biometric = BiometricAvailability.NO_HARDWARE,
                ),
                SecurityCallbacks(),
            )
        }
        rule.waitForIdle()
        assertThat(rowSays(CheckId.INTEGRITY)).contains("Note")
        assertThat(rowSays(CheckId.INTEGRITY)).contains("simulated")
        assertThat(rowSays(CheckId.DEVICE)).contains("Note")
        assertThat(rowSays(CheckId.DEVICE)).contains("Debug build")
        assertThat(rowSays(CheckId.BIOMETRIC)).contains("Note")
        // Nothing to do, but the hero doesn't claim that everything checked out.
        rule.onNodeWithText("Nothing needs you").assertExists()
        assertThat(rule.onAllNodes(androidx.compose.ui.test.hasText("Everything checks out")).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun theOnePrimaryActionIsTheMostImportantNextStep() {
        var setup = 0
        rule.setDs(reduceMotion = true) { SecurityScreen(allGood.copy(pinConfigured = false, binding = BindingStatus.Unbound), SecurityCallbacks(onSetupPin = { setup++ })) }
        rule.waitForIdle()
        rule.onNodeWithTag(PayFlowTestTags.SECURITY_SETUP_PIN).performScrollTo().performClick()
        assertThat(setup).isEqualTo(1)
    }

    @Test
    fun rowsOpenTheirSheetAndBindingRunsThroughTheSandboxOtp() {
        var sent = 0
        var state by mutableStateOf(allGood.copy(binding = BindingStatus.Unbound))
        rule.setDs(reduceMotion = true) { SecurityScreen(state, SecurityCallbacks(onRequestOtp = { sent++ })) }
        rule.waitForIdle()
        rule.onNodeWithTag(PayFlowTestTags.securityCheck(CheckId.BINDING.name)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(PayFlowTestTags.SECURITY_PHONE).assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.SECURITY_SEND_OTP).performClick()
        assertThat(sent).isEqualTo(1)
        state = state.copy(binding = BindingStatus.OtpPending("+91 ****3210"), sandboxOtp = "482913")
        rule.waitForIdle()
        rule.onNodeWithTag(PayFlowTestTags.SECURITY_SANDBOX_OTP).assertIsDisplayed()
        rule.onNodeWithText("Sandbox OTP: 482913").assertExists()
        rule.onNodeWithTag(PayFlowTestTags.SECURITY_VERIFY_OTP).assertIsNotEnabled()
    }

    // ---- turning the lock off

    @Test
    fun turnOffOnlyAsksForProofItNeverRemovesOnATap() {
        var started = 0
        val confirmed = mutableListOf<String>()
        rule.setDs(reduceMotion = true) {
            SecurityScreen(allGood, SecurityCallbacks(onStartRemoval = { started++ }, onConfirmRemoval = { confirmed += it }))
        }
        rule.waitForIdle()
        rule.onNodeWithTag(PayFlowTestTags.securityCheck(CheckId.APP_LOCK.name)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(PayFlowTestTags.SECURITY_REMOVE_PIN).performClick()
        rule.waitForIdle()
        assertThat(started).isEqualTo(1)
        assertThat(confirmed).isEmpty()
    }

    @Test
    fun theRemovalSheetSubmitsSixDigitsOnceAndOffersBiometricOnlyWhenAvailable() {
        val confirmed = mutableListOf<String>()
        var bio = 0
        var state by mutableStateOf(allGood.copy(removal = PinRemovalState(biometricAvailable = false, remainingAttempts = 5)))
        rule.setDs(reduceMotion = true) { SecurityScreen(state, SecurityCallbacks(onConfirmRemoval = { confirmed += it }, onRemoveWithBiometric = { bio++ })) }
        rule.waitForIdle()
        rule.onNodeWithText("Turn off app lock?").assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.SECURITY_REMOVE_PIN_CONFIRM).assertIsNotEnabled()
        assertThat(rule.onAllNodesWithTagCount(PayFlowTestTags.SECURITY_REMOVE_PIN_BIOMETRIC)).isEqualTo(0)
        rule.onNodeWithTag(PayFlowTestTags.SECURITY_REMOVE_PIN_FIELD).performTextInput("49302")
        assertThat(confirmed).isEmpty()
        rule.onNodeWithTag(PayFlowTestTags.SECURITY_REMOVE_PIN_FIELD).performTextInput("7")
        assertThat(confirmed).containsExactly("493027")
        // A refusal clears the field and says how many tries are left.
        state = state.copy(removal = PinRemovalState(biometricAvailable = true, remainingAttempts = 4, error = PinRemovalError.WRONG_PIN, attempts = 1))
        rule.waitForIdle()
        rule.onNodeWithText("Wrong PIN. 4 tries left before a pause.").assertExists()
        rule.onNodeWithTag(PayFlowTestTags.SECURITY_REMOVE_PIN_CONFIRM).assertIsNotEnabled()
        rule.onNodeWithTag(PayFlowTestTags.SECURITY_REMOVE_PIN_BIOMETRIC).performClick()
        assertThat(bio).isEqualTo(1)
    }

    @Test
    fun aLockoutDisablesTheFieldAndSaysHowLong() {
        rule.setDs(reduceMotion = true) {
            SecurityScreen(
                allGood.copy(removal = PinRemovalState(true, remainingAttempts = 5, lockoutRemainingMillis = 90_000, error = PinRemovalError.LOCKED_OUT, attempts = 5)),
                SecurityCallbacks(),
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Too many tries. Try again in 2 min.").assertExists()
        rule.onNodeWithTag(PayFlowTestTags.SECURITY_REMOVE_PIN_BIOMETRIC).assertIsEnabled()
    }

    // ---- root / tamper

    @Test
    fun aRootedPhoneSaysPaymentsArePausedAndExplainsCalmly() {
        var rechecks = 0
        rule.setDs(reduceMotion = true) {
            SecurityScreen(allGood.copy(tamper = TamperReport(listOf(TamperFinding.MAGISK))), SecurityCallbacks(onRefresh = { rechecks++ }))
        }
        rule.waitForIdle()
        rule.onNodeWithText("Payments are paused on this phone").assertExists()
        rule.onNodeWithTag(PayFlowTestTags.SECURITY_NEXT_STEP).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(PayFlowTestTags.TAMPER_WARNING).assertIsDisplayed()
        rule.onNodeWithText("The phone is rooted: an app can take full control of it.").assertExists()
        rule.onNodeWithText("What still works").assertExists()
        rule.onNodeWithTag(PayFlowTestTags.TAMPER_RECHECK).performClick()
        assertThat(rechecks).isEqualTo(1)
    }

    @Test
    fun theWarningLeavesOnItsOwnOnceAReScanComesBackClean() {
        var state by mutableStateOf(allGood.copy(tamper = TamperReport(listOf(TamperFinding.FRIDA_PORT))))
        rule.setDs(reduceMotion = true) { SecurityScreen(state, SecurityCallbacks()) }
        rule.waitForIdle()
        rule.onNodeWithTag(PayFlowTestTags.SECURITY_NEXT_STEP).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(PayFlowTestTags.TAMPER_WARNING).assertIsDisplayed()
        state = allGood
        rule.waitForIdle()
        assertThat(rule.onAllNodesWithTagCount(PayFlowTestTags.TAMPER_WARNING)).isEqualTo(0)
    }

    @Test
    fun largeTextKeepsEveryRowOnScreen() {
        rule.setDs(fontScale = 1.3f, reduceMotion = true) { SecurityScreen(allGood.copy(pinConfigured = false), SecurityCallbacks()) }
        rule.waitForIdle()
        CheckId.entries.forEach { rule.onNodeWithTag(PayFlowTestTags.securityCheck(it.name)).performScrollTo().assertInside(rule.onRoot()) }
        rule.onNodeWithTag(PayFlowTestTags.SECURITY_SETUP_PIN).performScrollTo().assertInside(rule.onRoot())
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
