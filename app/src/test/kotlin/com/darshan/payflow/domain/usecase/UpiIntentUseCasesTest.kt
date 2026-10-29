package com.darshan.payflow.domain.usecase

import com.darshan.payflow.data.psp.FakeOutcome
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.psp.FakePspConfig
import com.darshan.payflow.data.psp.PspException
import com.darshan.payflow.data.psp.PspInitiateRequest
import com.darshan.payflow.data.psp.PspInitiateResponse
import com.darshan.payflow.data.psp.PspPaymentStatus
import com.darshan.payflow.data.psp.PspClient
import com.darshan.payflow.data.upi.UpiAppStatus
import com.darshan.payflow.data.upi.UpiResponseParser
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.UpiPayRequest
import com.darshan.payflow.testutil.FakePaymentRepository
import com.darshan.payflow.testutil.fixedClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UpiIntentUseCasesTest {
    private val repo = FakePaymentRepository()
    private val request = UpiPayRequest("alice@okbank", "Alice", 12_300, "Lunch", "PFTEST1")

    private fun pay(psp: PspClient) = PayViaUpiIntentUseCase(repo, psp, fixedClock) { "pay-1" }
    private fun confirm(psp: PspClient) = ConfirmPaymentUseCase(repo, psp, fixedClock)

    @Test
    fun `pay creates a pending payment registered with the psp and returns the upi link`() = runTest {
        val psp = FakePsp(fixedClock)
        val launch = pay(psp).invoke(request, PaymentMethod.UPI_INTENT).getOrThrow()
        assertThat(launch.paymentId).isEqualTo("pay-1")
        assertThat(launch.upiUri).startsWith("upi://pay?pa=alice%40okbank&pn=Alice&am=123.00&cu=INR&tn=Lunch&tr=PFTEST1")
        val stored = repo.getById("pay-1")!!
        assertThat(stored.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(stored.txnRef).isEqualTo("PFTEST1")
        assertThat(stored.amountPaise).isEqualTo(12_300)
        assertThat(psp.initiateCalls).isEqualTo(1)
        assertThat(psp.orders["PFTEST1"]!!.amountPaise).isEqualTo(12_300)
    }

    @Test
    fun `psp initiate failure marks the payment failed and never launches the app`() = runTest {
        val psp = object : PspClient {
            override suspend fun initiatePayment(request: PspInitiateRequest) = throw PspException.Server(503)
            override suspend fun getPaymentStatus(txnRef: String) = error("not called")
        }
        val result = pay(psp).invoke(request, PaymentMethod.UPI_INTENT)
        assertThat(result.exceptionOrNull()).isInstanceOf(PspException.Server::class.java)
        assertThat(repo.getById("pay-1")!!.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(repo.getById("pay-1")!!.failureReason).contains("503")
    }

    @Test
    fun `app says success and psp confirms a matching payment - success`() = runTest {
        val psp = FakePsp(fixedClock)
        pay(psp).invoke(request, PaymentMethod.UPI_INTENT).getOrThrow()
        val app = UpiResponseParser.parse("txnId=APP1&responseCode=00&Status=SUCCESS&txnRef=PFTEST1")
        val result = confirm(psp).invoke("pay-1", app)
        assertThat(result).isInstanceOf(ConfirmResult.Success::class.java)
        val stored = repo.getById("pay-1")!!
        assertThat(stored.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(stored.pspTxnId).isNotNull()
        assertThat(stored.upiTxnId).isNotNull()
    }

    @Test
    fun `app says success but the psp says failed - failed, the app response is never trusted alone`() = runTest {
        val psp = FakePsp(fixedClock, FakePspConfig(outcome = FakeOutcome.FAILED))
        pay(psp).invoke(request, PaymentMethod.UPI_INTENT).getOrThrow()
        val spoofed = UpiResponseParser.parse("txnId=FAKE&responseCode=00&Status=SUCCESS&txnRef=PFTEST1&ApprovalRefNo=123")
        val result = confirm(psp).invoke("pay-1", spoofed)
        assertThat(result).isInstanceOf(ConfirmResult.Failed::class.java)
        assertThat(repo.getById("pay-1")!!.status).isEqualTo(PaymentStatus.FAILED)
    }

    @Test
    fun `app says success but the psp is still pending - stays pending`() = runTest {
        val psp = FakePsp(fixedClock, FakePspConfig(pendingPolls = 5))
        pay(psp).invoke(request, PaymentMethod.UPI_INTENT).getOrThrow()
        val result = confirm(psp).invoke("pay-1", UpiResponseParser.parse("Status=SUCCESS&txnRef=PFTEST1"))
        assertThat(result).isInstanceOf(ConfirmResult.Pending::class.java)
        assertThat(repo.getById("pay-1")!!.status).isEqualTo(PaymentStatus.PENDING)
    }

    @Test
    fun `app cancelled or garbage but the psp says success - success, money moved`() = runTest {
        val psp = FakePsp(fixedClock)
        pay(psp).invoke(request, PaymentMethod.UPI_INTENT).getOrThrow()
        val result = confirm(psp).invoke("pay-1", null)
        assertThat(result).isInstanceOf(ConfirmResult.Success::class.java)
        assertThat(repo.getById("pay-1")!!.status).isEqualTo(PaymentStatus.SUCCESS)
    }

    @Test
    fun `psp success for a different amount, payee or ref is a mismatch and never confirms`() = runTest {
        val cases = listOf(
            PspPaymentStatus.Success("PFTEST1", "p", "u", 1, "alice@okbank"),
            PspPaymentStatus.Success("PFTEST1", "p", "u", 12_300, "mallory@evil"),
            PspPaymentStatus.Success("OTHER", "p", "u", 12_300, "alice@okbank"),
        )
        for (status in cases) {
            val localRepo = FakePaymentRepository()
            val psp = object : PspClient {
                override suspend fun initiatePayment(request: PspInitiateRequest) = PspInitiateResponse("p", request.txnRef)
                override suspend fun getPaymentStatus(txnRef: String) = status
            }
            PayViaUpiIntentUseCase(localRepo, psp, fixedClock) { "pay-1" }.invoke(request, PaymentMethod.UPI_INTENT).getOrThrow()
            val result = ConfirmPaymentUseCase(localRepo, psp, fixedClock).invoke("pay-1", UpiResponseParser.parse("Status=SUCCESS&txnRef=PFTEST1"))
            assertThat(result).isInstanceOf(ConfirmResult.Mismatch::class.java)
            assertThat(localRepo.getById("pay-1")!!.status).isEqualTo(PaymentStatus.PENDING)
        }
    }

    @Test
    fun `psp unreachable leaves the payment pending and reports unresolved`() = runTest {
        val psp = object : PspClient {
            override suspend fun initiatePayment(request: PspInitiateRequest) = PspInitiateResponse("p", request.txnRef)
            override suspend fun getPaymentStatus(txnRef: String) = throw PspException.Timeout()
        }
        pay(psp).invoke(request, PaymentMethod.UPI_INTENT).getOrThrow()
        val result = confirm(psp).invoke("pay-1", UpiResponseParser.parse("Status=SUCCESS&txnRef=PFTEST1"))
        assertThat(result).isInstanceOf(ConfirmResult.Unresolved::class.java)
        assertThat(repo.getById("pay-1")!!.status).isEqualTo(PaymentStatus.PENDING)
    }

    @Test
    fun `confirming twice is idempotent and confirming an unknown payment is not found`() = runTest {
        val psp = FakePsp(fixedClock)
        pay(psp).invoke(request, PaymentMethod.UPI_INTENT).getOrThrow()
        val app = UpiResponseParser.parse("Status=SUCCESS&txnRef=PFTEST1")
        assertThat(confirm(psp).invoke("pay-1", app)).isInstanceOf(ConfirmResult.Success::class.java)
        assertThat(confirm(psp).invoke("pay-1", app)).isInstanceOf(ConfirmResult.Success::class.java)
        assertThat(repo.getById("pay-1")!!.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(confirm(psp).invoke("ghost", app)).isInstanceOf(ConfirmResult.NotFound::class.java)
    }

    @Test
    fun `psp not found for our ref fails the payment`() = runTest {
        val psp = object : PspClient {
            override suspend fun initiatePayment(request: PspInitiateRequest) = PspInitiateResponse("p", request.txnRef)
            override suspend fun getPaymentStatus(txnRef: String) = PspPaymentStatus.NotFound(txnRef)
        }
        pay(psp).invoke(request, PaymentMethod.UPI_INTENT).getOrThrow()
        assertThat(confirm(psp).invoke("pay-1", null)).isInstanceOf(ConfirmResult.Failed::class.java)
    }

    @Test
    fun `app failure status is recorded as the reason when the psp also fails`() = runTest {
        val psp = FakePsp(fixedClock, FakePspConfig(outcome = FakeOutcome.FAILED))
        pay(psp).invoke(request, PaymentMethod.UPI_INTENT).getOrThrow()
        val app = UpiResponseParser.parse("Status=FAILURE&responseCode=ZA&txnRef=PFTEST1")
        assertThat(app.status).isEqualTo(UpiAppStatus.FAILURE)
        confirm(psp).invoke("pay-1", app)
        assertThat(repo.getById("pay-1")!!.failureReason).isNotEmpty()
    }
}
