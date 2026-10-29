package com.darshan.payflow.core

import com.darshan.payflow.data.psp.PspException
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * Exponential backoff with (full) jitter. `jitterFactor = 1.0` spreads each delay over `[0, exp]`, `0.5` over `[exp/2, exp]`,
 * `0.0` is deterministic. A server `Retry-After` wins over the computed delay but is capped at [maxDelayMillis].
 */
data class BackoffPolicy(
    val initialDelayMillis: Long,
    val maxDelayMillis: Long,
    val multiplier: Double,
    val jitterFactor: Double,
    val maxAttempts: Int,
) {
    init {
        require(initialDelayMillis > 0) { "initialDelayMillis must be positive" }
        require(maxDelayMillis >= initialDelayMillis) { "maxDelayMillis must be >= initialDelayMillis" }
        require(multiplier >= 1.0) { "multiplier must be >= 1" }
        require(jitterFactor in 0.0..1.0) { "jitterFactor must be within 0..1" }
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
    }

    /** Delay to wait after [attempt] (1-based) failed. */
    fun delayMillis(attempt: Int, retryAfterMillis: Long? = null, random: Random): Long {
        if (retryAfterMillis != null && retryAfterMillis > 0) return min(retryAfterMillis, maxDelayMillis)
        val exp = min(initialDelayMillis.toDouble() * multiplier.pow((attempt - 1).coerceAtLeast(0)), maxDelayMillis.toDouble())
        val jitterRange = exp * jitterFactor
        return (exp - jitterRange + random.nextDouble() * jitterRange).toLong().coerceIn(0, maxDelayMillis)
    }

    companion object {
        /** Production default: 1 s → 30 s, full jitter, 8 attempts (~2 minutes worst case). */
        val DEFAULT = BackoffPolicy(initialDelayMillis = 1_000, maxDelayMillis = 30_000, multiplier = 2.0, jitterFactor = 1.0, maxAttempts = 8)

        /** Registration (initiate) retries: shorter, the user is waiting on the pay screen. */
        val INITIATE = BackoffPolicy(initialDelayMillis = 500, maxDelayMillis = 4_000, multiplier = 2.0, jitterFactor = 1.0, maxAttempts = 4)

        /** No retries at all (single attempt); the default for tests that script PSP failures. */
        val NONE = BackoffPolicy(initialDelayMillis = 1, maxDelayMillis = 1, multiplier = 1.0, jitterFactor = 0.0, maxAttempts = 1)
    }
}

/** Runs a PSP call with retries for retryable [PspException]s. Cancellation is never swallowed. */
class RetryExecutor(private val policy: BackoffPolicy, private val random: Random = Random.Default) {
    suspend fun <T> execute(block: suspend () -> T): Result<T> {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return Result.success(block())
            } catch (e: PspException) {
                if (!e.retryable || attempt >= policy.maxAttempts) return Result.failure(e)
                delay(policy.delayMillis(attempt, (e as? PspException.RateLimited)?.retryAfterMillis, random))
            }
        }
    }
}
