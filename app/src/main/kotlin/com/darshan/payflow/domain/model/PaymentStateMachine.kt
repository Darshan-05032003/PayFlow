package com.darshan.payflow.domain.model

import java.time.Instant

/** Events that move a payment through its lifecycle. */
sealed interface PaymentEvent {
    /** The payment was handed to the PSP / UPI app. CREATED → PENDING. */
    data object Initiate : PaymentEvent

    /** The PSP status check confirmed the capture. PENDING → SUCCESS. */
    data class Confirm(val pspTxnId: String, val upiTxnId: String?) : PaymentEvent

    /** CREATED / PENDING → FAILED. */
    data class Fail(val reason: String) : PaymentEvent

    /** SUCCESS → REFUND_PENDING. */
    data object RequestRefund : PaymentEvent

    /** REFUND_PENDING → REFUNDED. */
    data class RefundConfirmed(val refundId: String) : PaymentEvent

    /** REFUND_PENDING → SUCCESS (the money stays with the payee). */
    data object RefundFailed : PaymentEvent
}

sealed interface TransitionResult {
    data class Accepted(val status: PaymentStatus) : TransitionResult
    data class Rejected(val from: PaymentStatus, val event: PaymentEvent) : TransitionResult
}

class IllegalTransitionException(val from: PaymentStatus, val event: PaymentEvent) :
    IllegalStateException("Illegal transition from $from on ${event::class.simpleName}")

class PaymentNotFoundException(val id: String) : NoSuchElementException("Payment $id not found")

/** Pure transition function. Every (state, event) pair not listed here is rejected and leaves the payment untouched. */
object PaymentStateMachine {
    fun transition(from: PaymentStatus, event: PaymentEvent): TransitionResult {
        val to: PaymentStatus? = when (from) {
            PaymentStatus.CREATED -> when (event) {
                PaymentEvent.Initiate -> PaymentStatus.PENDING
                is PaymentEvent.Fail -> PaymentStatus.FAILED
                else -> null
            }
            PaymentStatus.PENDING -> when (event) {
                is PaymentEvent.Confirm -> PaymentStatus.SUCCESS
                is PaymentEvent.Fail -> PaymentStatus.FAILED
                else -> null
            }
            PaymentStatus.SUCCESS -> when (event) {
                PaymentEvent.RequestRefund -> PaymentStatus.REFUND_PENDING
                else -> null
            }
            PaymentStatus.REFUND_PENDING -> when (event) {
                is PaymentEvent.RefundConfirmed -> PaymentStatus.REFUNDED
                PaymentEvent.RefundFailed -> PaymentStatus.SUCCESS
                else -> null
            }
            PaymentStatus.FAILED, PaymentStatus.REFUNDED -> null
        }
        return if (to == null) TransitionResult.Rejected(from, event) else TransitionResult.Accepted(to)
    }

    /** Applies [event] to [payment], returning the updated copy or an [IllegalTransitionException]. */
    fun apply(payment: Payment, event: PaymentEvent, now: Instant): Result<Payment> =
        when (val result = transition(payment.status, event)) {
            is TransitionResult.Rejected -> Result.failure(IllegalTransitionException(payment.status, event))
            is TransitionResult.Accepted -> Result.success(
                when (event) {
                    is PaymentEvent.Confirm -> payment.copy(status = result.status, pspTxnId = event.pspTxnId, upiTxnId = event.upiTxnId ?: payment.upiTxnId, updatedAt = now)
                    is PaymentEvent.Fail -> payment.copy(status = result.status, failureReason = event.reason, updatedAt = now)
                    is PaymentEvent.RefundConfirmed -> payment.copy(status = result.status, refundId = event.refundId, updatedAt = now)
                    PaymentEvent.Initiate, PaymentEvent.RequestRefund, PaymentEvent.RefundFailed ->
                        payment.copy(status = result.status, updatedAt = now)
                },
            )
        }
}
