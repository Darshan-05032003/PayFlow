package com.darshan.payflow.ui.pay

import com.darshan.payflow.ui.designsystem.effects.HapticEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SwipeToPayHolderTest {
    private val holder = SwipeToPayHolder(detents = 8, commitAt = 0.9f)

    private fun drag(to: Float): List<SwipeSignal> = holder.dragBy(to - holder.fraction)

    @Test
    fun `below the threshold a release springs back and commits nothing`() {
        holder.begin(enabled = true)
        drag(0.89f)
        val signals = holder.release()
        assertThat(signals).containsExactly(SwipeSignal.SnappedBack)
        assertThat(holder.phase).isEqualTo(SwipePhase.SpringingBack)
    }

    @Test
    fun `at the threshold a release commits exactly once`() {
        holder.begin(enabled = true)
        assertThat(drag(0.9f)).contains(SwipeSignal.Armed)
        assertThat(holder.release()).containsExactly(SwipeSignal.Committed)
        assertThat(holder.phase).isEqualTo(SwipePhase.Committed)
        assertThat(holder.fraction).isEqualTo(1f)
    }

    @Test
    fun `speed counts for nothing, because a release has no velocity to consider`() {
        // A fling that only reaches a quarter of the track: the position decides, and it is short.
        holder.begin(enabled = true)
        drag(0.25f)
        assertThat(holder.release()).doesNotContain(SwipeSignal.Committed)
    }

    @Test
    fun `an interrupted drag past the threshold does nothing`() {
        holder.begin(enabled = true)
        drag(0.99f)
        assertThat(holder.cancel()).containsExactly(SwipeSignal.SnappedBack)
        assertThat(holder.phase).isEqualTo(SwipePhase.SpringingBack)
    }

    @Test
    fun `dragging back under the threshold disarms it`() {
        holder.begin(enabled = true)
        drag(0.95f)
        assertThat(holder.phase).isEqualTo(SwipePhase.Armed)
        drag(0.5f)
        assertThat(holder.phase).isEqualTo(SwipePhase.Dragging)
        assertThat(holder.release()).containsExactly(SwipeSignal.SnappedBack)
    }

    @Test
    fun `jitter across the threshold arms once, not per frame`() {
        holder.begin(enabled = true)
        val armed = (0 until 100).flatMap { i -> drag(if (i % 2 == 0) 0.9f else 0.895f) }.count { it == SwipeSignal.Armed }
        assertThat(armed).isEqualTo(1)
    }

    @Test
    fun `one tick per detent forward, and going back and forward again does not re-tick below the high mark`() {
        holder.begin(enabled = true)
        assertThat(drag(0.5f).count { it == SwipeSignal.Tick }).isEqualTo(4)
        assertThat(drag(0.1f).count { it == SwipeSignal.Tick }).isEqualTo(0)
        assertThat(drag(0.5f).count { it == SwipeSignal.Tick }).isEqualTo(0)
        assertThat(drag(0.63f).count { it == SwipeSignal.Tick }).isEqualTo(1)
    }

    @Test
    fun `a disabled slider refuses with a reject and never moves`() {
        assertThat(holder.begin(enabled = false)).containsExactly(SwipeSignal.Refused)
        assertThat(holder.dragBy(0.95f)).isEmpty()
        assertThat(holder.release()).isEmpty()
        assertThat(holder.fraction).isEqualTo(0f)
    }

    @Test
    fun `the fraction is clamped to the track`() {
        holder.begin(enabled = true)
        holder.dragBy(-5f)
        assertThat(holder.fraction).isEqualTo(0f)
        holder.dragBy(50f)
        assertThat(holder.fraction).isEqualTo(1f)
    }

    @Test
    fun `after a commit a thousand more gestures commit nothing until the owner reopens it`() {
        holder.begin(enabled = true); drag(1f)
        assertThat(holder.release()).containsExactly(SwipeSignal.Committed)
        var commits = 0
        repeat(1_000) {
            holder.begin(enabled = true)
            holder.dragBy(1f)
            commits += holder.release().count { it == SwipeSignal.Committed }
        }
        assertThat(commits).isEqualTo(0)
        holder.reopen()
        assertThat(holder.phase).isEqualTo(SwipePhase.SpringingBack)
        holder.settled()
        assertThat(holder.fraction).isEqualTo(0f)
        assertThat(holder.phase).isEqualTo(SwipePhase.Idle)
        holder.begin(enabled = true); holder.dragBy(1f)
        assertThat(holder.release()).containsExactly(SwipeSignal.Committed)
    }

    @Test
    fun `a second finger cannot restart a drag in progress`() {
        holder.begin(enabled = true)
        drag(0.5f)
        assertThat(holder.begin(enabled = true)).isEmpty()
        assertThat(holder.fraction).isEqualTo(0.5f)
    }

    @Test
    fun `catching the handle mid spring continues from where it is`() {
        holder.begin(enabled = true); drag(0.6f); holder.release()
        holder.springTo(0.3f)
        assertThat(holder.begin(enabled = true)).isEmpty()
        assertThat(holder.phase).isEqualTo(SwipePhase.Dragging)
        assertThat(holder.fraction).isEqualTo(0.3f)
    }

    @Test
    fun `a stale spring frame cannot move the handle once a new drag owns it`() {
        holder.begin(enabled = true); drag(0.6f); holder.release()
        holder.begin(enabled = true)
        holder.springTo(0.1f)
        assertThat(holder.fraction).isEqualTo(0.6f)
    }

    @Test
    fun `every signal has its own haptic`() {
        assertThat(SwipeSignal.Tick.haptic).isEqualTo(HapticEvent.Tick)
        assertThat(SwipeSignal.Armed.haptic).isEqualTo(HapticEvent.Light)
        assertThat(SwipeSignal.Committed.haptic).isEqualTo(HapticEvent.Heavy)
        assertThat(SwipeSignal.Refused.haptic).isEqualTo(HapticEvent.Reject)
        assertThat(SwipeSignal.SnappedBack.haptic).isNull()
    }
}
