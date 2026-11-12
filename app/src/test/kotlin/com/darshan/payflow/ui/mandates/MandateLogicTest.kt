package com.darshan.payflow.ui.mandates

import com.darshan.payflow.domain.model.Mandate
import com.darshan.payflow.domain.model.MandateAction
import com.darshan.payflow.domain.model.MandateFrequency
import com.darshan.payflow.domain.model.MandateStatus
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Test

class MandateLogicTest {
    private val zone = ZoneOffset.UTC
    private val today: LocalDate = FIXED_INSTANT.atZone(zone).toLocalDate() // 2026-10-29

    private fun mandate(
        frequency: MandateFrequency = MandateFrequency.MONTHLY,
        start: LocalDate = today,
        end: LocalDate = today.plusYears(1),
        status: MandateStatus = MandateStatus.ACTIVE,
        lastExecutedAt: java.time.Instant? = null,
    ) = Mandate(
        id = "m", umn = "UMN1", payeeVpa = "lumora@okbank", payeeName = "Lumora", maxAmountPaise = 64_900, frequency = frequency,
        startDate = start, endDate = end, status = status, note = "", createdAt = FIXED_INSTANT, updatedAt = FIXED_INSTANT,
        lastExecutedAt = lastExecutedAt, executionCount = if (lastExecutedAt == null) 0 else 1,
    )

