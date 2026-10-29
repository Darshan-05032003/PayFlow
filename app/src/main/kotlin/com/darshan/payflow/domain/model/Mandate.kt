package com.darshan.payflow.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields

enum class MandateFrequency { DAILY, WEEKLY, MONTHLY, QUARTERLY, YEARLY, AS_PRESENTED }

enum class MandateStatus(val isTerminal: Boolean) { ACTIVE(false), PAUSED(false), REVOKED(true), EXPIRED(true) }

/** User/PSP actions on a mandate. EXPIRE is applied by the app when the end date passes. */
enum class MandateAction { PAUSE, RESUME, REVOKE, EXPIRE }

/** A UPI AutoPay mandate: a standing authorisation for a payee to debit up to [maxAmountPaise] per [frequency] period. */
data class Mandate(
    val id: String,
    /** Unique Mandate Number issued by the PSP/NPCI. */
    val umn: String,
    val payeeVpa: String,
    val payeeName: String,
    val maxAmountPaise: Long,
    val frequency: MandateFrequency,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val status: MandateStatus,
    val note: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastExecutedAt: Instant?,
    val executionCount: Int,
) {
    /** A non-terminal mandate past its end date is shown as expired even before the next sweep persists it. */
    fun effectiveStatus(today: LocalDate): MandateStatus =
        if (!status.isTerminal && endDate.isBefore(today)) MandateStatus.EXPIRED else status
}

object MandateStateMachine {
    /** Returns the new status, or null if the (status, action) pair is illegal. */
    fun transition(from: MandateStatus, action: MandateAction): MandateStatus? = when (from) {
        MandateStatus.ACTIVE -> when (action) {
            MandateAction.PAUSE -> MandateStatus.PAUSED
            MandateAction.REVOKE -> MandateStatus.REVOKED
            MandateAction.EXPIRE -> MandateStatus.EXPIRED
            MandateAction.RESUME -> null
        }
        MandateStatus.PAUSED -> when (action) {
            MandateAction.RESUME -> MandateStatus.ACTIVE
            MandateAction.REVOKE -> MandateStatus.REVOKED
            MandateAction.EXPIRE -> MandateStatus.EXPIRED
            MandateAction.PAUSE -> null
        }
        MandateStatus.REVOKED, MandateStatus.EXPIRED -> null
    }

    /** Actions a user may take from [status] (EXPIRE is never a user action). */
    fun userActions(status: MandateStatus): List<MandateAction> =
        listOf(MandateAction.PAUSE, MandateAction.RESUME, MandateAction.REVOKE).filter { transition(status, it) != null }
}

sealed interface MandateValidation {
    data object Ok : MandateValidation
    data object InvalidAmount : MandateValidation
    data object StartInPast : MandateValidation
    data object EndBeforeStart : MandateValidation
    data object TooLong : MandateValidation
    data object InvalidPayee : MandateValidation
}

sealed interface ExecutionCheck {
    data object Allowed : ExecutionCheck
    data object InvalidAmount : ExecutionCheck
    data object AmountExceedsMax : ExecutionCheck
    data object NotActive : ExecutionCheck
    data object OutsideValidity : ExecutionCheck
    data object AlreadyExecutedThisPeriod : ExecutionCheck
}

/** Pure AutoPay rules: creation limits, and the frequency / max-amount / validity checks applied to every debit. */
object MandateRules {
    const val MAX_VALIDITY_YEARS = 10

    fun validateCreation(maxAmountPaise: Long, startDate: LocalDate, endDate: LocalDate, today: LocalDate): MandateValidation = when {
        maxAmountPaise !in 1..Payment.MAX_AMOUNT_PAISE -> MandateValidation.InvalidAmount
        startDate.isBefore(today) -> MandateValidation.StartInPast
        endDate.isBefore(startDate) -> MandateValidation.EndBeforeStart
        endDate.isAfter(startDate.plusYears(MAX_VALIDITY_YEARS.toLong())) -> MandateValidation.TooLong
        else -> MandateValidation.Ok
    }

    fun checkExecution(mandate: Mandate, amountPaise: Long, now: Instant, zone: ZoneId): ExecutionCheck {
        val today = now.atZone(zone).toLocalDate()
        return when {
            amountPaise <= 0 -> ExecutionCheck.InvalidAmount
            // Validity before status: an ended mandate is reported as "outside validity", the more precise reason.
            today.isBefore(mandate.startDate) || today.isAfter(mandate.endDate) -> ExecutionCheck.OutsideValidity
            mandate.status != MandateStatus.ACTIVE -> ExecutionCheck.NotActive
            amountPaise > mandate.maxAmountPaise -> ExecutionCheck.AmountExceedsMax
            mandate.lastExecutedAt != null && periodKey(mandate.frequency, mandate.lastExecutedAt, zone) == periodKey(mandate.frequency, now, zone) ->
                ExecutionCheck.AlreadyExecutedThisPeriod
            else -> ExecutionCheck.Allowed
        }
    }

    /** Two instants in the same period share a key. AS_PRESENTED has no period (every instant is unique). */
    fun periodKey(frequency: MandateFrequency, at: Instant, zone: ZoneId): String {
        val d = at.atZone(zone).toLocalDate()
        return when (frequency) {
            MandateFrequency.DAILY -> "D$d"
            MandateFrequency.WEEKLY -> "W${d.get(IsoFields.WEEK_BASED_YEAR)}-${d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
            MandateFrequency.MONTHLY -> "M${d.year}-${d.monthValue}"
            MandateFrequency.QUARTERLY -> "Q${d.year}-${d.get(IsoFields.QUARTER_OF_YEAR)}"
            MandateFrequency.YEARLY -> "Y${d.year}"
            MandateFrequency.AS_PRESENTED -> "P${at.toEpochMilli()}"
        }
    }
}
