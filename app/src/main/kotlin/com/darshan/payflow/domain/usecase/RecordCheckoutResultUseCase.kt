package com.darshan.payflow.domain.usecase

import com.darshan.checkoutkit.core.CheckoutResult
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentDirection
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.repository.PaymentRepository
import java.time.Clock
import java.util.UUID
import javax.inject.Inject

/** A merchant order paid through the CheckoutKit sheet. */
data class CheckoutOrder(val orderId: String, val amountPaise: Long, val description: String, val merchantVpa: String, val merchantName: String)

/**
 * Writes the sheet's final [CheckoutResult] into PayFlow's history as a CHECKOUT payment. `Cancelled` stores nothing (no
 * money moved); `Unresolved` is stored PENDING under the gateway's order reference so the reconciler can settle it later.
 * Idempotent per order (key `checkout:<orderId>`).
 */
class RecordCheckoutResultUseCase(
    private val payments: PaymentRepository,
    private val clock: Clock,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val txnRefForOrder: (String) -> String?,
) {
    @Inject constructor(payments: PaymentRepository, clock: Clock, gateway: com.darshan.payflow.data.checkout.PayFlowPaymentGateway) :
        this(payments, clock, { UUID.randomUUID().toString() }, gateway::txnRefForOrder)

    suspend operator fun invoke(order: CheckoutOrder, result: CheckoutResult): Payment? {
        val key = "checkout:${order.orderId}"
        payments.findByIdempotencyKey(key)?.let { return it }
        val now = clock.instant()
        val gatewayRef = txnRefForOrder(order.orderId)
        val (status, txnRef, pspTxnId, refundId, reason) = when (result) {
            is CheckoutResult.Success -> Recorded(PaymentStatus.SUCCESS, result.transactionId, result.transactionId, null, null)
            is CheckoutResult.Pending -> Recorded(PaymentStatus.PENDING, result.transactionId, null, null, null)
            is CheckoutResult.Refunded -> Recorded(PaymentStatus.REFUNDED, result.transactionId, result.transactionId, result.refundId, null)
            is CheckoutResult.Failed -> Recorded(PaymentStatus.FAILED, gatewayRef ?: fallbackRef(order), null, null, "CheckoutKit ${result.error.code}")
            is CheckoutResult.Rejected -> Recorded(PaymentStatus.FAILED, gatewayRef ?: fallbackRef(order), null, null, "CheckoutKit rejected: ${result.error.code}")
            is CheckoutResult.Unresolved ->
                if (gatewayRef != null) Recorded(PaymentStatus.PENDING, gatewayRef, null, null, null)
                else Recorded(PaymentStatus.FAILED, fallbackRef(order), null, null, "CheckoutKit UNRESOLVED (${result.lastError.code}), no order reference")
            CheckoutResult.Cancelled -> return null
        }
        val payment = Payment(
            id = idGenerator(), txnRef = txnRef, payeeVpa = order.merchantVpa, payeeName = order.merchantName, amountPaise = order.amountPaise,
            note = order.description, status = status, method = PaymentMethod.CHECKOUT, direction = PaymentDirection.OUTGOING,
            pspTxnId = pspTxnId, upiTxnId = null, refundId = refundId, failureReason = reason, createdAt = now, updatedAt = now, idempotencyKey = key,
        )
        return try {
            payments.create(payment)
            payment
        } catch (e: Exception) {
            payments.findByIdempotencyKey(key) ?: throw e
        }
    }

    private fun fallbackRef(order: CheckoutOrder) = ("CKU" + order.orderId.filter { it.isLetterOrDigit() }).take(35)

    private data class Recorded(val status: PaymentStatus, val txnRef: String, val pspTxnId: String?, val refundId: String?, val reason: String?)
}
