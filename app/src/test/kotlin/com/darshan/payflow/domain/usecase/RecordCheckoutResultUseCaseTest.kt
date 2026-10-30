package com.darshan.payflow.domain.usecase

import com.darshan.checkoutkit.core.CheckoutResult
import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.testutil.FakePaymentRepository
import com.darshan.payflow.testutil.fixedClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RecordCheckoutResultUseCaseTest {
    private val repo = FakePaymentRepository()
    private var n = 0
    private val order = CheckoutOrder(orderId = "order-42", amountPaise = 49_900, description = "Coffee beans", merchantVpa = "payflow.store@okbank", merchantName = "PayFlow Store")
    private fun useCase(txnRef: String? = "CKREF") = RecordCheckoutResultUseCase(repo, fixedClock, { "ck-${++n}" }) { txnRef }

    @Test
    fun `success and pending are stored as checkout payments keyed by the sdk transaction id`() = runTest {
        val success = useCase().invoke(order, CheckoutResult.Success("CKREF", 49_900))
        assertThat(success!!.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(success.method).isEqualTo(PaymentMethod.CHECKOUT)
        assertThat(success.txnRef).isEqualTo("CKREF")
        assertThat(success.pspTxnId).isEqualTo("CKREF")
        assertThat(success.payeeVpa).isEqualTo("payflow.store@okbank")
        val pending = useCase().invoke(order.copy(orderId = "order-43"), CheckoutResult.Pending("CKREF2"))
        assertThat(pending!!.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(pending.txnRef).isEqualTo("CKREF2")
    }

    @Test
    fun `failed is stored with the typed error code, cancelled stores nothing`() = runTest {
        val failed = useCase().invoke(order, CheckoutResult.Failed(CheckoutError.InsufficientFunds))
        assertThat(failed!!.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(failed.failureReason).contains(CheckoutError.InsufficientFunds.code)
        assertThat(useCase().invoke(order.copy(orderId = "o2"), CheckoutResult.Cancelled)).isNull()
        assertThat(repo.observeRecent(10).first()).hasSize(1)
    }

    @Test
    fun `unresolved is stored pending under the gateway txnRef so the reconciler can settle it`() = runTest {
        val unresolved = useCase(txnRef = "CKUNRES").invoke(order, CheckoutResult.Unresolved(CheckoutError.Timeout))
        assertThat(unresolved!!.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(unresolved.txnRef).isEqualTo("CKUNRES")
        assertThat(unresolved.failureReason).isNull()
    }

    @Test
    fun `unresolved without a known txnRef still records a failed attempt rather than losing it`() = runTest {
        val unresolved = useCase(txnRef = null).invoke(order, CheckoutResult.Unresolved(CheckoutError.Timeout))
        assertThat(unresolved!!.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(unresolved.failureReason).contains("UNRESOLVED")
    }

    @Test
    fun `recording the same order twice is idempotent`() = runTest {
        val first = useCase().invoke(order, CheckoutResult.Success("CKREF", 49_900))
        val second = useCase().invoke(order, CheckoutResult.Success("CKREF", 49_900))
        assertThat(second!!.id).isEqualTo(first!!.id)
        assertThat(repo.observeRecent(10).first()).hasSize(1)
    }

    @Test
    fun `refunded and rejected results are recorded honestly`() = runTest {
        val refunded = useCase().invoke(order, CheckoutResult.Refunded("CKREF", "rf-1"))
        assertThat(refunded!!.status).isEqualTo(PaymentStatus.REFUNDED)
        assertThat(refunded.refundId).isEqualTo("rf-1")
        // Rejected happens before any gateway call, so there is no gateway reference for the order.
        val rejected = useCase(txnRef = null).invoke(order.copy(orderId = "o3"), CheckoutResult.Rejected(CheckoutError.InvalidRequest("amount")))
        assertThat(rejected!!.status).isEqualTo(PaymentStatus.FAILED)
    }
}
