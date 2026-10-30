package com.darshan.payflow.data.checkout

import com.darshan.checkoutkit.core.PaymentMethod
import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.error.GatewayException
import com.darshan.checkoutkit.core.gateway.ChargeRequest
import com.darshan.checkoutkit.core.gateway.GatewayResult
import com.darshan.checkoutkit.core.gateway.RefundRequest
import com.darshan.checkoutkit.core.gateway.RefundResult
import com.darshan.checkoutkit.core.gateway.StatusQuery
import com.darshan.checkoutkit.core.idempotency.IdempotencyKey
import com.darshan.payflow.data.psp.FakeOutcome
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.psp.FakePspConfig
import com.darshan.payflow.data.psp.PspException
import com.darshan.payflow.data.psp.PspPaymentStatus
import com.darshan.payflow.testutil.StubPsp
import com.darshan.payflow.testutil.fixedClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PayFlowPaymentGatewayTest {
    private val key = IdempotencyKey("11111111-2222-4333-8444-555555555555")
    private val charge = ChargeRequest(key, orderId = "order-42", amountPaise = 49_900, currency = "INR", method = PaymentMethod.Upi("alice@okbank"))
    private fun gateway(psp: com.darshan.payflow.data.psp.PspClient) = PayFlowPaymentGateway(psp, CheckoutMerchant("payflow.store@okbank", "PayFlow Store"))

    @Test
    fun `charge registers the order with the merchant as payee and reports the captured amount`() = runTest {
        val psp = FakePsp(fixedClock)
        val result = gateway(psp).charge(charge) as GatewayResult.Succeeded
        assertThat(result.amountPaise).isEqualTo(49_900)
        assertThat(result.transactionId).isEqualTo(PayFlowPaymentGateway.txnRefFor(key))
        val order = psp.orders.values.single()
        assertThat(order.payeeVpa).isEqualTo("payflow.store@okbank")
        assertThat(order.amountPaise).isEqualTo(49_900)
        assertThat(gateway(psp).txnRefForOrder("order-42")).isNull() // mapping is per gateway instance
    }

    @Test
    fun `charging the same key twice registers once (psp idempotency by txnRef)`() = runTest {
        val psp = FakePsp(fixedClock)
        val g = gateway(psp)
        g.charge(charge); g.charge(charge)
        assertThat(psp.orders).hasSize(1)
        assertThat(g.txnRefForOrder("order-42")).isEqualTo(PayFlowPaymentGateway.txnRefFor(key))
    }

    @Test
    fun `pending, failed and refund map to the sdk types`() = runTest {
        val pending = FakePsp(fixedClock, FakePspConfig(pendingPolls = 1))
        val g = gateway(pending)
        assertThat(g.charge(charge)).isEqualTo(GatewayResult.Pending(PayFlowPaymentGateway.txnRefFor(key), 49_900))
        assertThat(g.status(StatusQuery(key, null))).isInstanceOf(GatewayResult.Succeeded::class.java)

        val declined = gateway(FakePsp(fixedClock, FakePspConfig(outcome = FakeOutcome.FAILED)))
        val failed = declined.charge(charge) as GatewayResult.Failed
        assertThat(failed.error).isEqualTo(CheckoutError.Declined) // sandbox code ZA (declined by payer) → Declined

        val refund = g.refund(RefundRequest(IdempotencyKey("11111111-2222-4333-8444-555555555556"), PayFlowPaymentGateway.txnRefFor(key), 49_900)) as RefundResult.Refunded
        assertThat(refund.amountPaise).isEqualTo(49_900)
        assertThat(pending.getPaymentStatus(PayFlowPaymentGateway.txnRefFor(key))).isInstanceOf(PspPaymentStatus.Refunded::class.java)
        val again = g.refund(RefundRequest(IdempotencyKey("11111111-2222-4333-8444-555555555556"), PayFlowPaymentGateway.txnRefFor(key), 49_900))
        assertThat(again).isEqualTo(refund) // idempotent by refund key
    }

    @Test
    fun `transport errors become GatewayException with the matching typed error`() = runTest {
        suspend fun errorFor(e: PspException): CheckoutError {
            val psp = object : StubPsp() { override suspend fun initiatePayment(request: com.darshan.payflow.data.psp.PspInitiateRequest) = throw e }
            return (runCatching { gateway(psp).charge(charge) }.exceptionOrNull() as GatewayException).error
        }
        assertThat(errorFor(PspException.Timeout())).isEqualTo(CheckoutError.Timeout)
        assertThat(errorFor(PspException.Network())).isEqualTo(CheckoutError.Network)
        assertThat(errorFor(PspException.RateLimited(2_000))).isEqualTo(CheckoutError.RateLimited(2_000))
        assertThat(errorFor(PspException.Server(503))).isEqualTo(CheckoutError.GatewayError("HTTP_503", isTransient = true))
        assertThat(errorFor(PspException.Unauthorized())).isEqualTo(CheckoutError.AuthenticationFailed)
        assertThat(errorFor(PspException.ClientError(422, "bad"))).isInstanceOf(CheckoutError.InvalidRequest::class.java)
        assertThat(errorFor(PspException.Malformed("x"))).isEqualTo(CheckoutError.GatewayError("MALFORMED_RESPONSE", isTransient = false))
    }

    @Test
    fun `a psp success for a different amount is reported truthfully so the sdk can fail it`() = runTest {
        val psp = object : StubPsp() {
            override suspend fun getPaymentStatus(txnRef: String) = PspPaymentStatus.Success(txnRef, "p", "u", amountPaise = 1, payeeVpa = "payflow.store@okbank")
        }
        val result = gateway(psp).charge(charge) as GatewayResult.Succeeded
        assertThat(result.amountPaise).isEqualTo(1)
    }

    @Test
    fun `unknown transaction on status is a non transient failure`() = runTest {
        val g = gateway(FakePsp(fixedClock))
        val failed = g.status(StatusQuery(key, null)) as GatewayResult.Failed
        assertThat(failed.error).isEqualTo(CheckoutError.GatewayError("NOT_FOUND", isTransient = false))
    }
}
