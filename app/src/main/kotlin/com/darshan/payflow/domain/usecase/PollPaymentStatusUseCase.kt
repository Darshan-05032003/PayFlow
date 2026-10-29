package com.darshan.payflow.domain.usecase

import com.darshan.payflow.core.BackoffPolicy
import com.darshan.payflow.data.psp.PspException
import com.darshan.payflow.domain.repository.PaymentRepository
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * Polls the PSP for a pending payment with exponential backoff + jitter until it settles or [BackoffPolicy.maxAttempts]
 * is reached. Single-flight per payment: concurrent callers (pay screen, detail screen, reconciler) share one poller, so a
 * polling storm never multiplies PSP calls. A payment that never settles is reported as Pending/Unresolved, never Failed.
 */
@Singleton
class PollPaymentStatusUseCase @Inject constructor(
    private val confirm: ConfirmPaymentUseCase,
    private val policy: BackoffPolicy,
    private val random: Random,
) {
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ConfirmResult>>()

    suspend operator fun invoke(paymentId: String): ConfirmResult {
        val mine = CompletableDeferred<ConfirmResult>()
        val existing = inFlight.putIfAbsent(paymentId, mine)
        if (existing != null) return existing.await()
        try {
            val result = poll(paymentId)
            mine.complete(result)
            return result
        } catch (t: Throwable) {
            mine.completeExceptionally(t)
            throw t
        } finally {
            inFlight.remove(paymentId, mine)
        }
    }

    private suspend fun poll(paymentId: String): ConfirmResult {
        var attempt = 0
        while (true) {
            attempt++
            val result = confirm(paymentId, appResponse = null)
            val retryAfter: Long? = when (result) {
                is ConfirmResult.Pending -> null
                is ConfirmResult.Unresolved -> if (result.error.retryable) (result.error as? PspException.RateLimited)?.retryAfterMillis else return result
                is ConfirmResult.Success, is ConfirmResult.Failed, is ConfirmResult.Mismatch, ConfirmResult.NotFound -> return result
            }
            if (attempt >= policy.maxAttempts) return result
            delay(policy.delayMillis(attempt, retryAfter, random))
        }
    }
}

data class ReconcileSummary(val checked: Int, val settled: Int)

/**
 * Process-death / app-start recovery: every payment still CREATED or PENDING for longer than [minAge] is polled again. The
 * screen that created a fresher payment is still polling it itself, so those are skipped.
 */
@Singleton
class ReconcilePendingPaymentsUseCase @Inject constructor(
    private val payments: PaymentRepository,
    private val poll: PollPaymentStatusUseCase,
    private val clock: Clock,
) {
    suspend operator fun invoke(minAge: Duration = Duration.ofSeconds(30)): ReconcileSummary {
        val stale = payments.pendingOlderThan(clock.instant().minus(minAge))
        var settled = 0
        for (payment in stale) {
            when (poll(payment.id)) {
                is ConfirmResult.Success, is ConfirmResult.Failed -> settled++
                else -> Unit
            }
        }
        return ReconcileSummary(checked = stale.size, settled = settled)
    }
}
