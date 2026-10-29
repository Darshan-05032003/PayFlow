package com.darshan.payflow.domain.usecase

import com.darshan.payflow.core.BackoffPolicy
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.psp.FakePspConfig
import com.darshan.payflow.data.psp.PspException
import com.darshan.payflow.data.psp.PspPaymentStatus
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.UpiPayRequest
import com.darshan.payflow.testutil.FakePaymentRepository
import com.darshan.payflow.testutil.StubPsp
import com.darshan.payflow.testutil.fixedClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class PollPaymentStatusUseCaseTest {
    private val repo = FakePaymentRepository()
    private val request = UpiPayRequest("alice@okbank", "Alice", 12_300, "Lunch", "PFPOLL1")
    private val policy = BackoffPolicy(initialDelayMillis = 1_000, maxDelayMillis = 8_000, multiplier = 2.0, jitterFactor = 0.0, maxAttempts = 5)

    private suspend fun pending(psp: com.darshan.payflow.data.psp.PspClient): String =
        PayViaUpiIntentUseCase(repo, psp, fixedClock) { "pay-1" }.invoke(request, PaymentMethod.UPI_INTENT).getOrThrow().paymentId

    private fun poller(psp: com.darshan.payflow.data.psp.PspClient) = PollPaymentStatusUseCase(ConfirmPaymentUseCase(repo, psp, fixedClock), policy, Random(1))

    @Test
    fun `polls with exponential backoff until the psp settles`() = runTest {
        val psp = FakePsp(fixedClock, FakePspConfig(pendingPolls = 2))
        val id = pending(psp)
        val result = async { poller(psp).invoke(id) }
        runCurrent()
        assertThat(psp.statusCalls).isEqualTo(1) // pending
        advanceTimeBy(1_000); runCurrent()
        assertThat(psp.statusCalls).isEqualTo(2) // pending
        advanceTimeBy(2_000); runCurrent()
        assertThat(psp.statusCalls).isEqualTo(3) // success
        assertThat(result.await()).isInstanceOf(ConfirmResult.Success::class.java)
        assertThat(repo.getById(id)!!.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(testScheduler.currentTime).isEqualTo(3_000)
    }

    @Test
    fun `transient psp failures are retried, a permanent one stops polling as unresolved`() = runTest {
        val flaky = object : StubPsp() {
            val calls = AtomicInteger()
            override suspend fun getPaymentStatus(txnRef: String): PspPaymentStatus = when (calls.incrementAndGet()) {
                1 -> throw PspException.Timeout()
                2 -> throw PspException.Server(503)
                else -> PspPaymentStatus.Success(txnRef, "p", "u", 12_300, "alice@okbank")
            }
        }
        val id = pending(flaky)
        val result = poller(flaky).invoke(id)
        assertThat(result).isInstanceOf(ConfirmResult.Success::class.java)
        assertThat(flaky.calls.get()).isEqualTo(3)

        val unauthorized = object : StubPsp() {
            val calls = AtomicInteger()
            override suspend fun getPaymentStatus(txnRef: String): PspPaymentStatus { calls.incrementAndGet(); throw PspException.Unauthorized() }
        }
        val repo2 = FakePaymentRepository()
        val id2 = PayViaUpiIntentUseCase(repo2, unauthorized, fixedClock) { "pay-2" }.invoke(request, PaymentMethod.UPI_INTENT).getOrThrow().paymentId
        val r2 = PollPaymentStatusUseCase(ConfirmPaymentUseCase(repo2, unauthorized, fixedClock), policy, Random(1)).invoke(id2)
        assertThat(r2).isInstanceOf(ConfirmResult.Unresolved::class.java)
        assertThat(unauthorized.calls.get()).isEqualTo(1)
    }

    @Test
    fun `gives up after maxAttempts and reports pending, never failed`() = runTest {
        val psp = FakePsp(fixedClock, FakePspConfig(pendingPolls = 100))
        val id = pending(psp)
        val result = poller(psp).invoke(id)
        assertThat(result).isInstanceOf(ConfirmResult.Pending::class.java)
        assertThat(psp.statusCalls).isEqualTo(5)
        assertThat(repo.getById(id)!!.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(testScheduler.currentTime).isEqualTo(1_000 + 2_000 + 4_000 + 8_000)
    }

    @Test
    fun `rate limited responses wait for retry-after`() = runTest {
        val limited = object : StubPsp() {
            val calls = AtomicInteger()
            override suspend fun getPaymentStatus(txnRef: String): PspPaymentStatus =
                if (calls.incrementAndGet() == 1) throw PspException.RateLimited(5_000) else PspPaymentStatus.Success(txnRef, "p", "u", 12_300, "alice@okbank")
        }
        val id = pending(limited)
        val result = async { poller(limited).invoke(id) }
        runCurrent(); advanceTimeBy(4_999); runCurrent()
        assertThat(limited.calls.get()).isEqualTo(1)
        advanceTimeBy(1); runCurrent()
        assertThat(result.await()).isInstanceOf(ConfirmResult.Success::class.java)
    }

    @Test
    fun `concurrent polls for the same payment share one poller (single flight)`() = runTest {
        val psp = FakePsp(fixedClock, FakePspConfig(pendingPolls = 1))
        val id = pending(psp)
        val poller = poller(psp)
        val results = (1..50).map { async { poller.invoke(id) } }
        advanceUntilIdle()
        assertThat(results.map { it.await() }.all { it is ConfirmResult.Success }).isTrue()
        assertThat(psp.statusCalls).isEqualTo(2) // one pending + one success, not 100
    }

    @Test
    fun `terminal payments return immediately without a psp call`() = runTest {
        val psp = FakePsp(fixedClock)
        val id = pending(psp)
        poller(psp).invoke(id)
        val before = psp.statusCalls
        assertThat(poller(psp).invoke(id)).isInstanceOf(ConfirmResult.Success::class.java)
        assertThat(psp.statusCalls).isEqualTo(before)
        assertThat(poller(psp).invoke("ghost")).isEqualTo(ConfirmResult.NotFound)
    }
}
