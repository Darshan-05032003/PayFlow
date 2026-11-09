package com.darshan.payflow.ui.collect

import com.darshan.payflow.domain.model.CollectRequest
import com.darshan.payflow.domain.model.CollectStatus
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import org.junit.Test

class CollectLogicTest {
    private fun request(
        id: String = "r",
        createdAgo: Duration = Duration.ofMinutes(5),
        expiresIn: Duration = Duration.ofMinutes(5),
        status: CollectStatus = CollectStatus.PENDING,
        updatedAt: Instant = FIXED_INSTANT,
    ) = CollectRequest(
        id = id, payeeVpa = "tara@okhdfc", payeeName = "Tara", amountPaise = 4_500, note = "",
        createdAt = FIXED_INSTANT.minus(createdAgo), expiresAt = FIXED_INSTANT.plus(expiresIn), updatedAt = updatedAt,
        status = status, paymentId = null,
    )

    @Test
    fun `the ring shows the share of the request's life that is left`() {
        val c = CollectCountdown.of(request(createdAgo = Duration.ofMinutes(5), expiresIn = Duration.ofMinutes(5)), FIXED_INSTANT)
        assertThat(c.fraction).isWithin(0.001f).of(0.5f)
        assertThat(c.remaining).isEqualTo(Duration.ofMinutes(5))
        assertThat(c.urgency).isEqualTo(Urgency.Calm)
    }

    @Test
    fun `the last two minutes are urgent, and at zero it has expired`() {
        assertThat(CollectCountdown.of(request(expiresIn = Duration.ofSeconds(119)), FIXED_INSTANT).urgency).isEqualTo(Urgency.Soon)
        val gone = CollectCountdown.of(request(expiresIn = Duration.ZERO), FIXED_INSTANT)
        assertThat(gone.urgency).isEqualTo(Urgency.Expired)
        assertThat(gone.fraction).isEqualTo(0f)
        assertThat(CollectCountdown.of(request(expiresIn = Duration.ofSeconds(-30)), FIXED_INSTANT).remaining).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `a request whose clock is wrong never shows more than a full ring`() {
        val c = CollectCountdown.of(request(createdAgo = Duration.ofMinutes(-10), expiresIn = Duration.ofMinutes(5)), FIXED_INSTANT)
        assertThat(c.fraction).isAtMost(1f)
        assertThat(c.fraction).isAtLeast(0f)
    }

    @Test
    fun `labels read as minutes and seconds, then hours, then days`() {
        assertThat(CollectCountdown.label(Duration.ofSeconds(581))).isEqualTo("9:41")
        assertThat(CollectCountdown.label(Duration.ofSeconds(5))).isEqualTo("0:05")
        assertThat(CollectCountdown.label(Duration.ofMinutes(150))).isEqualTo("2h")
        assertThat(CollectCountdown.label(Duration.ofHours(50))).isEqualTo("2d")
        assertThat(CollectCountdown.label(Duration.ZERO)).isEqualTo("0:00")
    }

    @Test
    fun `only a pending, unexpired request that is not already being answered can be paid`() {
        assertThat(CollectApproval.canApprove(CollectItem(request(), busy = false), FIXED_INSTANT)).isTrue()
        assertThat(CollectApproval.canApprove(CollectItem(request(), busy = true), FIXED_INSTANT)).isFalse()
        assertThat(CollectApproval.canApprove(CollectItem(request(expiresIn = Duration.ZERO), busy = false), FIXED_INSTANT)).isFalse()
        assertThat(CollectApproval.canApprove(CollectItem(request(status = CollectStatus.DECLINED), busy = false), FIXED_INSTANT)).isFalse()
        assertThat(CollectApproval.canApprove(CollectItem(request(status = CollectStatus.APPROVED), busy = false), FIXED_INSTANT)).isFalse()
    }

    @Test
    fun `declining is offered wherever paying is, and a busy request can't be answered twice`() {
        assertThat(CollectApproval.canDecline(CollectItem(request(), busy = false), FIXED_INSTANT)).isTrue()
        assertThat(CollectApproval.canDecline(CollectItem(request(), busy = true), FIXED_INSTANT)).isFalse()
        assertThat(CollectApproval.canDecline(CollectItem(request(expiresIn = Duration.ZERO), busy = false), FIXED_INSTANT)).isFalse()
    }

    @Test
    fun `open requests come first with the soonest to expire on top, then answered ones newest first`() {
        val items = listOf(
            CollectItem(request("late", expiresIn = Duration.ofMinutes(30)), false),
            CollectItem(request("done", status = CollectStatus.APPROVED, updatedAt = FIXED_INSTANT.minusSeconds(60)), false),
            CollectItem(request("soon", expiresIn = Duration.ofMinutes(1)), false),
            CollectItem(request("gone", expiresIn = Duration.ofSeconds(-1)), false),
            CollectItem(request("declined", status = CollectStatus.DECLINED, updatedAt = FIXED_INSTANT), false),
        )
        val split = CollectSections.split(items, FIXED_INSTANT)
        assertThat(split.open.map { it.request.id }).containsExactly("soon", "late").inOrder()
        assertThat(split.earlier.map { it.request.id }).containsExactly("declined", "gone", "done").inOrder()
        // An overdue one is shown as expired, even before the next refresh writes it down.
        assertThat(split.earlier.single { it.request.id == "gone" }.request.status).isEqualTo(CollectStatus.EXPIRED)
    }

    @Test
    fun `a request slides in once per visit`() {
        val memory = HeadsUpMemory()
        assertThat(memory.firstSight("a")).isTrue()
        assertThat(memory.firstSight("a")).isFalse()
        assertThat(memory.firstSight("b")).isTrue()
    }

    @Test
    fun `an answer that ends without paying sends the slider home, a payment doesn't`() {
        assertThat(CollectApproval.refused(wasBusy = true, nowBusy = false, status = CollectStatus.PENDING)).isTrue()
        assertThat(CollectApproval.refused(wasBusy = true, nowBusy = false, status = CollectStatus.APPROVED)).isFalse()
        assertThat(CollectApproval.refused(wasBusy = false, nowBusy = false, status = CollectStatus.PENDING)).isFalse()
    }
}
