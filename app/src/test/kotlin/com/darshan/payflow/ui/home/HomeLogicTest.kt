package com.darshan.payflow.ui.home

import com.darshan.payflow.ui.components.feedPill

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.darshan.payflow.domain.model.HistorySummary
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.ui.designsystem.components.StatusKind
import com.google.common.truth.Truth.assertThat
import kotlin.math.hypot
import kotlin.random.Random
import org.junit.Test

class HomeLogicTest {
    // Collapsing header

    @Test
    fun `the hero shows the first total as it is and rolls only from one already seen`() {
        assertThat(HomeHeaderLogic.shownTotal(lastShown = null, current = 50_000)).isEqualTo(50_000)
        assertThat(HomeHeaderLogic.shownTotal(lastShown = 10_000, current = 50_000)).isEqualTo(10_000)
        assertThat(HomeHeaderLogic.shownTotal(lastShown = null, current = null)).isEqualTo(0)
        assertThat(HomeHeaderLogic.rollsTo(lastShown = null, current = 50_000)).isFalse()
        assertThat(HomeHeaderLogic.rollsTo(lastShown = 50_000, current = 50_000)).isFalse()
        assertThat(HomeHeaderLogic.rollsTo(lastShown = 10_000, current = 50_000)).isTrue()
    }

    @Test
    fun `progress runs from 0 to 1 over the collapse range and stays there`() {
        assertThat(HomeHeaderLogic.progress(0, 200)).isEqualTo(0f)
        assertThat(HomeHeaderLogic.progress(100, 200)).isEqualTo(0.5f)
        assertThat(HomeHeaderLogic.progress(200, 200)).isEqualTo(1f)
        assertThat(HomeHeaderLogic.progress(5_000, 200)).isEqualTo(1f)
    }

    @Test
    fun `an unmeasured header counts as not collapsed rather than dividing by zero`() {
        assertThat(HomeHeaderLogic.progress(50, 0)).isEqualTo(0f)
        assertThat(HomeHeaderLogic.isDocked(HomeHeaderLogic.progress(50, 0))).isFalse()
    }

    @Test
    fun `the header follows the scroll until the range is used up, then pins`() {
        assertThat(HomeHeaderLogic.pinOffset(100, 200)).isEqualTo(0f)
        assertThat(HomeHeaderLogic.pinOffset(200, 200)).isEqualTo(0f)
        assertThat(HomeHeaderLogic.pinOffset(750, 200)).isEqualTo(550f)
        // Screen position of the header top: minus the scroll plus the pin, never further up than the range.
        listOf(0, 100, 200, 750).forEach { scroll ->
            assertThat(-scroll + HomeHeaderLogic.pinOffset(scroll, 200)).isWithin(0.001f).of(-HomeHeaderLogic.collapsedPx(scroll, 200))
        }
    }

    @Test
    fun `an element docks exactly at its dock position and rests at its rest position`() {
        val rest = 120f
        val dock = 30f
        val range = 200
        fun screenY(scroll: Int): Float {
            val p = HomeHeaderLogic.progress(scroll, range)
            return rest - HomeHeaderLogic.collapsedPx(scroll, range) + HomeHeaderLogic.dockTranslation(p, rest, dock, range)
        }
        assertThat(screenY(0)).isEqualTo(rest)
        assertThat(screenY(200)).isEqualTo(dock)
        assertThat(screenY(100)).isEqualTo((rest + dock) / 2)
        assertThat(screenY(5_000)).isEqualTo(dock)
    }

    @Test
    fun `an element whose dock is its rest position stays still through the whole collapse`() {
        listOf(0, 50, 133, 200).forEach { scroll ->
            val p = HomeHeaderLogic.progress(scroll, 200)
            assertThat(10f - HomeHeaderLogic.collapsedPx(scroll, 200) + HomeHeaderLogic.dockTranslation(p, 10f, 10f, 200)).isWithin(0.01f).of(10f)
        }
    }

    @Test
    fun `the amount scales from the hero size to the top bar size`() {
        val dock = HomeHeaderLogic.dockScale(44f, 20f)
        assertThat(dock).isWithin(0.001f).of(20f / 44f)
        assertThat(HomeHeaderLogic.scaleAt(0f, dock)).isEqualTo(1f)
        assertThat(HomeHeaderLogic.scaleAt(1f, dock)).isWithin(0.001f).of(dock)
    }

