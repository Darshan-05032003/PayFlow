package com.darshan.payflow.domain.usecase

import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.upi.UpiLaunchResult
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.StatusSource
import com.darshan.payflow.domain.model.UpiPayRequest
import com.darshan.payflow.testutil.FakePaymentRepository
import com.darshan.payflow.testutil.fixedClock
import com.darshan.payflow.testutil.testPayment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UpiAppUseCasesTest {
    private val repo = FakePaymentRepository()
    private val psp = FakePsp(fixedClock)
    private var ids = 0
    private val pay = PayViaUpiIntentUseCase(repo, psp, fixedClock) { "pay-${++ids}" }
    private val record = RecordUpiAppResultUseCase(repo)
    private val mark = MarkPaymentManuallyUseCase(repo)
    private val request = UpiPayRequest("alice@okbank", "Alice", 10_000, "Lunch", "PFTESTREF1")

    private suspend fun launched(pkg: String = "com.example.gpay"): String =
        pay(request.copy(txnRef = "PFTESTREF${ids + 1}"), PaymentMethod.UPI_INTENT, "key-${ids + 1}", PaymentMode.UPI_APPS, pkg).getOrThrow().paymentId

    // --- PayViaUpiIntentUseCase in UPI-apps mode ---

    @Test
    fun `upi apps mode stores the payment as pending without touching the psp and records the chosen app`() = runTest {
        val launch = pay(request, PaymentMethod.UPI_INTENT, "k1", PaymentMode.UPI_APPS, "com.example.gpay").getOrThrow()
        assertThat(launch.packageName).isEqualTo("com.example.gpay")
        assertThat(launch.upiUri).startsWith("upi://pay?pa=alice%40okbank&pn=Alice&am=100.00&cu=INR&tn=Lunch&tr=PFTESTREF1")
        val stored = repo.getById(launch.paymentId)!!
        assertThat(stored.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(stored.handlerPackage).isEqualTo("com.example.gpay")
        assertThat(stored.statusSource).isEqualTo(StatusSource.PSP) // nothing has decided the outcome yet
        assertThat(psp.initiateCalls).isEqualTo(0)
    }

    @Test
    fun `the same idempotency key returns the same pending payment and app`() = runTest {
        val first = pay(request, PaymentMethod.UPI_INTENT, "k1", PaymentMode.UPI_APPS, "com.example.gpay").getOrThrow()
        val again = pay(request, PaymentMethod.UPI_INTENT, "k1", PaymentMode.UPI_APPS, "com.example.phonepe").getOrThrow()
        assertThat(again.paymentId).isEqualTo(first.paymentId)
        assertThat(again.packageName).isEqualTo("com.example.gpay")
        assertThat(repo.getById("pay-2")).isNull()
    }

    @Test
    fun `sandbox mode ignores a handler package`() = runTest {
        val launch = pay(request, PaymentMethod.UPI_INTENT, "k1", PaymentMode.SANDBOX, "com.example.gpay").getOrThrow()
        assertThat(launch.packageName).isNull()
        assertThat(repo.getById(launch.paymentId)!!.handlerPackage).isNull()
        assertThat(psp.initiateCalls).isEqualTo(1)
    }

    // --- RecordUpiAppResultUseCase ---

    @Test
    fun `a success response for our reference is recorded as reported by the app`() = runTest {
        val id = launched()
        val result = record(id, UpiLaunchResult.Responded("txnId=UPI123&responseCode=00&Status=SUCCESS&txnRef=PFTESTREF1"), appLabel = "GPay")
        val payment = (result as UpiAppResult.ReportedSuccess).payment
        assertThat(payment.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(payment.statusSource).isEqualTo(StatusSource.UPI_APP)
        assertThat(payment.upiTxnId).isEqualTo("UPI123")
        assertThat(payment.pspTxnId).isNull() // no PSP ever saw this payment
        assertThat(psp.statusCalls).isEqualTo(0)
    }

    @Test
    fun `a failure response is recorded with the app and code`() = runTest {
        val id = launched()
        val result = record(id, UpiLaunchResult.Responded("Status=FAILURE&responseCode=U30&txnRef=pftestref1"), appLabel = "PhonePe")
        val payment = (result as UpiAppResult.ReportedFailure).payment
        assertThat(payment.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(payment.statusSource).isEqualTo(StatusSource.UPI_APP)
        assertThat(payment.failureReason).isEqualTo("Reported as failed by PhonePe (code U30)")
    }

    @Test
    fun `a response naming another reference is refused and the payment stays pending`() = runTest {
        val id = launched()
        val spoof = record(id, UpiLaunchResult.Responded("Status=SUCCESS&txnRef=SOMEONEELSE&ApprovalRefNo=1"))
        assertThat(spoof).isEqualTo(UpiAppResult.Mismatch(repo.getById(id)!!, "SOMEONEELSE"))
        assertThat(repo.getById(id)!!.status).isEqualTo(PaymentStatus.PENDING)
        // ... and so is a FAILURE for another reference (it must not fail *our* payment either).
        assertThat(record(id, UpiLaunchResult.Responded("Status=FAILURE&txnRef=OTHER"))).isInstanceOf(UpiAppResult.Mismatch::class.java)
        assertThat(repo.getById(id)!!.status).isEqualTo(PaymentStatus.PENDING)
    }

    @Test
    fun `cancelled, submitted, empty and garbage answers leave the payment pending for a manual check`() = runTest {
        val id = launched()
        listOf(
            UpiLaunchResult.Cancelled,
            UpiLaunchResult.Responded(null),
            UpiLaunchResult.Responded(""),
            UpiLaunchResult.Responded("Status=SUBMITTED&txnRef=PFTESTREF1"),
            UpiLaunchResult.Responded("\u0000‮&&&==garbage"),
            UpiLaunchResult.Responded("x".repeat(100_000)),
        ).forEach { answer ->
            val result = record(id, answer)
            assertThat(result).isInstanceOf(UpiAppResult.Unknown::class.java)
            assertThat(repo.getById(id)!!.status).isEqualTo(PaymentStatus.PENDING)
            assertThat(repo.getById(id)!!.canBeMarkedManually).isTrue()
        }
    }

    @Test
    fun `a second delivery of the result does not change a settled payment`() = runTest {
        val id = launched()
        record(id, UpiLaunchResult.Responded("Status=SUCCESS&txnRef=PFTESTREF1"))
        val again = record(id, UpiLaunchResult.Responded("Status=FAILURE&txnRef=PFTESTREF1"))
        assertThat(again).isInstanceOf(UpiAppResult.AlreadySettled::class.java)
        assertThat(repo.getById(id)!!.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(record("nope", UpiLaunchResult.Cancelled)).isEqualTo(UpiAppResult.NotFound)
    }

    // --- MarkPaymentManuallyUseCase ---

    @Test
    fun `the user can mark a pending upi app payment as paid or not paid`() = runTest {
        val paidId = launched()
        val paid = (mark(paidId, paid = true) as ManualMarkResult.Marked).payment
        assertThat(paid.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(paid.statusSource).isEqualTo(StatusSource.USER)

        val failedId = launched("com.example.phonepe")
        val failed = (mark(failedId, paid = false) as ManualMarkResult.Marked).payment
        assertThat(failed.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(failed.statusSource).isEqualTo(StatusSource.USER)
        assertThat(failed.failureReason).isEqualTo(MarkPaymentManuallyUseCase.REASON_NOT_PAID)
    }

    @Test
    fun `sandbox payments and settled payments cannot be marked by hand`() = runTest {
        repo.seed(testPayment("sandbox", status = PaymentStatus.PENDING)) // no handler package: PSP-confirmed path
        assertThat(mark("sandbox", paid = true)).isInstanceOf(ManualMarkResult.NotAllowed::class.java)
        assertThat(repo.getById("sandbox")!!.status).isEqualTo(PaymentStatus.PENDING)

        val id = launched()
        mark(id, paid = true)
        assertThat(mark(id, paid = false)).isInstanceOf(ManualMarkResult.NotAllowed::class.java)
        assertThat(repo.getById(id)!!.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(mark("missing", paid = true)).isEqualTo(ManualMarkResult.NotFound)
    }
}
