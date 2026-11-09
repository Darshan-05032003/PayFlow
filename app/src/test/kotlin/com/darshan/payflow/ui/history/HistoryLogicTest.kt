package com.darshan.payflow.ui.history

import com.darshan.payflow.domain.model.PaymentDirection
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.testPayment
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

class HistoryLogicTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val today: LocalDate = FIXED_INSTANT.atZone(zone).toLocalDate()

    private fun daysAgo(days: Long, hours: Long = 0) = FIXED_INSTANT.minus(Duration.ofDays(days)).minus(Duration.ofHours(hours))

    @Test
    fun `payments are grouped under one header per calendar day, newest first`() {
        val payments = listOf(
            testPayment("a", createdAt = FIXED_INSTANT, status = PaymentStatus.SUCCESS, amountPaise = 1_000),
            testPayment("b", createdAt = daysAgo(0, 2), status = PaymentStatus.SUCCESS, amountPaise = 2_000),
            testPayment("c", createdAt = daysAgo(1), status = PaymentStatus.FAILED, amountPaise = 5_000),
            testPayment("d", createdAt = daysAgo(40), status = PaymentStatus.SUCCESS, amountPaise = 700),
        )
        val entries = HistorySections.build(payments, today, zone)
        assertThat(entries.map { it.key }).containsExactly("day:$today", "row:a", "row:b", "day:${today.minusDays(1)}", "row:c", "day:${today.minusDays(40)}", "row:d").inOrder()
        val first = entries[0] as HistoryEntry.Day
        assertThat(first.label).isEqualTo(DayLabel.Today)
        assertThat(first.count).isEqualTo(2)
        assertThat((entries[3] as HistoryEntry.Day).label).isEqualTo(DayLabel.Yesterday)
        assertThat((entries[5] as HistoryEntry.Day).label).isEqualTo(DayLabel.Date(today.minusDays(40), showYear = false))
    }

    @Test
    fun `a day's total is what was really sent that day, nothing failed, pending or received`() {
        val payments = listOf(
            testPayment("a", status = PaymentStatus.SUCCESS, amountPaise = 1_000),
            testPayment("b", status = PaymentStatus.FAILED, amountPaise = 9_000),
            testPayment("c", status = PaymentStatus.PENDING, amountPaise = 9_000),
            testPayment("d", status = PaymentStatus.SUCCESS, amountPaise = 9_000, direction = PaymentDirection.INCOMING),
            testPayment("e", status = PaymentStatus.SUCCESS, amountPaise = 250),
        )
        val day = HistorySections.build(payments, today, zone).first() as HistoryEntry.Day
        assertThat(day.sentPaise).isEqualTo(1_250)
        assertThat(day.count).isEqualTo(5)
    }

    @Test
    fun `rows know their place in the day so the card can round only its ends`() {
        val payments = listOf(testPayment("a"), testPayment("b"), testPayment("c"), testPayment("z", createdAt = daysAgo(3)))
        val rows = HistorySections.build(payments, today, zone).filterIsInstance<HistoryEntry.Row>()
        assertThat(rows.map { it.first to it.last }).containsExactly(true to false, false to false, false to true, true to true).inOrder()
    }

    @Test
    fun `the year shows only for another year`() {
        val lastYear = FIXED_INSTANT.atZone(zone).minusYears(1).toInstant()
        val day = HistorySections.build(listOf(testPayment("a", createdAt = lastYear)), today, zone).first() as HistoryEntry.Day
        assertThat(day.label).isEqualTo(DayLabel.Date(today.minusYears(1), showYear = true))
    }

    @Test
    fun `the day follows the user's zone, not UTC`() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        // 20:00 UTC is 01:30 the next morning in India.
        val late = FIXED_INSTANT.atZone(zone).withHour(20).toInstant()
        val localToday = late.atZone(kolkata).toLocalDate()
        val day = HistorySections.build(listOf(testPayment("a", createdAt = late)), localToday, kolkata).first() as HistoryEntry.Day
        assertThat(day.date).isEqualTo(localToday)
        assertThat(day.label).isEqualTo(DayLabel.Today)
    }

    @Test
    fun `an empty list has no headers, and keys are unique for 10,000 rows`() {
        assertThat(HistorySections.build(emptyList(), today, zone)).isEmpty()
        val many = (0 until 10_000).map { testPayment("p$it", createdAt = FIXED_INSTANT.minusSeconds(it * 600L)) }
        val entries = HistorySections.build(many, today, zone)
        assertThat(entries.map { it.key }.toSet()).hasSize(entries.size)
        assertThat(entries.count { it is HistoryEntry.Row }).isEqualTo(10_000)
        assertThat(entries.map { it.contentType }.toSet()).containsExactly(HistoryEntry.TYPE_DAY, HistoryEntry.TYPE_ROW)
    }

    @Test
    fun `swipe actions never offer paying again while a payment is unsettled`() {
        assertThat(HistoryRowActions.of(testPayment("a", status = PaymentStatus.SUCCESS))).containsExactly(RowAction.PayAgain)
        assertThat(HistoryRowActions.of(testPayment("a", status = PaymentStatus.FAILED))).containsExactly(RowAction.PayAgain)
        assertThat(HistoryRowActions.of(testPayment("a", status = PaymentStatus.PENDING))).containsExactly(RowAction.CheckStatus)
        assertThat(HistoryRowActions.of(testPayment("a", status = PaymentStatus.CREATED))).containsExactly(RowAction.CheckStatus)
        assertThat(HistoryRowActions.of(testPayment("a", status = PaymentStatus.REFUND_PENDING))).isEmpty()
        assertThat(HistoryRowActions.of(testPayment("a", status = PaymentStatus.REFUNDED))).containsExactly(RowAction.PayAgain)
    }

    @Test
    fun `a UPI-app payment has no provider to ask, and money received cannot be paid again`() {
        val upiApp = testPayment("a", status = PaymentStatus.PENDING).copy(handlerPackage = "com.example.gpay")
        assertThat(HistoryRowActions.of(upiApp)).isEmpty()
        val incoming = testPayment("b", status = PaymentStatus.SUCCESS, direction = PaymentDirection.INCOMING)
        assertThat(HistoryRowActions.of(incoming)).isEmpty()
    }

    @Test
    fun `a row swipe opens past half the tray and closes otherwise`() {
        val holder = RowSwipeHolder(trayPx = 200f)
        holder.dragBy(-90f)
        assertThat(holder.offset).isEqualTo(-90f)
        assertThat(holder.release(velocity = 0f)).isEqualTo(RowSwipeTarget.Closed)
        holder.snapTo(0f)
        holder.dragBy(-120f)
        assertThat(holder.release(velocity = 0f)).isEqualTo(RowSwipeTarget.Open)
    }

    @Test
    fun `a fling decides by direction, and dragging the wrong way or too far resists`() {
        val holder = RowSwipeHolder(trayPx = 200f)
        holder.dragBy(-30f)
        assertThat(holder.release(velocity = -RowSwipeHolder.FLING_VELOCITY)).isEqualTo(RowSwipeTarget.Open)
        holder.snapTo(-200f)
        assertThat(holder.release(velocity = RowSwipeHolder.FLING_VELOCITY)).isEqualTo(RowSwipeTarget.Closed)

        val fresh = RowSwipeHolder(trayPx = 200f)
        fresh.dragBy(80f)
        assertThat(fresh.offset).isEqualTo(0f)
        fresh.dragBy(-300f)
        assertThat(fresh.offset).isGreaterThan(-300f)
        assertThat(fresh.offset).isLessThan(-200f)
    }

    @Test
    fun `a row with no actions does not move`() {
        val holder = RowSwipeHolder(trayPx = 0f)
        holder.dragBy(-150f)
        assertThat(holder.offset).isEqualTo(0f)
        assertThat(holder.release(velocity = -9_000f)).isEqualTo(RowSwipeTarget.Closed)
    }

    @Test
    fun `the search bar stays open while there is a query, whatever the user taps`() {
        assertThat(SearchMorph.expanded(userOpened = false, query = "")).isFalse()
        assertThat(SearchMorph.expanded(userOpened = true, query = "")).isTrue()
        assertThat(SearchMorph.expanded(userOpened = false, query = "asha")).isTrue()
    }
}