    @Test
    fun `label and scaled amount together are centred in the bar`() {
        val dock = 0.5f
        val y = HomeHeaderLogic.labelDockY(topPx = 24f, barPx = 56f, labelPx = 16f, amountPx = 48f, dockScale = dock)
        assertThat(y).isEqualTo(24f + (56f - 16f - 24f) / 2f)
    }

    @Test
    fun `details leave before the amount lands and the wordmark gives way to it`() {
        assertThat(HomeHeaderLogic.detailsAlpha(0f)).isEqualTo(1f)
        assertThat(HomeHeaderLogic.detailsAlpha(0.6f)).isEqualTo(0f)
        assertThat(HomeHeaderLogic.wordmarkAlpha(0f)).isEqualTo(1f)
        assertThat(HomeHeaderLogic.wordmarkAlpha(0.6f)).isEqualTo(0f)
    }

    @Test
    fun `the scan action is gone by the time it reaches the docked bar`() {
        assertThat(HomeHeaderLogic.scanActionAlpha(0f)).isEqualTo(1f)
        assertThat(HomeHeaderLogic.scanActionScale(0f)).isEqualTo(1f)
        assertThat(HomeHeaderLogic.scanActionAlpha(1f)).isEqualTo(0f)
        assertThat(HomeHeaderLogic.scanActionScale(1f)).isLessThan(1f)
        assertThat(HomeHeaderLogic.isDocked(0.99f)).isFalse()
        assertThat(HomeHeaderLogic.isDocked(1f)).isTrue()
    }

    // Hide and show

    @Test
    fun `every scramble frame has the same length as the mask, whatever the amount`() {
        val frames = AmountVeilLogic.scrambleFrames(Random(7))
        assertThat(frames).hasSize(AmountVeilLogic.FRAMES)
        frames.forEach { assertThat(it.length).isEqualTo(maskedAmount().length) }
        frames.forEach { assertThat(it.first()).isEqualTo('₹') }
    }

    @Test
    fun `the scramble changes from frame to frame`() {
        assertThat(AmountVeilLogic.scrambleFrames(Random(7)).toSet().size).isGreaterThan(1)
    }

    @Test
    fun `the veil peaks when the content swaps and is clear at both ends`() {
        assertThat(AmountVeilLogic.veil(0f)).isEqualTo(0f)
        assertThat(AmountVeilLogic.veil(AmountVeilLogic.COVERED)).isEqualTo(1f)
        assertThat(AmountVeilLogic.veil(AmountVeilLogic.DONE)).isEqualTo(0f)
        assertThat(AmountVeilLogic.veil(0.5f)).isEqualTo(0.5f)
        assertThat(AmountVeilLogic.veil(1.5f)).isEqualTo(0.5f)
    }

    @Test
    fun `scramble frames advance with the phase and never overrun`() {
        assertThat(AmountVeilLogic.frameAt(0f)).isEqualTo(0)
        assertThat(AmountVeilLogic.frameAt(AmountVeilLogic.DONE)).isEqualTo(AmountVeilLogic.FRAMES - 1)
        assertThat(AmountVeilLogic.frameAt(9f)).isEqualTo(AmountVeilLogic.FRAMES - 1)
        assertThat(AmountVeilLogic.frameAt(-1f)).isEqualTo(0)
        val frames = (0..20).map { AmountVeilLogic.frameAt(it / 10f) }
        assertThat(frames).isInOrder()
    }

    @Test
    fun `blur level follows the veil in fixed steps, so no effect is built per frame`() {
        assertThat(AmountVeilLogic.blurLevel(0f, 5)).isEqualTo(0)
        assertThat(AmountVeilLogic.blurLevel(1f, 5)).isEqualTo(4)
        assertThat(AmountVeilLogic.blurLevel(0.5f, 5)).isEqualTo(2)
    }

    // Magnetic press

    @Test
    fun `a press at the centre does not pull`() {
        assertThat(MagneticLogic.pull(Offset(50f, 50f), Size(100f, 100f), 10f)).isEqualTo(Offset.Zero)
    }

