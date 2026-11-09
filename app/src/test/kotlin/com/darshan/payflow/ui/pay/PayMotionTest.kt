package com.darshan.payflow.ui.pay

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.StatusSource
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.FakeHaptics
import com.darshan.payflow.ui.designsystem.effects.LocalHaptics
import com.darshan.payflow.ui.designsystem.recompositionCycles
import com.darshan.payflow.ui.designsystem.setDs
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The moments, with the clock paused: what plays, what doesn't, and that it costs no recomposition per frame. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class PayMotionTest {
    @get:Rule val rule = createComposeRule()
    private val haptics = FakeHaptics()

    private fun done(status: PaymentStatus, source: StatusSource = StatusSource.PSP, handler: String? = null, reason: String? = null) =
        PayUiState(
            paymentMode = if (handler != null) PaymentMode.UPI_APPS else PaymentMode.SANDBOX,
            phase = PayPhase.Done("p1", status, reason, testPayment("p1", status = status, amountPaise = 1_25_000).copy(statusSource = source, handlerPackage = handler), handler?.let { "GPay" }),
        )

    private fun show(state: PayUiState, reduce: Boolean = false) {
        rule.mainClock.autoAdvance = false
        rule.setDs(reduceMotion = reduce, haptics = haptics) { PayScreen(state, PayCallbacks()) }
        rule.mainClock.advanceTimeByFrame()
    }

    private fun burstCount(): Int = rule.onAllNodesWithTag(PayFlowTestTags.PAY_BURST).fetchSemanticsNodes().size

    @Test
    fun aProviderSuccessBurstsAndTheBurstEnds() {
        show(done(PaymentStatus.SUCCESS))
        rule.mainClock.advanceTimeBy(100)
        assertThat(burstCount()).isEqualTo(1)
        val early = rule.onRoot().captureToImage().asAndroidBitmap()
        rule.mainClock.advanceTimeBy(250)
        val later = rule.onRoot().captureToImage().asAndroidBitmap()
        assertThat(early.sameAs(later)).isFalse()
    }

    @Test
    fun anAppReportedSuccessDoesNotBurst() {
        show(done(PaymentStatus.SUCCESS, StatusSource.UPI_APP, "com.example.gpay"))
        rule.mainClock.advanceTimeBy(200)
        assertThat(burstCount()).isEqualTo(0)
    }

    @Test
    fun nothingElseBursts() {
        val holder = mutableStateOf(done(PaymentStatus.PENDING))
        rule.mainClock.autoAdvance = false
        rule.setDs(haptics = haptics) { PayScreen(holder.value, PayCallbacks()) }
        for (state in listOf(
            done(PaymentStatus.PENDING),
            done(PaymentStatus.FAILED),
            done(PaymentStatus.REFUNDED),
            done(PaymentStatus.SUCCESS, StatusSource.USER, "com.example.gpay"),
            done(PaymentStatus.SUCCESS, StatusSource.UPI_APP, "com.example.gpay", reason = PayViewModel.MISMATCH_REASON),
            PayUiState(phase = PayPhase.Unresolved("p1", mismatch = true)),
        )) {
            com.darshan.payflow.ui.designsystem.commitState { holder.value = state }
            rule.mainClock.advanceTimeBy(200)
            assertThat(burstCount()).isEqualTo(0)
        }
    }

    @Test
    fun reducedMotionHasNoBurstAndTheReceiptIsAlreadyThere() {
        show(done(PaymentStatus.SUCCESS), reduce = true)
        rule.mainClock.advanceTimeBy(50)
        assertThat(burstCount()).isEqualTo(0)
        val first = rule.onNodeWithTag(PayFlowTestTags.PAY_RECEIPT).fetchSemanticsNode().boundsInRoot
        rule.mainClock.advanceTimeBy(2_000)
        assertThat(rule.onNodeWithTag(PayFlowTestTags.PAY_RECEIPT).fetchSemanticsNode().boundsInRoot).isEqualTo(first)
    }

    @Test
    fun theReceiptPrintsDownAndIsReadableFromTheFirstFrame() {
        show(done(PaymentStatus.SUCCESS))
        // Present for TalkBack even before it has printed.
        rule.onNodeWithTag(PayFlowTestTags.PAY_RECEIPT).assertExists()
        val start = rule.onRoot().captureToImage().asAndroidBitmap()
        rule.mainClock.advanceTimeBy(3_000)
        val end = rule.onRoot().captureToImage().asAndroidBitmap()
        assertThat(start.sameAs(end)).isFalse()
    }

    @Test
    fun theBurstAndThePrintDoNotRecomposePerFrame() {
        show(done(PaymentStatus.SUCCESS))
        rule.mainClock.advanceTimeBy(1_200) // odometer and first state settle
        val before = recompositionCycles()
        repeat(60) { rule.mainClock.advanceTimeByFrame() }
        assertThat(recompositionCycles() - before).isAtMost(3L)
    }

    @Test
    fun aRotationDoesNotReplayTheMoment() {
        val tester = StateRestorationTester(rule)
        rule.mainClock.autoAdvance = false
        tester.setContent {
            PayFlowTheme(haptics = haptics) { PayScreen(done(PaymentStatus.SUCCESS), PayCallbacks()) }
        }
        rule.mainClock.advanceTimeBy(100)
        assertThat(burstCount()).isEqualTo(1)
        rule.mainClock.autoAdvance = true
        tester.emulateSavedInstanceStateRestore()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeByFrame()
        assertThat(burstCount()).isEqualTo(0)
    }

    @Test
    fun theScreenItselfPlaysNoOutcomeHaptic_thatIsTheViewModelsOneShot() {
        show(done(PaymentStatus.SUCCESS))
        rule.mainClock.advanceTimeBy(1_500)
        assertThat(haptics.events).isEmpty()
    }
}
