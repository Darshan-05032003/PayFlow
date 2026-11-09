package com.darshan.payflow.ui.pay

import com.darshan.payflow.core.Money
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.StatusSource
import com.darshan.payflow.domain.model.VpaValidator
import com.darshan.payflow.ui.designsystem.effects.HapticEvent

/** One key of the amount keypad. */
enum class AmountKey(val label: String) {
    D1("1"), D2("2"), D3("3"),
    D4("4"), D5("5"), D6("6"),
    D7("7"), D8("8"), D9("9"),
    Dot("."), D0("0"), Backspace("⌫");

    val isDigit: Boolean get() = this != Dot && this != Backspace

    companion object {
        fun digit(value: Int): AmountKey = entries.first { it.label == value.toString() }
    }
}

enum class AmountRefusal { TOO_LARGE, TOO_MANY_DECIMALS, EXTRA_DOT, NOTHING_TO_DELETE }

sealed interface AmountPress {
    data class Accepted(val text: String) : AmountPress
    data class Refused(val reason: AmountRefusal) : AmountPress
}

/**
 * The keypad's rules. Text is the only state and is parsed exactly, with no floating point. A key that would make the amount
 * invalid is refused, so the keypad can't produce more than [Payment.MAX_AMOUNT_PAISE] at any state.
 */
object AmountInput {
    private const val MAX_DECIMALS = 2

    fun press(text: String, key: AmountKey): AmountPress {
        if (key == AmountKey.Backspace) {
            return if (text.isEmpty()) AmountPress.Refused(AmountRefusal.NOTHING_TO_DELETE) else AmountPress.Accepted(text.dropLast(1))
        }
        if (key == AmountKey.Dot) {
            return when {
                '.' in text -> AmountPress.Refused(AmountRefusal.EXTRA_DOT)
                text.isEmpty() -> AmountPress.Accepted("0.")
                else -> AmountPress.Accepted("$text.")
            }
        }
        if (decimalsOf(text) >= MAX_DECIMALS) return AmountPress.Refused(AmountRefusal.TOO_MANY_DECIMALS)
        val next = if (text == "0") key.label else text + key.label
        val paise = paiseOf(next)
        return if (paise == null || paise > Payment.MAX_AMOUNT_PAISE) AmountPress.Refused(AmountRefusal.TOO_LARGE) else AmountPress.Accepted(next)
    }

    /** Paise for what's typed so far. A trailing dot is fine: "12." means ₹12. Null when there's no number yet. */
    fun paiseOf(text: String): Long? {
        val whole = text.removeSuffix(".")
        return if (whole.isEmpty()) null else Money.parseInrToPaise(whole)
    }

    /** Whether the display should show paise yet: once a dot exists, the user is typing them. */
    fun showsDecimals(text: String): Boolean = '.' in text

    private fun decimalsOf(text: String): Int {
        val dot = text.indexOf('.')
        return if (dot < 0) -1 else text.length - dot - 1
    }
}

/** Why the slider can't be swiped yet. It's said on the slider, so a disabled control never leaves the user guessing. */
enum class PayBlock { ENTER_PAYEE, INVALID_PAYEE, PAYEE_NOT_FOUND, CHECKING_PAYEE, ENTER_NAME, ENTER_AMOUNT, AMOUNT_TOO_LARGE }

object PayReadiness {
    /**
     * Null when the form can be paid. In sandbox mode the directory has to answer first, so the registered name is on screen
     * before the user commits, and nobody pays a name they haven't seen. A provider that can't be reached is a warning, not a block.
     */
    fun blockOf(state: PayUiState): PayBlock? {
        val paise = AmountInput.paiseOf(state.amountText)
        return when {
            state.vpa.isBlank() -> PayBlock.ENTER_PAYEE
            !VpaValidator.isValid(state.vpa) -> PayBlock.INVALID_PAYEE
            state.verification is VpaVerificationState.NotFound -> PayBlock.PAYEE_NOT_FOUND
            state.paymentMode == null -> PayBlock.CHECKING_PAYEE
            state.paymentMode == PaymentMode.SANDBOX && (state.verification is VpaVerificationState.Idle || state.verification is VpaVerificationState.Verifying) -> PayBlock.CHECKING_PAYEE
            state.paymentMode == PaymentMode.SANDBOX && state.name.isBlank() -> PayBlock.ENTER_NAME
            paise == null || paise <= 0 -> PayBlock.ENTER_AMOUNT
            paise > Payment.MAX_AMOUNT_PAISE -> PayBlock.AMOUNT_TOO_LARGE
            else -> null
        }
    }
}

