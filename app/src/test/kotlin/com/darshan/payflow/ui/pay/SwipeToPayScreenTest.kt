package com.darshan.payflow.ui.pay

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.upi.UpiApp
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.FakeHaptics
import com.darshan.payflow.ui.designsystem.commitState
import com.darshan.payflow.ui.designsystem.effects.HapticEvent
import com.darshan.payflow.ui.designsystem.setDs
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Injected touch against the real composable. The rules themselves are in `SwipeToPayHolderTest`. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SwipeToPayScreenTest {
    @get:Rule val rule = createComposeRule()

    private val holder = mutableStateOf(PayUiState())
    private var submits = 0
    private val haptics = FakeHaptics()
    private var back: OnBackPressedDispatcher? = null
    private var backs = 0

    private val ready = PayUiState(
        vpa = "alice@okbank", name = "Alice Kumar", amountText = "10",
        verification = VpaVerificationState.Verified("Alice Kumar"), paymentMode = PaymentMode.SANDBOX,
    )

    private fun show(
        state: PayUiState = ready,
        fontScale: Float = 1f,
        reduceMotion: Boolean = false,
        onSubmit: () -> Unit = {
            submits++
            holder.value = holder.value.copy(phase = PayPhase.Submitting)
        },
    ) {
        holder.value = state
        rule.setDs(fontScale = fontScale, reduceMotion = reduceMotion, haptics = haptics) {
            val current by holder
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PayScreen(current, PayCallbacks(onSubmit = onSubmit, onBack = { backs++ }))
        }
    }

    private fun swipe(endFraction: Float, up: Boolean = true) {
        rule.onNodeWithTag(PayFlowTestTags.PAY_SWIPE).performTouchInput {
            val start = Offset(height / 2f, centerY)
            down(start)
            val travel = (width - height) * endFraction
            repeat(10) { moveBy(Offset(travel / 10f, 0f)) }
            if (up) up() else cancel()
        }
        rule.waitForIdle()
    }

    @Test
    fun aFullSwipePaysOnce_withTicksThenHeavy() {
        show()
        swipe(1f)
        assertThat(submits).isEqualTo(1)
        assertThat(haptics.events.count { it == HapticEvent.Tick }).isAtLeast(6)
        assertThat(haptics.events).contains(HapticEvent.Light)
        assertThat(haptics.events.last()).isEqualTo(HapticEvent.Heavy)
        assertThat(haptics.events.count { it == HapticEvent.Heavy }).isEqualTo(1)
        rule.onNodeWithTag(PayFlowTestTags.PAY_SWIPE).assertIsNotEnabled()
    }

    @Test
    fun aShortSwipeSpringsBackAndDoesNothing() {
        show()
        swipe(0.6f)
        assertThat(submits).isEqualTo(0)
        assertThat(haptics.events).doesNotContain(HapticEvent.Heavy)
        rule.onNodeWithTag(PayFlowTestTags.PAY_SWIPE).assertIsEnabled()
        // And it can be used again.
        swipe(1f)
        assertThat(submits).isEqualTo(1)
    }

    @Test
    fun aCancelledDragPastTheMarkPaysNothing() {
        show()
        swipe(1f, up = false)
        assertThat(submits).isEqualTo(0)
        assertThat(haptics.events).doesNotContain(HapticEvent.Heavy)
    }

    @Test
    fun aDragThatDoesNotStartOnTheHandleDoesNothing() {
        show()
        rule.onNodeWithTag(PayFlowTestTags.PAY_SWIPE).performTouchInput {
            down(Offset(width * 0.6f, centerY))
            moveBy(Offset(width * 0.4f, 0f))
            up()
        }
        rule.waitForIdle()
        assertThat(submits).isEqualTo(0)
        assertThat(haptics.events).isEmpty()
    }

    @Test
    fun aSecondFingerDoesNotPayAgainOrSteal() {
        show()
        rule.onNodeWithTag(PayFlowTestTags.PAY_SWIPE).performTouchInput {
            down(1, Offset(height / 2f, centerY))
            down(2, Offset(width * 0.7f, centerY))
            moveBy(2, Offset(width * 0.2f, 0f))
            up(2)
            up(1)
        }
        rule.waitForIdle()
        assertThat(submits).isEqualTo(0)
    }

    @Test
    fun aDisabledSliderRefusesWithAReject() {
        show(PayUiState(paymentMode = PaymentMode.SANDBOX))
        swipe(1f)
        assertThat(submits).isEqualTo(0)
        assertThat(haptics.events).containsExactly(HapticEvent.Reject)
    }

    @Test
    fun aRefusedAttemptSendsTheHandleHomeAndItWorksAgain() {
        show(onSubmit = { submits++ })
        swipe(1f)
        assertThat(submits).isEqualTo(1)
        swipe(1f) // Committed and not yet answered: nothing.
        assertThat(submits).isEqualTo(1)
        commitState { holder.value = holder.value.copy(attemptsRefused = 1) }
        rule.waitForIdle()
        swipe(1f)
        assertThat(submits).isEqualTo(2)
    }

    @Test
    fun aThousandSwipesPayOnce() {
        show()
        repeat(1_000) { rule.onNodeWithTag(PayFlowTestTags.PAY_SWIPE).performTouchInput { swipeOnce() } }
        rule.waitForIdle()
        assertThat(submits).isEqualTo(1)
    }

    private fun androidx.compose.ui.test.TouchInjectionScope.swipeOnce() {
        down(Offset(height / 2f, centerY))
        moveBy(Offset(width.toFloat(), 0f))
        up()
    }

    @Test
    fun aThousandTapsOnThePlainConfirmPayOnce() {
        show()
        rule.onNodeWithTag(PayFlowTestTags.PAY_PLAIN_ACTION).performClick()
        rule.waitForIdle()
        repeat(1_000) { runCatching { rule.onNodeWithTag(PayFlowTestTags.PAY_SUBMIT).performClick() } }
        assertThat(submits).isEqualTo(1)
    }

    @Test
    fun talkBackActivateAsksForConfirmationAndNeverPaysDirectly() {
        show()
        rule.onNodeWithTag(PayFlowTestTags.PAY_SWIPE).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertThat(submits).isEqualTo(0)
        rule.onNodeWithTag(PayFlowTestTags.PAY_SUBMIT).assertIsDisplayed()
        rule.onNodeWithText("To Alice Kumar").assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.PAY_SUBMIT).performClick()
        assertThat(submits).isEqualTo(1)
    }

    @Test
    fun reducedMotionHasThePlainRouteAndItStillAsksForConfirmation() {
        show(reduceMotion = true)
        rule.onNodeWithTag(PayFlowTestTags.PAY_PLAIN_ACTION).performClick()
        rule.waitForIdle()
        assertThat(submits).isEqualTo(0)
        rule.onNodeWithTag(PayFlowTestTags.PAY_SUBMIT).performClick()
        assertThat(submits).isEqualTo(1)
    }

    @Test
    fun aSwipeStillWorksAtLargeFontAndReducedMotion() {
        show(fontScale = 1.3f, reduceMotion = true)
        swipe(1f)
        assertThat(submits).isEqualTo(1)
    }

    @Test
    fun confirmationAndAppSheetsStayInTheWindow() {
        show(ready.copy(paymentMode = PaymentMode.UPI_APPS, verification = VpaVerificationState.Skipped))
        rule.onNodeWithTag(PayFlowTestTags.PAY_PLAIN_ACTION).performClick()
        rule.waitForIdle()
        rule.onAllNodes(isDialog()).assertCountEquals(0)
        rule.onAllNodes(isRoot()).assertCountEquals(1)
        commitState { holder.value = holder.value.copy(phase = PayPhase.ChoosingApp(listOf(UpiApp("com.example.gpay", "GPay")))) }
        rule.waitForIdle()
        rule.onNodeWithTag(PayFlowTestTags.PAY_APP_SHEET).assertExists()
        rule.onAllNodes(isDialog()).assertCountEquals(0)
        rule.onAllNodes(isRoot()).assertCountEquals(1)
        // A separate window would be a second root; that is the one that wouldn't inherit FLAG_SECURE.
    }

    @Test
    fun contentBehindAnOpenSheetIsHiddenFromTalkBack() {
        show(ready.copy(phase = PayPhase.ChoosingApp(listOf(UpiApp("com.example.gpay", "GPay"))), paymentMode = PaymentMode.UPI_APPS))
        rule.waitForIdle()
        rule.onAllNodesWithTag(PayFlowTestTags.PAY_SWIPE).assertCountEquals(0)
        rule.onAllNodesWithText("Pay with UPI").assertCountEquals(0)
        commitState { holder.value = holder.value.copy(phase = PayPhase.Idle) }
        rule.waitForIdle()
        rule.onNodeWithTag(PayFlowTestTags.PAY_SWIPE).assertExists()
    }

    @Test
    fun aRunningPaymentBlocksBackUntilTheLimitThenLetsGo() {
        show(ready.copy(phase = PayPhase.Confirming("p1")))
        rule.runOnIdle { back!!.onBackPressed() }
        assertThat(backs).isEqualTo(0)
        assertThat(haptics.events).contains(HapticEvent.Reject)
        rule.onAllNodesWithTag(PayFlowTestTags.PAY_LEAVE).assertCountEquals(0)

        rule.mainClock.advanceTimeBy(ProcessingStage.STILL_CHECKING_MS + 100)
        rule.onNodeWithText("Still checking…").assertIsDisplayed()
        rule.runOnIdle { back!!.onBackPressed() }
        assertThat(backs).isEqualTo(0)

        rule.mainClock.advanceTimeBy(PayBackPolicy.BLOCK_LIMIT_MS)
        rule.onNodeWithText("The bank is taking longer than usual").assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.PAY_LEAVE).performClick()
        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun aPickedAppLocksTheSheetAndTheLimitOpensIt() {
        show(ready.copy(phase = PayPhase.ChoosingApp(listOf(UpiApp("com.example.gpay", "GPay")), picked = "com.example.gpay"), paymentMode = PaymentMode.UPI_APPS))
        rule.waitForIdle()
        rule.runOnIdle { back!!.onBackPressed() }
        assertThat(backs).isEqualTo(0)
        rule.mainClock.advanceTimeBy(PayBackPolicy.BLOCK_LIMIT_MS + 100)
        rule.runOnIdle { back!!.onBackPressed() }
        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun backIsNeverBlockedOnceThereIsAnAnswer() {
        for (phase in listOf(PayPhase.Idle, PayPhase.AwaitingApp("p1"), PayPhase.Unresolved("p1"), PayPhase.Done("p1", PaymentStatus.PENDING, null, testPayment("p1", status = PaymentStatus.PENDING)))) {
            assertThat(PayBackPolicy.blocksBack(phase, 0)).isFalse()
        }
    }
}