    @Test
    fun `a press leans towards the finger, and never further than the limit`() {
        val edge = MagneticLogic.pull(Offset(100f, 50f), Size(100f, 100f), 10f)
        assertThat(edge.x).isEqualTo(10f)
        assertThat(edge.y).isEqualTo(0f)
        val halfway = MagneticLogic.pull(Offset(25f, 50f), Size(100f, 100f), 10f)
        assertThat(halfway.x).isEqualTo(-5f)
        listOf(Offset(100f, 100f), Offset(5_000f, -5_000f), Offset(0f, 0f)).forEach {
            val pull = MagneticLogic.pull(it, Size(100f, 100f), 10f)
            assertThat(hypot(pull.x, pull.y)).isAtMost(10.001f)
        }
    }

    @Test
    fun `an element with no size yet does not pull`() {
        assertThat(MagneticLogic.pull(Offset(5f, 5f), Size.Zero, 10f)).isEqualTo(Offset.Zero)
    }

    // Pull to refresh

    @Test
    fun `the page follows the finger up to the threshold, then resists`() {
        assertThat(PullLogic.stretchPx(0f, 200f)).isEqualTo(0f)
        assertThat(PullLogic.stretchPx(0.5f, 200f)).isEqualTo(100f)
        assertThat(PullLogic.stretchPx(1f, 200f)).isEqualTo(200f)
        val far = PullLogic.stretchPx(3f, 200f)
        assertThat(far).isGreaterThan(200f)
        assertThat(far).isLessThan(300.001f)
        assertThat(PullLogic.stretchPx(50f, 200f)).isAtMost(300.001f)
        assertThat(PullLogic.stretchPx(-1f, 200f)).isEqualTo(0f)
    }

    @Test
    fun `the coin turns with the pull and shows its back part of the way`() {
        assertThat(PullLogic.coinDegrees(0f)).isEqualTo(0f)
        assertThat(PullLogic.coinDegrees(1f)).isEqualTo(PullLogic.FLIPS_DEGREES)
        assertThat(PullLogic.showsFront(0f)).isTrue()
        assertThat(PullLogic.showsFront(89f)).isTrue()
        assertThat(PullLogic.showsFront(90.5f)).isFalse()
        assertThat(PullLogic.showsFront(180f)).isFalse()
        assertThat(PullLogic.showsFront(271f)).isTrue()
        assertThat(PullLogic.showsFront(360f)).isTrue()
    }

    @Test
    fun `the threshold tick fires once on the way out and not on the way back`() {
        assertThat(PullLogic.crossedThreshold(0.9f, 1.0f)).isTrue()
        assertThat(PullLogic.crossedThreshold(1.0f, 1.2f)).isFalse()
        assertThat(PullLogic.crossedThreshold(1.2f, 0.9f)).isFalse()
        assertThat(PullLogic.crossedThreshold(0.1f, 0.2f)).isFalse()
    }

    // Hero line and feed labels

    @Test
    fun `the hero line leaves out zero counts`() {
        val summary = HistorySummary(total = 5, success = 3, pending = 0, failed = 2, refunded = 0, sentPaise = 1, receivedPaise = 0)
        assertThat(summary.heroCounts()).containsExactly(HeroCount.Paid(3), HeroCount.Failed(2)).inOrder()
        assertThat(HistorySummary.EMPTY.heroCounts()).isEmpty()
    }

    @Test
    fun `only exceptions get a pill in the feed`() {
        assertThat(PaymentStatus.SUCCESS.feedPill()).isNull()
        assertThat(PaymentStatus.PENDING.feedPill()).isEqualTo(StatusKind.Pending)
        assertThat(PaymentStatus.CREATED.feedPill()).isEqualTo(StatusKind.Pending)
        assertThat(PaymentStatus.FAILED.feedPill()).isEqualTo(StatusKind.Failed)
        assertThat(PaymentStatus.REFUND_PENDING.feedPill()).isEqualTo(StatusKind.Refund)
        assertThat(PaymentStatus.REFUNDED.feedPill()).isEqualTo(StatusKind.Refund)
    }

    // Entrance memory

    @Test
    fun `a row plays its entrance the first time it is seen, and not again`() {
        val memory = HomeEntranceMemory()
        assertThat(memory.firstSight("a")).isTrue()
        assertThat(memory.firstSight("a")).isFalse()
        assertThat(memory.firstSight("b")).isTrue()
    }

    @Test
    fun `the memory stays bounded however many payments pass through`() {
        val memory = HomeEntranceMemory()
        repeat(100_000) { memory.firstSight("p$it") }
        assertThat(memory.seenCount).isAtMost(HomeEntranceMemory.MAX_SEEN)
    }
}
