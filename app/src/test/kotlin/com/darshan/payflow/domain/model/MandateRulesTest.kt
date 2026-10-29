package com.darshan.payflow.domain.model

import com.darshan.payflow.testutil.FIXED_INSTANT
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class MandateRulesTest {
    private val zone = ZoneOffset.UTC
    private val today: LocalDate = FIXED_INSTANT.atZone(zone).toLocalDate() // 2026-10-29

    private fun mandate(
        frequency: MandateFrequency = MandateFrequency.MONTHLY,
        status: MandateStatus = MandateStatus.ACTIVE,
        maxAmountPaise: Long = 50_000,
        start: LocalDate = today,
        end: LocalDate = today.plusYears(1),
        lastExecutedAt: java.time.Instant? = null,
    ) = Mandate(
        id = "m1", umn = "UMN1", payeeVpa = "netflix@okbank", payeeName = "Netflix", maxAmountPaise = maxAmountPaise, frequency = frequency,
        startDate = start, endDate = end, status = status, note = "Subscription", createdAt = FIXED_INSTANT, updatedAt = FIXED_INSTANT,
        lastExecutedAt = lastExecutedAt, executionCount = if (lastExecutedAt == null) 0 else 1,
    )

    @Test
    fun `creation rules - amount, validity window and dates`() {
        assertThat(MandateRules.validateCreation(50_000, today, today.plusMonths(6), today)).isEqualTo(MandateValidation.Ok)
        assertThat(MandateRules.validateCreation(0, today, today.plusMonths(6), today)).isEqualTo(MandateValidation.InvalidAmount)
        assertThat(MandateRules.validateCreation(-1, today, today.plusMonths(6), today)).isEqualTo(MandateValidation.InvalidAmount)
        assertThat(MandateRules.validateCreation(Payment.MAX_AMOUNT_PAISE + 1, today, today.plusMonths(6), today)).isEqualTo(MandateValidation.InvalidAmount)
        assertThat(MandateRules.validateCreation(1, today.minusDays(1), today.plusMonths(6), today)).isEqualTo(MandateValidation.StartInPast)
        assertThat(MandateRules.validateCreation(1, today, today.minusDays(1), today)).isEqualTo(MandateValidation.EndBeforeStart)
        assertThat(MandateRules.validateCreation(1, today, today.plusYears(MandateRules.MAX_VALIDITY_YEARS.toLong()).plusDays(1), today)).isEqualTo(MandateValidation.TooLong)
        assertThat(MandateRules.validateCreation(1, today, today, today)).isEqualTo(MandateValidation.Ok) // one-day mandate
    }

    @Test
    fun `execution - amount above the cap, inactive status and validity window are rejected`() {
        val now = FIXED_INSTANT
        assertThat(MandateRules.checkExecution(mandate(), 50_000, now, zone)).isEqualTo(ExecutionCheck.Allowed)
        assertThat(MandateRules.checkExecution(mandate(), 50_001, now, zone)).isEqualTo(ExecutionCheck.AmountExceedsMax)
        assertThat(MandateRules.checkExecution(mandate(), 0, now, zone)).isEqualTo(ExecutionCheck.InvalidAmount)
        assertThat(MandateRules.checkExecution(mandate(status = MandateStatus.PAUSED), 100, now, zone)).isEqualTo(ExecutionCheck.NotActive)
        assertThat(MandateRules.checkExecution(mandate(status = MandateStatus.REVOKED), 100, now, zone)).isEqualTo(ExecutionCheck.NotActive)
        assertThat(MandateRules.checkExecution(mandate(start = today.plusDays(1)), 100, now, zone)).isEqualTo(ExecutionCheck.OutsideValidity)
        assertThat(MandateRules.checkExecution(mandate(end = today.minusDays(1)), 100, now, zone)).isEqualTo(ExecutionCheck.OutsideValidity)
    }

    @Test
    fun `execution - one debit per period for each frequency`() {
        val now = FIXED_INSTANT // Thu 2026-10-29 10:15 UTC
        fun check(freq: MandateFrequency, lastAt: java.time.Instant) = MandateRules.checkExecution(mandate(frequency = freq, lastExecutedAt = lastAt), 100, now, zone)

        assertThat(check(MandateFrequency.DAILY, now.minusSeconds(3600))).isEqualTo(ExecutionCheck.AlreadyExecutedThisPeriod)
        assertThat(check(MandateFrequency.DAILY, now.minusSeconds(24 * 3600))).isEqualTo(ExecutionCheck.Allowed)

        assertThat(check(MandateFrequency.WEEKLY, now.minusSeconds(3 * 24 * 3600))).isEqualTo(ExecutionCheck.AlreadyExecutedThisPeriod) // Monday same ISO week
        assertThat(check(MandateFrequency.WEEKLY, now.minusSeconds(4 * 24 * 3600))).isEqualTo(ExecutionCheck.Allowed) // previous Sunday

        assertThat(check(MandateFrequency.MONTHLY, now.minusSeconds(20L * 24 * 3600))).isEqualTo(ExecutionCheck.AlreadyExecutedThisPeriod) // Oct 9
        assertThat(check(MandateFrequency.MONTHLY, now.minusSeconds(30L * 24 * 3600))).isEqualTo(ExecutionCheck.Allowed) // Sep 29

        assertThat(check(MandateFrequency.QUARTERLY, now.minusSeconds(28L * 24 * 3600))).isEqualTo(ExecutionCheck.AlreadyExecutedThisPeriod) // Oct 1 is Q4
        assertThat(check(MandateFrequency.QUARTERLY, now.minusSeconds(29L * 24 * 3600))).isEqualTo(ExecutionCheck.Allowed) // Sep 30 is Q3

        assertThat(check(MandateFrequency.YEARLY, now.minusSeconds(300L * 24 * 3600))).isEqualTo(ExecutionCheck.AlreadyExecutedThisPeriod)
        assertThat(check(MandateFrequency.YEARLY, now.minusSeconds(302L * 24 * 3600))).isEqualTo(ExecutionCheck.Allowed) // 2025

        assertThat(check(MandateFrequency.AS_PRESENTED, now.minusSeconds(1))).isEqualTo(ExecutionCheck.Allowed)
    }

    @Test
    fun `period boundaries use the given time zone, not UTC`() {
        // 2026-10-29T20:30Z is already Oct 30 in IST (+05:30).
        val ist = java.time.ZoneId.of("Asia/Kolkata")
        val lateEvening = java.time.Instant.parse("2026-10-29T20:30:00Z")
        val m = mandate(frequency = MandateFrequency.DAILY, lastExecutedAt = FIXED_INSTANT)
        assertThat(MandateRules.checkExecution(m, 100, lateEvening, ZoneOffset.UTC)).isEqualTo(ExecutionCheck.AlreadyExecutedThisPeriod)
        assertThat(MandateRules.checkExecution(m, 100, lateEvening, ist)).isEqualTo(ExecutionCheck.Allowed)
    }

    @Test
    fun `state machine - every status and action pair is classified`() {
        val legal = mapOf(
            (MandateStatus.ACTIVE to MandateAction.PAUSE) to MandateStatus.PAUSED,
            (MandateStatus.ACTIVE to MandateAction.REVOKE) to MandateStatus.REVOKED,
            (MandateStatus.ACTIVE to MandateAction.EXPIRE) to MandateStatus.EXPIRED,
            (MandateStatus.PAUSED to MandateAction.RESUME) to MandateStatus.ACTIVE,
            (MandateStatus.PAUSED to MandateAction.REVOKE) to MandateStatus.REVOKED,
            (MandateStatus.PAUSED to MandateAction.EXPIRE) to MandateStatus.EXPIRED,
        )
        for (status in MandateStatus.entries) for (action in MandateAction.entries) {
            val expected = legal[status to action]
            assertThat(MandateStateMachine.transition(status, action)).isEqualTo(expected)
        }
        assertThat(MandateStatus.REVOKED.isTerminal).isTrue()
        assertThat(MandateStatus.EXPIRED.isTerminal).isTrue()
        assertThat(MandateStatus.ACTIVE.isTerminal).isFalse()
    }

    @Test
    fun `effective status derives expiry from the clock`() {
        assertThat(mandate(end = today.minusDays(1)).effectiveStatus(today)).isEqualTo(MandateStatus.EXPIRED)
        assertThat(mandate(end = today).effectiveStatus(today)).isEqualTo(MandateStatus.ACTIVE)
        assertThat(mandate(status = MandateStatus.REVOKED, end = today.minusDays(1)).effectiveStatus(today)).isEqualTo(MandateStatus.REVOKED)
    }
}
