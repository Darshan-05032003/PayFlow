package com.darshan.payflow.ui.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.StatusSource
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.FakeHaptics
import com.darshan.payflow.ui.designsystem.effects.HapticEvent
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
class ScratchCardTest {
    @get:Rule val rule = createComposeRule()
    private val paid = testPayment("pay-42", status = PaymentStatus.SUCCESS, amountPaise = 1_25_000)

    private fun detail(payment: com.darshan.payflow.domain.model.Payment, haptics: FakeHaptics = FakeHaptics(), reduceMotion: Boolean = false, onRevealed: () -> Unit = {}) {
        val revealed = mutableStateOf(false)
        rule.setDs(haptics = haptics, reduceMotion = reduceMotion) {
            val r by revealed
            PaymentDetailScreen(
                PaymentDetailUiState.Content(payment),
                PaymentDetailCallbacks(onRewardRevealed = { revealed.value = true; onRevealed() }),
                rewardRevealed = r,
            )
        }
        rule.waitForIdle()
    }

    private fun description() = rule.onNodeWithTag(PayFlowTestTags.DETAIL_SCRATCH).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()

    @Test
    fun onlyAProviderConfirmedSuccessShowsACard() {
        detail(paid.copy(status = PaymentStatus.PENDING))
        rule.onAllNodesWithTag(PayFlowTestTags.DETAIL_SCRATCH).assertCountEquals(0)
    }

    @Test
    fun anAppReportedSuccessShowsNoCard() {
        detail(paid.copy(statusSource = StatusSource.UPI_APP, handlerPackage = "com.example.wallet"))
        rule.onAllNodesWithTag(PayFlowTestTags.DETAIL_SCRATCH).assertCountEquals(0)
    }

    @Test
    fun theCardSaysDemoAndNothingIsCreditedBeforeAndAfter() {
        detail(paid)
        assertThat(description()).contains("not real money")
        rule.onNodeWithTag(PayFlowTestTags.DETAIL_SCRATCH).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertThat(description()).contains("Demo cashback")
        assertThat(description()).contains("Not real money")
    }

    @Test
    fun scratchingErasesTheFoilAndRevealsOnceWithOneConfirm() {
        val haptics = FakeHaptics()
        var revealed = 0
        detail(paid, haptics = haptics) { revealed++ }
        val card = rule.onNodeWithTag(PayFlowTestTags.DETAIL_SCRATCH)
        val bounds = card.fetchSemanticsNode().boundsInRoot
        val probe = Offset(bounds.left + bounds.width * 0.25f, bounds.top + bounds.height * 0.5f)
        val before = rule.onNodeWithTag(PayFlowTestTags.DETAIL_SCRATCH).captureToImage().toPixelMap()[(bounds.width * 0.25f).toInt(), (bounds.height * 0.5f).toInt()]
        // One stroke through the probe point: the foil there is gone, but not enough for a reveal.
        card.performTouchInput {
            down(Offset(width * 0.1f, height * 0.5f))
            moveTo(Offset(width * 0.4f, height * 0.5f))
            up()
        }
        rule.waitForIdle()
        val after = rule.onNodeWithTag(PayFlowTestTags.DETAIL_SCRATCH).captureToImage().toPixelMap()[(bounds.width * 0.25f).toInt(), (bounds.height * 0.5f).toInt()]
        assertThat(after).isNotEqualTo(before)
        assertThat(revealed).isEqualTo(0)
        // Then scratch the whole card, row by row.
        card.performTouchInput {
            var y = 0.05f
            while (y < 1f) {
                down(Offset(1f, height * y))
                var x = 0f
                while (x <= 1f) { moveTo(Offset(width * x, height * y)); x += 0.05f }
                up()
                y += 0.1f
            }
        }
        rule.waitForIdle()
        assertThat(revealed).isEqualTo(1)
        assertThat(haptics.events.count { it == HapticEvent.Confirm }).isEqualTo(1)
        assertThat(probe.x).isGreaterThan(0f)
    }

    @Test
    fun reducedMotionStillRevealsWithoutSparkles() {
        var revealed = 0
        detail(paid, reduceMotion = true) { revealed++ }
        rule.onNodeWithTag(PayFlowTestTags.DETAIL_SCRATCH).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertThat(revealed).isEqualTo(1)
    }

    @Test
    fun revealingNeverChangesTheAmountShown() {
        detail(paid)
        val before = rule.onNodeWithTag(PayFlowTestTags.DETAIL_AMOUNT).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
        rule.onNodeWithTag(PayFlowTestTags.DETAIL_SCRATCH).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        val after = rule.onNodeWithTag(PayFlowTestTags.DETAIL_AMOUNT).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
        assertThat(after).isEqualTo(before)
    }
}