    @Test
    fun `a monthly mandate expects a debit each month from its start date`() {
        val dates = MandateSchedule.upcoming(mandate(start = LocalDate.of(2026, 1, 31)), today, zone, count = 4)
        // From the 31st: months without one fall on their last day, and the day never drifts.
        assertThat(dates).containsExactly(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 31)).inOrder()
    }

    @Test
    fun `this period's debit drops off once it has been taken`() {
        val taken = mandate(frequency = MandateFrequency.MONTHLY, start = LocalDate.of(2026, 10, 1), lastExecutedAt = FIXED_INSTANT.minusSeconds(3_600))
        assertThat(MandateSchedule.upcoming(taken, today, zone, count = 2)).containsExactly(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1)).inOrder()
        val notYet = mandate(frequency = MandateFrequency.MONTHLY, start = LocalDate.of(2026, 10, 1))
        // Not taken yet this month: the debit is still due, so it shows as today rather than in the past.
        assertThat(MandateSchedule.upcoming(notYet, today, zone, count = 1)).containsExactly(today)
    }

    @Test
    fun `weekly, daily, quarterly and yearly step by their period`() {
        assertThat(MandateSchedule.upcoming(mandate(frequency = MandateFrequency.WEEKLY), today, zone, 3)).containsExactly(today, today.plusWeeks(1), today.plusWeeks(2)).inOrder()
        assertThat(MandateSchedule.upcoming(mandate(frequency = MandateFrequency.DAILY), today, zone, 2)).containsExactly(today, today.plusDays(1)).inOrder()
        assertThat(MandateSchedule.upcoming(mandate(frequency = MandateFrequency.QUARTERLY), today, zone, 2)).containsExactly(today, today.plusMonths(3)).inOrder()
        assertThat(MandateSchedule.upcoming(mandate(frequency = MandateFrequency.YEARLY, end = today.plusYears(3)), today, zone, 5)).hasSize(4)
    }

    @Test
    fun `nothing is expected from a paused, ended or as-presented mandate`() {
        assertThat(MandateSchedule.upcoming(mandate(status = MandateStatus.PAUSED), today, zone, 3)).isEmpty()
        assertThat(MandateSchedule.upcoming(mandate(status = MandateStatus.REVOKED), today, zone, 3)).isEmpty()
        assertThat(MandateSchedule.upcoming(mandate(frequency = MandateFrequency.AS_PRESENTED), today, zone, 3)).isEmpty()
        assertThat(MandateSchedule.upcoming(mandate(end = today.minusDays(1), start = today.minusMonths(2)), today, zone, 3)).isEmpty()
    }

    @Test
    fun `the schedule stops at the end date and starts no earlier than the start`() {
        val future = mandate(start = today.plusDays(10), end = today.plusDays(40))
        assertThat(MandateSchedule.upcoming(future, today, zone, 5)).containsExactly(today.plusDays(10), today.plusDays(10).plusMonths(1)).inOrder()
    }

    @Test
    fun `why there is no next debit is a reason, not a blank`() {
        assertThat(MandateSchedule.nextDebit(mandate(), today, zone)).isEqualTo(NextDebit.On(today))
        assertThat(MandateSchedule.nextDebit(mandate(status = MandateStatus.PAUSED), today, zone)).isEqualTo(NextDebit.Paused)
        assertThat(MandateSchedule.nextDebit(mandate(frequency = MandateFrequency.AS_PRESENTED), today, zone)).isEqualTo(NextDebit.WhenPresented)
        assertThat(MandateSchedule.nextDebit(mandate(status = MandateStatus.REVOKED), today, zone)).isEqualTo(NextDebit.Ended)
    }

    @Test
    fun `the card's quick action is pause or resume, and nothing on an ended mandate`() {
        assertThat(MandateQuickAction.of(MandateStatus.ACTIVE)).isEqualTo(MandateAction.PAUSE)
        assertThat(MandateQuickAction.of(MandateStatus.PAUSED)).isEqualTo(MandateAction.RESUME)
        assertThat(MandateQuickAction.of(MandateStatus.REVOKED)).isNull()
        assertThat(MandateQuickAction.of(MandateStatus.EXPIRED)).isNull()
        // The icon shows what a tap will do: two bars to pause, a triangle to resume.
        assertThat(MandateQuickAction.morphTarget(MandateStatus.ACTIVE)).isEqualTo(0f)
        assertThat(MandateQuickAction.morphTarget(MandateStatus.PAUSED)).isEqualTo(1f)
    }

    @Test
    fun `a stack swipe past a third of the card, or a fling, moves one card`() {
        val stack = CardStackHolder(count = 3, widthPx = 300f)
        // The owner springs the offset home after each release; snapOffset(0) stands in for that here.
        stack.dragBy(-80f)
        assertThat(stack.release(velocity = 0f)).isEqualTo(0)
        stack.snapOffset(0f)
        stack.dragBy(-120f)
        assertThat(stack.release(velocity = 0f)).isEqualTo(1)
        assertThat(stack.index).isEqualTo(1)
        stack.snapOffset(0f)
        stack.dragBy(-10f)
        assertThat(stack.release(velocity = -CardStackHolder.FLING_VELOCITY)).isEqualTo(2)
        stack.snapOffset(0f)
        stack.dragBy(40f)
        assertThat(stack.release(velocity = CardStackHolder.FLING_VELOCITY)).isEqualTo(1)
    }

    @Test
    fun `the stack resists past its ends and never skips more than one card`() {
        val stack = CardStackHolder(count = 2, widthPx = 300f)
        stack.dragBy(200f)
        assertThat(stack.offset).isLessThan(200f)
        assertThat(stack.release(velocity = 9_000f)).isEqualTo(0)
        stack.snapOffset(0f)
        stack.dragBy(-900f)
        assertThat(stack.release(velocity = -9_000f)).isEqualTo(1)
        stack.snapOffset(0f)
        stack.dragBy(-900f)
        assertThat(stack.release(velocity = -9_000f)).isEqualTo(1)
    }

    @Test
    fun `the stack keeps a valid card when the list shrinks`() {
        val stack = CardStackHolder(count = 5, widthPx = 300f)
        stack.goTo(4)
        stack.resize(2)
        assertThat(stack.index).isEqualTo(1)
        stack.resize(0)
        assertThat(stack.index).isEqualTo(0)
    }

    /** Design QA: each card behind sits exactly one peek lower, whatever the card height (1.3x text makes cards taller). */
    @Test
    fun stackCardsSitOnePeekApartAtAnyHeight() {
        val peek = 24f
        for (height in listOf(300f, 420f, 640f)) {
            for (depth in 0..2) {
                assertThat(StackGeometry.bottom(depth.toFloat(), height, peek)).isWithin(0.01f).of(height + depth * peek)
            }
        }
        assertThat(StackGeometry.scale(0f)).isEqualTo(1f)
        assertThat(StackGeometry.translationY(0f, 500f, peek)).isEqualTo(0f)
    }

    /** Device finding: going back flew the front card off first, which showed the *next* card for half a second. */
    @Test
    fun goingBackBringsThePreviousCardBackFromTheSideItLeftBy() {
        val width = 300f
        assertThat(StackSettle.of(before = 0, target = 1, widthPx = width, flyOut = 1.2f)).isEqualTo(StackSettle.FlyOut(-360f))
        assertThat(StackSettle.of(before = 1, target = 0, widthPx = width, flyOut = 1.2f)).isEqualTo(StackSettle.ReturnFrom(-360f))
        assertThat(StackSettle.of(before = 2, target = 2, widthPx = width, flyOut = 1.2f)).isEqualTo(StackSettle.SpringBack)
    }
}