/**
 * Back is blocked only for the short stretch when leaving would strand a call mid-flight, and only up to [BLOCK_LIMIT_MS]:
 * a hung provider must not trap anyone. Past that the user may leave; the payment is already stored and stays pending in
 * History, where the reconciler keeps asking.
 */
object PayBackPolicy {
    const val BLOCK_LIMIT_MS = 15_000L

    fun isRunning(phase: PayPhase): Boolean = when (phase) {
        PayPhase.Submitting, is PayPhase.Confirming -> true
        is PayPhase.ChoosingApp -> phase.picked != null
        else -> false
    }

    fun blocksBack(phase: PayPhase, elapsedMs: Long): Boolean = isRunning(phase) && elapsedMs < BLOCK_LIMIT_MS
}

/** How the wait is described. The copy changes as it gets long, and never promises an outcome. */
enum class ProcessingStage {
    NORMAL, STILL_CHECKING, BANK_SLOW;

    companion object {
        const val STILL_CHECKING_MS = 5_000L

        fun at(elapsedMs: Long): ProcessingStage = when {
            elapsedMs >= PayBackPolicy.BLOCK_LIMIT_MS -> BANK_SLOW
            elapsedMs >= STILL_CHECKING_MS -> STILL_CHECKING
            else -> NORMAL
        }
    }
}

enum class OutcomeKind { SUCCESS, REPORTED_SUCCESS, MARKED_PAID, PENDING, FAILED, REPORTED_FAILURE, MARKED_NOT_PAID, REFUND }

enum class Celebration {
    /** Checkmark, burst, sheen and the confirm haptic: the provider confirmed it. */
    FULL,

    /** Checkmark and the confirm haptic only: the UPI app says so, and PayFlow can't verify that. */
    QUIET,
    NONE,
}

data class PayOutcome(val kind: OutcomeKind, val celebration: Celebration, val haptic: HapticEvent?)

/** What an outcome is allowed to look like. The stored record decides, and every delight is earned here or not at all. */
object PayOutcomes {
    fun of(done: PayPhase.Done): PayOutcome {
        val payment = done.payment
        val source = payment?.statusSource
        val mismatch = done.reason == PayViewModel.MISMATCH_REASON
        return when (payment?.status ?: done.status) {
            PaymentStatus.CREATED, PaymentStatus.PENDING -> PayOutcome(OutcomeKind.PENDING, Celebration.NONE, null)
            PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED -> PayOutcome(OutcomeKind.REFUND, Celebration.NONE, null)
            PaymentStatus.FAILED -> when (source) {
                StatusSource.USER -> PayOutcome(OutcomeKind.MARKED_NOT_PAID, Celebration.NONE, null)
                StatusSource.UPI_APP -> PayOutcome(OutcomeKind.REPORTED_FAILURE, Celebration.NONE, HapticEvent.Reject)
                else -> PayOutcome(OutcomeKind.FAILED, Celebration.NONE, HapticEvent.Reject)
            }
            PaymentStatus.SUCCESS -> when {
                payment == null -> PayOutcome(OutcomeKind.SUCCESS, Celebration.NONE, null)
                source == StatusSource.USER -> PayOutcome(OutcomeKind.MARKED_PAID, Celebration.NONE, null)
                mismatch -> PayOutcome(OutcomeKind.REPORTED_SUCCESS, Celebration.NONE, null)
                source == StatusSource.UPI_APP -> PayOutcome(OutcomeKind.REPORTED_SUCCESS, Celebration.QUIET, HapticEvent.Confirm)
                else -> PayOutcome(OutcomeKind.SUCCESS, Celebration.FULL, HapticEvent.Confirm)
            }
        }
    }
}
