package com.darshan.payflow.data.psp

import com.darshan.payflow.testutil.fixedClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakePspTest {
    private val request = PspInitiateRequest("PF1", 12_300, "alice@okbank", "Alice", "Lunch", idempotencyKey = null)

    @Test
    fun `initiate then status goes pending for the configured polls then success`() = runTest {
        val psp = FakePsp(fixedClock, FakePspConfig(pendingPolls = 2))
        val init = psp.initiatePayment(request)
        assertThat(init.txnRef).isEqualTo("PF1")
        assertThat(init.pspTxnId).isNotEmpty()
        assertThat(psp.getPaymentStatus("PF1")).isEqualTo(PspPaymentStatus.Pending("PF1"))
        assertThat(psp.getPaymentStatus("PF1")).isEqualTo(PspPaymentStatus.Pending("PF1"))
        val success = psp.getPaymentStatus("PF1") as PspPaymentStatus.Success
        assertThat(success.amountPaise).isEqualTo(12_300)
        assertThat(success.payeeVpa).isEqualTo("alice@okbank")
        assertThat(success.pspTxnId).isEqualTo(init.pspTxnId)
        assertThat(success.upiTxnId).isNotEmpty()
    }

    @Test
    fun `failed outcome and unknown ref`() = runTest {
        val psp = FakePsp(fixedClock, FakePspConfig(outcome = FakeOutcome.FAILED))
        psp.initiatePayment(request)
        val failed = psp.getPaymentStatus("PF1") as PspPaymentStatus.Failed
        assertThat(failed.code).isNotEmpty()
        assertThat(psp.getPaymentStatus("nope")).isEqualTo(PspPaymentStatus.NotFound("nope"))
    }

    @Test
    fun `latency is simulated with delay so virtual time works`() = runTest {
        val psp = FakePsp(fixedClock, FakePspConfig(latencyMillis = 1_500))
        val before = testScheduler.currentTime
        psp.initiatePayment(request)
        assertThat(testScheduler.currentTime - before).isEqualTo(1_500)
    }

    @Test
    fun `initiate is idempotent per txnRef and rejects a different amount for the same ref`() = runTest {
        val psp = FakePsp(fixedClock)
        val a = psp.initiatePayment(request)
        val b = psp.initiatePayment(request)
        assertThat(b).isEqualTo(a)
        val ex = runCatching { psp.initiatePayment(request.copy(amountPaise = 1)) }.exceptionOrNull()
        assertThat(ex).isInstanceOf(PspException.ClientError::class.java)
        assertThat((ex as PspException.ClientError).status).isEqualTo(409)
    }

    @Test
    fun `settle lets a test act as the payer`() = runTest {
        val psp = FakePsp(fixedClock, FakePspConfig(outcome = FakeOutcome.PENDING_UNTIL_SETTLED))
        psp.initiatePayment(request)
        assertThat(psp.getPaymentStatus("PF1")).isInstanceOf(PspPaymentStatus.Pending::class.java)
        psp.settle("PF1", approved = false)
        assertThat(psp.getPaymentStatus("PF1")).isInstanceOf(PspPaymentStatus.Failed::class.java)
    }

    @Test
    fun `transient failures are injected by rate and are deterministic by seed`() = runTest {
        val psp = FakePsp(fixedClock, FakePspConfig(timeoutRate = 0.3, serverErrorRate = 0.2, rateLimitRate = 0.2), seed = 7)
        var initiated = false
        while (!initiated) initiated = runCatching { psp.initiatePayment(request) }.isSuccess // initiate is flaky too
        val kinds = (1..200).map { runCatching { psp.getPaymentStatus("PF1") }.exceptionOrNull()?.javaClass?.simpleName ?: "ok" }.groupingBy { it }.eachCount()
        assertThat(kinds.keys).containsAtLeast("Timeout", "Server", "RateLimited", "ok")
        assertThat(kinds["ok"]!!).isGreaterThan(30)
        assertThat(psp.statusCalls).isEqualTo(200)
        // deterministic for the same seed
        val psp2 = FakePsp(fixedClock, FakePspConfig(timeoutRate = 0.3, serverErrorRate = 0.2, rateLimitRate = 0.2), seed = 7)
        initiated = false
        while (!initiated) initiated = runCatching { psp2.initiatePayment(request) }.isSuccess
        val kinds2 = (1..200).map { runCatching { psp2.getPaymentStatus("PF1") }.exceptionOrNull()?.javaClass?.simpleName ?: "ok" }.groupingBy { it }.eachCount()
        assertThat(kinds2).isEqualTo(kinds)
    }

    @Test
    fun `mandate lifecycle in the sandbox`() = runTest {
        val psp = FakePsp(fixedClock)
        val created = psp.createMandate(PspMandateRequest("netflix@okbank", "Netflix", 64_900, "MONTHLY", "2026-10-29", "2027-10-29", ""))
        assertThat(created.status).isEqualTo(com.darshan.payflow.domain.model.MandateStatus.ACTIVE)
        assertThat(psp.updateMandate(created.umn, "PAUSE").status).isEqualTo(com.darshan.payflow.domain.model.MandateStatus.PAUSED)
        val paused = runCatching { psp.executeMandate(created.umn, "MND1", 100) }.exceptionOrNull() as PspException.ClientError
        assertThat(paused.status).isEqualTo(409)
        psp.updateMandate(created.umn, "RESUME")
        val tooMuch = runCatching { psp.executeMandate(created.umn, "MND1", 64_901) }.exceptionOrNull() as PspException.ClientError
        assertThat(tooMuch.status).isEqualTo(422)
        val ok = psp.executeMandate(created.umn, "MND1", 64_900)
        assertThat(psp.getPaymentStatus("MND1")).isInstanceOf(PspPaymentStatus.Success::class.java)
        assertThat(ok.txnRef).isEqualTo("MND1")
        assertThat(runCatching { psp.updateMandate("nope", "PAUSE") }.exceptionOrNull()).isInstanceOf(PspException.ClientError::class.java)
        assertThat(runCatching { psp.updateMandate(created.umn, "DANCE") }.exceptionOrNull()).isInstanceOf(PspException.ClientError::class.java)
    }

    @Test
    fun `refund in the sandbox is idempotent by key and only for captured orders`() = runTest {
        val psp = FakePsp(fixedClock)
        psp.initiatePayment(request)
        val notCaptured = runCatching { psp.refund("PF1", 12_300, "rf-key") }.exceptionOrNull() as PspException.ClientError
        assertThat(notCaptured.status).isEqualTo(409)
        psp.getPaymentStatus("PF1") // settles as success
        val refund = psp.refund("PF1", 12_300, "rf-key")
        assertThat(refund.amountPaise).isEqualTo(12_300)
        assertThat(psp.refund("PF1", 12_300, "rf-key")).isEqualTo(refund)
        assertThat(psp.getPaymentStatus("PF1")).isInstanceOf(PspPaymentStatus.Refunded::class.java)
        assertThat((runCatching { psp.refund("PF1", 1, "other") }.exceptionOrNull() as PspException.ClientError).status).isEqualTo(409)
        assertThat((runCatching { psp.refund("nope", 1, "x") }.exceptionOrNull() as PspException.ClientError).status).isEqualTo(404)
    }
}
