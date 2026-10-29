package com.darshan.payflow.core

import com.darshan.payflow.data.psp.PspException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class BackoffPolicyTest {
    private val policy = BackoffPolicy(initialDelayMillis = 500, maxDelayMillis = 8_000, multiplier = 2.0, jitterFactor = 0.0, maxAttempts = 5)

    @Test
    fun `delays grow exponentially and are capped`() {
        val r = Random(1)
        assertThat(policy.delayMillis(attempt = 1, random = r)).isEqualTo(500)
        assertThat(policy.delayMillis(attempt = 2, random = r)).isEqualTo(1_000)
        assertThat(policy.delayMillis(attempt = 3, random = r)).isEqualTo(2_000)
        assertThat(policy.delayMillis(attempt = 4, random = r)).isEqualTo(4_000)
        assertThat(policy.delayMillis(attempt = 5, random = r)).isEqualTo(8_000)
        assertThat(policy.delayMillis(attempt = 9, random = r)).isEqualTo(8_000)
        assertThat(policy.delayMillis(attempt = 60, random = r)).isEqualTo(8_000) // no Long overflow
    }

    @Test
    fun `full jitter keeps every delay within zero and the exponential value`() {
        val jittered = policy.copy(jitterFactor = 1.0)
        val r = Random(42)
        val samples = (1..1_000).map { jittered.delayMillis(attempt = 3, random = r) }
        assertThat(samples.min()).isAtLeast(0L)
        assertThat(samples.max()).isAtMost(2_000L)
        assertThat(samples.toSet().size).isGreaterThan(100) // actually spread out
        val half = policy.copy(jitterFactor = 0.5)
        val halfSamples = (1..1_000).map { half.delayMillis(attempt = 3, random = r) }
        assertThat(halfSamples.min()).isAtLeast(1_000L)
        assertThat(halfSamples.max()).isAtMost(2_000L)
    }

    @Test
    fun `retry-after from the server wins but is capped`() {
        val r = Random(1)
        assertThat(policy.delayMillis(attempt = 1, retryAfterMillis = 3_000, random = r)).isEqualTo(3_000)
        assertThat(policy.delayMillis(attempt = 1, retryAfterMillis = 999_999, random = r)).isEqualTo(8_000)
        assertThat(policy.delayMillis(attempt = 1, retryAfterMillis = -5, random = r)).isEqualTo(500)
    }

    @Test
    fun `invalid policies are rejected`() {
        assertThat(runCatching { policy.copy(initialDelayMillis = 0) }.isFailure).isTrue()
        assertThat(runCatching { policy.copy(maxDelayMillis = 100) }.isFailure).isTrue()
        assertThat(runCatching { policy.copy(multiplier = 0.5) }.isFailure).isTrue()
        assertThat(runCatching { policy.copy(jitterFactor = 1.5) }.isFailure).isTrue()
        assertThat(runCatching { policy.copy(maxAttempts = 0) }.isFailure).isTrue()
    }

    @Test
    fun `executor retries retryable failures with the policy delays and gives up after maxAttempts`() = runTest {
        var calls = 0
        val executor = RetryExecutor(policy, Random(1))
        val result = async {
            executor.execute { calls++; throw PspException.Timeout() }
        }
        runCurrent()
        assertThat(calls).isEqualTo(1)
        advanceTimeBy(500); runCurrent()
        assertThat(calls).isEqualTo(2)
        advanceTimeBy(1_000); runCurrent()
        assertThat(calls).isEqualTo(3)
        advanceTimeBy(2_000); runCurrent()
        assertThat(calls).isEqualTo(4)
        advanceTimeBy(4_000); runCurrent()
        assertThat(calls).isEqualTo(5)
        val outcome = result.await()
        assertThat(outcome.isFailure).isTrue()
        assertThat(outcome.exceptionOrNull()).isInstanceOf(PspException.Timeout::class.java)
        assertThat(testScheduler.currentTime).isEqualTo(7_500)
    }

    @Test
    fun `executor does not retry non retryable failures and returns the first success`() = runTest {
        val executor = RetryExecutor(policy, Random(1))
        var calls = 0
        val unauthorized = executor.execute { calls++; throw PspException.Unauthorized() }
        assertThat(unauthorized.exceptionOrNull()).isInstanceOf(PspException.Unauthorized::class.java)
        assertThat(calls).isEqualTo(1)
        calls = 0
        val ok = executor.execute { if (++calls < 3) throw PspException.Server(503) else "done" }
        assertThat(ok.getOrNull()).isEqualTo("done")
        assertThat(calls).isEqualTo(3)
        assertThat(testScheduler.currentTime).isEqualTo(1_500)
    }

    @Test
    fun `executor honours retry-after and is cancellation safe`() = runTest {
        val executor = RetryExecutor(policy, Random(1))
        var calls = 0
        val job = async { executor.execute { calls++; throw PspException.RateLimited(retryAfterMillis = 3_000) } }
        runCurrent()
        assertThat(calls).isEqualTo(1)
        advanceTimeBy(2_999); runCurrent()
        assertThat(calls).isEqualTo(1)
        advanceTimeBy(1); runCurrent()
        assertThat(calls).isEqualTo(2)
        job.cancel()
        advanceTimeBy(100_000); runCurrent()
        assertThat(calls).isEqualTo(2) // no attempts after cancellation
        assertThat(runCatching { job.await() }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    }
}
