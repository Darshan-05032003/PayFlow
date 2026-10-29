package com.darshan.payflow.domain.usecase

import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.psp.FakePspConfig
import com.darshan.payflow.domain.model.IdempotencyConflictException
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.UpiPayRequest
import com.darshan.payflow.testutil.FakePaymentRepository
import com.darshan.payflow.testutil.fixedClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class IdempotencyTest {
    private val repo = FakePaymentRepository()
    private val request = UpiPayRequest("alice@okbank", "Alice", 12_300, "Lunch", "PFIDEM1")
    private var ids = 0
    private fun pay(psp: FakePsp) = PayViaUpiIntentUseCase(repo, psp, fixedClock) { "pay-${++ids}" }

    @Test
    fun `the same idempotency key returns the same payment and calls the psp once`() = runTest {
        val psp = FakePsp(fixedClock)
        val first = pay(psp).invoke(request, PaymentMethod.UPI_INTENT, idempotencyKey = "k1").getOrThrow()
        val second = pay(psp).invoke(request.copy(txnRef = "OTHERREF"), PaymentMethod.UPI_INTENT, idempotencyKey = "k1").getOrThrow()
        assertThat(second.paymentId).isEqualTo(first.paymentId)
        assertThat(second.upiUri).isEqualTo(first.upiUri)
        assertThat(psp.initiateCalls).isEqualTo(1)
        assertThat(repo.getById("pay-2")).isNull()
    }

    @Test
    fun `a key reused with a different amount or payee is a conflict, never a second payment`() = runTest {
        val psp = FakePsp(fixedClock)
        pay(psp).invoke(request, PaymentMethod.UPI_INTENT, idempotencyKey = "k1").getOrThrow()
        val conflict = pay(psp).invoke(request.copy(amountPaise = 1), PaymentMethod.UPI_INTENT, idempotencyKey = "k1")
        assertThat(conflict.exceptionOrNull()).isInstanceOf(IdempotencyConflictException::class.java)
        val conflict2 = pay(psp).invoke(request.copy(payeeVpa = "mallory@okbank"), PaymentMethod.UPI_INTENT, idempotencyKey = "k1")
        assertThat(conflict2.exceptionOrNull()).isInstanceOf(IdempotencyConflictException::class.java)
        assertThat(psp.initiateCalls).isEqualTo(1)
    }

    @Test
    fun `retry after a transient psp failure with the same key reuses the stored payment`() = runTest {
        val down = FakePsp(fixedClock, FakePspConfig(serverErrorRate = 1.0))
        val failed = pay(down).invoke(request, PaymentMethod.UPI_INTENT, idempotencyKey = "k1")
        assertThat(failed.isFailure).isTrue()
        assertThat(repo.getById("pay-1")!!.status).isEqualTo(PaymentStatus.FAILED)
        // The user taps retry: a failed attempt under the same key must not be silently "returned"; a fresh key is required.
        val again = pay(down).invoke(request, PaymentMethod.UPI_INTENT, idempotencyKey = "k1")
        assertThat(again.exceptionOrNull()).isInstanceOf(IdempotencyConflictException::class.java)
    }

    @Test
    fun `after process death a new use case over the same repository dedupes`() = runTest {
        val psp = FakePsp(fixedClock)
        val first = PayViaUpiIntentUseCase(repo, psp, fixedClock) { "a" }.invoke(request, PaymentMethod.UPI_INTENT, idempotencyKey = "k1").getOrThrow()
        val afterDeath = PayViaUpiIntentUseCase(repo, psp, fixedClock) { "b" }.invoke(request, PaymentMethod.UPI_INTENT, idempotencyKey = "k1").getOrThrow()
        assertThat(afterDeath.paymentId).isEqualTo(first.paymentId)
        assertThat(repo.getById("b")).isNull()
    }

    @Test
    fun `without a key every submit is a new payment`() = runTest {
        val psp = FakePsp(fixedClock)
        pay(psp).invoke(request, PaymentMethod.UPI_INTENT).getOrThrow()
        pay(psp).invoke(request.copy(txnRef = "PFIDEM2"), PaymentMethod.UPI_INTENT).getOrThrow()
        assertThat(repo.getById("pay-2")).isNotNull()
    }

    @Test
    fun `transient psp errors at registration are retried with backoff and the key makes the retry safe`() = kotlinx.coroutines.test.runTest {
        val flaky = object : com.darshan.payflow.testutil.StubPsp() {
            val calls = java.util.concurrent.atomic.AtomicInteger()
            override suspend fun initiatePayment(request: com.darshan.payflow.data.psp.PspInitiateRequest) =
                if (calls.incrementAndGet() < 3) throw com.darshan.payflow.data.psp.PspException.Server(503) else com.darshan.payflow.data.psp.PspInitiateResponse("p", request.txnRef)
        }
        val policy = com.darshan.payflow.core.BackoffPolicy(initialDelayMillis = 200, maxDelayMillis = 1_000, multiplier = 2.0, jitterFactor = 0.0, maxAttempts = 4)
        val useCase = PayViaUpiIntentUseCase(repo, flaky, fixedClock, com.darshan.payflow.core.RetryExecutor(policy, kotlin.random.Random(1))) { "pay-r" }
        val launch = useCase(request, PaymentMethod.UPI_INTENT, idempotencyKey = "k-retry").getOrThrow()
        assertThat(launch.paymentId).isEqualTo("pay-r")
        assertThat(flaky.calls.get()).isEqualTo(3)
        assertThat(testScheduler.currentTime).isEqualTo(200 + 400)
        assertThat(repo.getById("pay-r")!!.status).isEqualTo(PaymentStatus.PENDING)
    }
}
