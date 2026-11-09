package com.darshan.payflow.ui.collect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.domain.model.CollectRequest
import com.darshan.payflow.domain.model.CollectStatus
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.FakeHaptics
import com.darshan.payflow.ui.designsystem.assertInside
import com.darshan.payflow.ui.designsystem.commitState
import com.darshan.payflow.ui.designsystem.setDs
import com.google.common.truth.Truth.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import org.robolectric.shadows.ShadowSystemClock

/** Attacks on the request screen: auto-approval, TalkBack and reduced-motion shortcuts, expiry, tap floods, spoofed names. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class CollectScreenAttackTest {
    @get:Rule val rule = createComposeRule()

    private class MovableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = MovableClock(FIXED_INSTANT)

    private fun request(id: String = "req-1", name: String = "Tara Book House", ttlSeconds: Long = 600) = CollectRequest(
        id = id, payeeVpa = "tara@okhdfc", payeeName = name, amountPaise = 4_500, note = "Order 7",
        createdAt = FIXED_INSTANT, expiresAt = FIXED_INSTANT.plusSeconds(ttlSeconds), updatedAt = FIXED_INSTANT,
        status = CollectStatus.PENDING, paymentId = null,
    )

    private val holder = mutableStateOf<CollectUiState>(CollectUiState.Loading)
    private val approvals = mutableListOf<String>()
    private val declines = mutableListOf<String>()

    private fun show(item: CollectItem, reduceMotion: Boolean = false, fontScale: Float = 1f, haptics: FakeHaptics = FakeHaptics()) {
        holder.value = CollectUiState.Content(listOf(item), refreshing = false)
        rule.setDs(reduceMotion = reduceMotion, fontScale = fontScale, haptics = haptics) {
            val current by holder
            CollectScreen(
                current,
                CollectCallbacks(
                    onApprove = { id ->
                        approvals += id
                        // Like the ViewModel: the item is busy from the first accepted answer.
                        val content = holder.value as CollectUiState.Content
                        holder.value = content.copy(requests = content.requests.map { it.copy(busy = true) })
                    },
                    onDecline = { declines += it },
                ),
                clock = clock,
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun showingARequestNeverAnswersIt() {
        show(CollectItem(request(), busy = false))
        rule.mainClock.advanceTimeBy(5_000)
        assertThat(approvals).isEmpty()
        assertThat(declines).isEmpty()
    }

    @Test
    fun talkBackActivateOnTheSliderOpensTheReviewInsteadOfPaying() {
        show(CollectItem(request(), busy = false))
        rule.onNodeWithTag(PayFlowTestTags.collectApprove("req-1")).performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag(PayFlowTestTags.COLLECT_CONFIRM_SHEET).assertIsDisplayed()
        assertThat(approvals).isEmpty()
        rule.onNodeWithTag(PayFlowTestTags.COLLECT_CONFIRM_PAY).performClick()
        assertThat(approvals).containsExactly("req-1")
    }

    @Test
    fun reducedMotionHasNoSliderAndStillNeedsTheReview() {
        show(CollectItem(request(), busy = false), reduceMotion = true)
        rule.onAllNodesWithTag(PayFlowTestTags.collectApprove("req-1")).assertCountEquals(0)
        rule.onNodeWithTag(PayFlowTestTags.collectPlain("req-1")).performClick()
        assertThat(approvals).isEmpty()
        rule.onNodeWithTag(PayFlowTestTags.COLLECT_CONFIRM_PAY).performClick()
        assertThat(approvals).containsExactly("req-1")
    }

    @Test
    fun aFullSwipePaysOnceAndAShortOneNothing() {
        show(CollectItem(request(), busy = false))
        val slider = rule.onNodeWithTag(PayFlowTestTags.collectApprove("req-1"))
        slider.performTouchInput {
            down(Offset(height / 2f, centerY)); moveTo(Offset(width * 0.5f, centerY)); up()
        }
        rule.waitForIdle()
        assertThat(approvals).isEmpty()
        slider.performTouchInput {
            down(Offset(height / 2f, centerY))
            for (i in 1..20) moveTo(Offset(height / 2f + (width - height) * i / 20f, centerY))
            up()
        }
        rule.waitForIdle()
        assertThat(approvals).containsExactly("req-1")
    }

    @Test
    fun aThousandTapsOnPayAnswerOnce() {
        show(CollectItem(request(), busy = false))
        rule.onNodeWithTag(PayFlowTestTags.collectPlain("req-1")).performClick()
        val pay = rule.onNodeWithTag(PayFlowTestTags.COLLECT_CONFIRM_PAY)
        repeat(1_000) {
            if (it % 50 == 0) ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(600))
            runCatching { pay.performClick() }
        }
        rule.waitForIdle()
        assertThat(approvals).hasSize(1)
    }

    @Test
    fun anExpiredRequestLocksEveryWayToPayIt() {
        show(CollectItem(request(ttlSeconds = 3), busy = false))
        rule.onNodeWithTag(PayFlowTestTags.collectPlain("req-1")).performClick()
        rule.onNodeWithTag(PayFlowTestTags.COLLECT_CONFIRM_SHEET).assertIsDisplayed()
        clock.now = FIXED_INSTANT.plusSeconds(4)
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        // The review closes by itself, and the request moves to Earlier as expired: nothing left to swipe or press.
        rule.onAllNodesWithTag(PayFlowTestTags.COLLECT_CONFIRM_PAY).assertCountEquals(0)
        rule.onAllNodesWithTag(PayFlowTestTags.collectApprove("req-1")).assertCountEquals(0)
        rule.onAllNodesWithTag(PayFlowTestTags.collectPlain("req-1")).assertCountEquals(0)
        rule.onNodeWithText("Expired").assertIsDisplayed()
        assertThat(approvals).isEmpty()
    }

    @Test
    fun theRingCountsDownAndTurnsUrgent() {
        show(CollectItem(request(ttlSeconds = 125), busy = false))
        rule.onNodeWithTag(PayFlowTestTags.collectRing("req-1")).assertContentDescriptionEquals("Expires in 2:05")
        clock.now = FIXED_INSTANT.plusSeconds(10)
        rule.mainClock.advanceTimeBy(1_100)
        rule.onNodeWithTag(PayFlowTestTags.collectRing("req-1")).assertContentDescriptionEquals("Expires in 1:55")
    }

    @Test
    fun declineIsOneTapAndLocksWhileAnAnswerIsInFlight() {
        show(CollectItem(request(), busy = false))
        rule.onNodeWithTag(PayFlowTestTags.collectDecline("req-1")).assertIsEnabled().performClick()
        assertThat(declines).containsExactly("req-1")
        commitState { holder.value = CollectUiState.Content(listOf(CollectItem(request(), busy = true)), refreshing = false) }
        rule.onNodeWithTag(PayFlowTestTags.collectDecline("req-1")).assertIsNotEnabled()
        rule.onNodeWithTag(PayFlowTestTags.collectApprove("req-1")).assertIsNotEnabled()
    }

    @Test
    fun aSpoofedNameCannotHideWhoGetsTheMoney() {
        // A right-to-left override would show "Refund from PayFlow" reversed; it is stripped, and the UPI ID is always shown.
        show(CollectItem(request(name = "‮wolfyap morf dnufeR"), busy = false))
        rule.onAllNodesWithText("‮wolfyap morf dnufeR").assertCountEquals(0)
        rule.onNodeWithText("wolfyap morf dnufeR").assertIsDisplayed()
        rule.onNodeWithText("tara@okhdfc").assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.collectPlain("req-1")).performClick()
        rule.onNodeWithText("Paying sends money from your account. You don't receive anything by approving.").assertIsDisplayed()
    }

    @Test
    fun largeTextKeepsTheAnswerControlsOnScreen() {
        show(CollectItem(request(name = "Shri Venkateswara Lakshmi Narasimha Enterprises"), busy = false), fontScale = 1.3f)
        rule.onNodeWithTag(PayFlowTestTags.collectApprove("req-1")).assertInside(rule.onRoot())
        rule.onNodeWithTag(PayFlowTestTags.collectDecline("req-1")).assertInside(rule.onRoot())
        rule.onNodeWithTag(PayFlowTestTags.collectRing("req-1")).assertInside(rule.onRoot())
    }

    @Test
    fun theCardSlidesInOncePerVisit() {
        rule.mainClock.autoAdvance = false
        val memory = HeadsUpMemory()
        holder.value = CollectUiState.Content(listOf(CollectItem(request(), busy = false)), refreshing = false)
        rule.setDs {
            val current by holder
            CollectScreen(current, CollectCallbacks(), clock = clock, memory = memory)
        }
        rule.mainClock.advanceTimeBy(32)
        val earlyMap = rule.onRoot().captureToImage().toPixelMap()
        rule.mainClock.advanceTimeBy(1_500)
        val settledMap = rule.onRoot().captureToImage().toPixelMap()
        // Sampled where the card finally sits.
        val card = rule.onNodeWithTag(PayFlowTestTags.collectCard("req-1")).fetchSemanticsNode().boundsInRoot
        val x = card.center.x.toInt()
        val y = card.center.y.toInt()
        val early = earlyMap[x, y]
        val settled = settledMap[x, y]
        // Mid-drop the card is still faint and above its place; settled, it is drawn in full.
        assertThat(early).isNotEqualTo(settled)
        assertThat(memory.firstSight("req-1")).isFalse()
        rule.mainClock.autoAdvance = true
        rule.onAllNodesWithText("Tara Book House").assertCountEquals(1)
    }
}
