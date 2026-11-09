package com.darshan.payflow.ui.mandates

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.domain.model.Mandate
import com.darshan.payflow.domain.model.MandateAction
import com.darshan.payflow.domain.model.MandateFrequency
import com.darshan.payflow.domain.model.MandateStatus
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.assertInside
import com.darshan.payflow.ui.designsystem.commitState
import com.darshan.payflow.ui.designsystem.setDs
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class MandateStackScreenTest {
    @get:Rule val rule = createComposeRule()
    private val zone = ZoneOffset.UTC
    private val today: LocalDate = FIXED_INSTANT.atZone(zone).toLocalDate()

    private fun mandate(id: String, name: String, status: MandateStatus = MandateStatus.ACTIVE, frequency: MandateFrequency = MandateFrequency.MONTHLY) = Mandate(
        id, "UMN$id", "$id@okbank", name, 64_900, frequency, today, today.plusYears(1), status, "", FIXED_INSTANT, FIXED_INSTANT, null, 0,
    )

    private val holder = mutableStateOf<MandatesUiState>(MandatesUiState.Loading)
    private val actions = mutableListOf<Pair<String, MandateAction>>()

    private fun show(list: List<Mandate>, busy: Set<String> = emptySet(), reduceMotion: Boolean = false, fontScale: Float = 1f) {
        holder.value = MandatesUiState.Content(list, busy, today)
        rule.setDs(reduceMotion = reduceMotion, fontScale = fontScale) {
            val s by holder
            MandatesScreen(s, MandatesCallbacks(onAction = { id, a -> actions += id to a }), zone = zone)
        }
        rule.waitForIdle()
    }

    @Test
    fun theCardsPauseControlAsksTheDataLayerAndTheCardFollowsTheStoredState() {
        show(listOf(mandate("m1", "Streambox")))
        rule.onNodeWithContentDescription("Pause AutoPay for Streambox").performClick()
        assertThat(actions).containsExactly("m1" to MandateAction.PAUSE)
        // Nothing changes until the stored status does.
        rule.onNodeWithContentDescription("Pause AutoPay for Streambox").assertIsDisplayed()
        commitState { holder.value = MandatesUiState.Content(listOf(mandate("m1", "Streambox", MandateStatus.PAUSED)), emptySet(), today) }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Resume AutoPay for Streambox").performClick()
        assertThat(actions.last()).isEqualTo("m1" to MandateAction.RESUME)
    }

    @Test
    fun theIconMorphsBetweenPauseAndPlay() {
        rule.mainClock.autoAdvance = false
        show(listOf(mandate("m1", "Streambox")))
        rule.mainClock.advanceTimeBy(1_000)
        val quick = rule.onNodeWithTag(PayFlowTestTags.mandateQuick("m1")).fetchSemanticsNode().boundsInRoot
        val x = quick.center.x.toInt()
        val y = quick.center.y.toInt()
        val pause = rule.onRoot().captureToImage().toPixelMap()[x, y]
        commitState { holder.value = MandatesUiState.Content(listOf(mandate("m1", "Streambox", MandateStatus.PAUSED)), emptySet(), today) }
        rule.mainClock.advanceTimeBy(1_000)
        // The centre of the glyph is the gap between the bars when paused-able, and inside the triangle when resumable.
        val play = rule.onRoot().captureToImage().toPixelMap()[x, y]
        assertThat(play).isNotEqualTo(pause)
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun aBusyCardHoldsItsControl() {
        show(listOf(mandate("m1", "Streambox")), busy = setOf("m1"))
        rule.onNodeWithTag(PayFlowTestTags.mandateQuick("m1")).assertIsNotEnabled()
    }

    @Test
    fun talkBackMovesThroughTheStackWithActions() {
        show(listOf(mandate("m1", "Streambox"), mandate("m2", "Gym Pass", MandateStatus.PAUSED)))
        val stack = rule.onNodeWithTag(PayFlowTestTags.MANDATE_STACK)
        val next = stack.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().single { it.label == "Next mandate" }
        rule.runOnIdle { next.action() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Resume AutoPay for Gym Pass").assertIsDisplayed()
        rule.onNodeWithContentDescription("Mandate 2 of 2").assertExists()
    }

    @Test
    fun theTimelineSaysExpectedAndSandbox() {
        show(listOf(mandate("m1", "Streambox")))
        rule.onNodeWithText("Expected debits").assertIsDisplayed()
        rule.onNodeWithText("Today", substring = true).assertExists()
        rule.onNodeWithText("In the sandbox no merchant presents debits", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun pausedAndBilledWhenPresentedExplainWhyNoDatesShow() {
        show(listOf(mandate("m1", "Streambox", MandateStatus.PAUSED)))
        rule.onNodeWithText("Paused. Nothing can be debited until you resume it.").assertIsDisplayed()
        commitState { holder.value = MandatesUiState.Content(listOf(mandate("m1", "Streambox", frequency = MandateFrequency.AS_PRESENTED)), emptySet(), today) }
        rule.waitForIdle()
        rule.onNodeWithText("Debited when the merchant presents a bill", substring = true).assertIsDisplayed()
    }

    @Test
    fun largeTextAndReducedMotionKeepTheCardUsable() {
        show(listOf(mandate("m1", "Shri Venkateswara Lakshmi Narasimha Enterprises")), reduceMotion = true, fontScale = 1.3f)
        rule.onNodeWithTag(PayFlowTestTags.mandateQuick("m1")).assertInside(rule.onRoot())
        rule.onNodeWithTag(PayFlowTestTags.MANDATE_CREATE).assertInside(rule.onRoot())
        rule.onNodeWithTag(PayFlowTestTags.mandateQuick("m1")).performClick()
        assertThat(actions).hasSize(1)
    }

    @Test
    fun revokingAsksFirst() {
        var revoked = 0
        val m = mandate("m1", "Streambox")
        rule.setDs {
            MandateDetailScreen(
                MandateDetailUiState.Content(m, emptyList(), listOf(MandateAction.PAUSE, MandateAction.REVOKE), busy = false, debitAmountText = ""),
                MandateDetailCallbacks(onAction = { if (it == MandateAction.REVOKE) revoked++ }),
                zone = zone,
            )
        }
        rule.onNodeWithTag(PayFlowTestTags.mandateAction(MandateAction.REVOKE)).performScrollTo().performClick()
        assertThat(revoked).isEqualTo(0)
        rule.onNodeWithTag(PayFlowTestTags.MANDATE_REVOKE_SHEET).assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.MANDATE_REVOKE_CONFIRM).performClick()
        assertThat(revoked).isEqualTo(1)
    }
}
