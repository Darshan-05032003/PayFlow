package com.darshan.payflow.data.checkout

import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.error.GatewayErrorCodes
import com.darshan.checkoutkit.core.error.GatewayException
import com.darshan.checkoutkit.core.gateway.ChargeRequest
import com.darshan.checkoutkit.core.gateway.GatewayResult
import com.darshan.checkoutkit.core.gateway.PaymentGateway
import com.darshan.checkoutkit.core.gateway.RefundRequest
import com.darshan.checkoutkit.core.gateway.RefundResult
import com.darshan.checkoutkit.core.gateway.StatusQuery
import com.darshan.checkoutkit.core.idempotency.IdempotencyKey
import com.darshan.payflow.data.psp.PspClient
import com.darshan.payflow.data.psp.PspException
import com.darshan.payflow.data.psp.PspInitiateRequest
import com.darshan.payflow.data.psp.PspPaymentStatus
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** The merchant that receives CheckoutKit payments in PayFlow's sandbox. */
data class CheckoutMerchant(val vpa: String, val name: String)

/**
 * Bridges CheckoutKit's [PaymentGateway] to PayFlow's sandbox [PspClient]. The SDK's idempotency key becomes the PSP
 * order reference (`txnRef`), so a retried charge hits the same order; transport failures become typed
 * [GatewayException]s and PSP declines become [GatewayResult.Failed]. The gateway remembers `orderId → txnRef` so the host
 * can store an `Unresolved` outcome (which carries no transaction id) under the reference the PSP knows.
 */
@Singleton
class PayFlowPaymentGateway @Inject constructor(
    private val psp: PspClient,
    private val merchant: CheckoutMerchant,
) : PaymentGateway {
    private val txnRefByOrder = ConcurrentHashMap<String, String>()

    fun txnRefForOrder(orderId: String): String? = txnRefByOrder[orderId]

    override suspend fun charge(request: ChargeRequest): GatewayResult {
        val txnRef = txnRefFor(request.idempotencyKey)
        txnRefByOrder[request.orderId] = txnRef
        rememberAmount(txnRef, request.amountPaise)
        try {
            psp.initiatePayment(
                PspInitiateRequest(
                    txnRef = txnRef, amountPaise = request.amountPaise, payeeVpa = merchant.vpa, payeeName = merchant.name,
                    note = "Checkout ${request.orderId} via ${request.method.type}", idempotencyKey = request.idempotencyKey.value,
                ),
            )
            return psp.getPaymentStatus(txnRef).toGatewayResult()
        } catch (e: PspException) {
            throw GatewayException(e.toCheckoutError())
        }
    }

    override suspend fun status(query: StatusQuery): GatewayResult = try {
        psp.getPaymentStatus(query.transactionId ?: txnRefFor(query.idempotencyKey)).toGatewayResult()
    } catch (e: PspException) {
        throw GatewayException(e.toCheckoutError())
    }

    override suspend fun refund(request: RefundRequest): RefundResult = try {
        val refund = psp.refund(request.transactionId, request.amountPaise, refundKey = request.refundKey.value)
        RefundResult.Refunded(refund.refundId, refund.amountPaise)
    } catch (e: PspException) {
        if (e.retryable) throw GatewayException(e.toCheckoutError()) else RefundResult.Failed(e.toCheckoutError())
    }

    private fun PspPaymentStatus.toGatewayResult(): GatewayResult = when (this) {
        is PspPaymentStatus.Success -> GatewayResult.Succeeded(txnRef, amountPaise)
        is PspPaymentStatus.Refunded -> GatewayResult.Succeeded(txnRef, amountPaise)
        is PspPaymentStatus.Pending -> GatewayResult.Pending(txnRef, amountFor(txnRef))
        is PspPaymentStatus.Failed -> GatewayResult.Failed(GatewayErrorCodes.fromCode(code))
        is PspPaymentStatus.NotFound -> GatewayResult.Failed(CheckoutError.GatewayError("NOT_FOUND", isTransient = false))
    }

    /** The PSP's pending status carries no amount; the SDK's `Pending` does, so the charged amount is kept per order. */
    private val amountByTxnRef = ConcurrentHashMap<String, Long>()
    private fun amountFor(txnRef: String): Long = amountByTxnRef[txnRef] ?: 0L
    private fun rememberAmount(txnRef: String, amountPaise: Long) { amountByTxnRef[txnRef] = amountPaise }

    companion object {
        /** Deterministic, alphanumeric, ≤ 35 chars: the SDK key (UUID) without dashes, prefixed `CK`. */
        fun txnRefFor(key: IdempotencyKey): String = "CK" + key.value.filter { it.isLetterOrDigit() }.take(32)

        fun PspException.toCheckoutError(): CheckoutError = when (this) {
            is PspException.Timeout -> CheckoutError.Timeout
            is PspException.Network -> CheckoutError.Network
            is PspException.RateLimited -> CheckoutError.RateLimited(retryAfterMillis)
            is PspException.Server -> CheckoutError.GatewayError("HTTP_$status", isTransient = true)
            is PspException.Unauthorized -> CheckoutError.AuthenticationFailed
            is PspException.ClientError -> CheckoutError.InvalidRequest(body ?: "HTTP $status")
            is PspException.Malformed -> CheckoutError.GatewayError("MALFORMED_RESPONSE", isTransient = false)
        }
    }
}
