package com.darshan.payflow.domain.usecase

import com.darshan.payflow.data.psp.PspClient
import com.darshan.payflow.data.psp.PspException
import com.darshan.payflow.domain.model.CollectRequest
import com.darshan.payflow.domain.model.CollectStatus
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentDirection
import com.darshan.payflow.domain.model.PaymentEvent
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.VpaValidator
import com.darshan.payflow.domain.repository.CollectRepository
import com.darshan.payflow.domain.repository.PaymentRepository
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Pulls incoming requests from the PSP into Room (dropping malformed rows) and expires overdue ones. */
class RefreshCollectRequestsUseCase @Inject constructor(
    private val collects: CollectRepository,
    private val psp: PspClient,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Result<Unit> {
        val now = clock.instant()
        val remote = try {
            psp.listCollectRequests()
        } catch (e: PspException) {
            collects.expireOverdue(now)
            return Result.failure(e)
        }
        val valid = remote.filter { VpaValidator.isValid(it.payeeVpa) && it.amountPaise in 1..Payment.MAX_AMOUNT_PAISE }.map {
            CollectRequest(
                id = it.id, payeeVpa = it.payeeVpa.lowercase(), payeeName = it.payeeName.ifBlank { it.payeeVpa }.take(99), amountPaise = it.amountPaise,
                note = it.note.take(99), createdAt = Instant.ofEpochMilli(it.createdAtMillis), expiresAt = Instant.ofEpochMilli(it.expiresAtMillis),
                updatedAt = now, status = it.status, paymentId = null,
            )
        }
        collects.upsertAll(valid)
        collects.expireOverdue(now)
        return Result.success(Unit)
    }
}

sealed interface CollectActionResult {
    data class Approved(val request: CollectRequest, val payment: Payment) : CollectActionResult
    data class Declined(val request: CollectRequest) : CollectActionResult
    data class Expired(val request: CollectRequest) : CollectActionResult
    data class AlreadyAnswered(val request: CollectRequest) : CollectActionResult
    /** The PSP could not be reached; the request is still pending and can be answered again. */
    data class Unavailable(val request: CollectRequest, val error: PspException) : CollectActionResult
    data object NotFound : CollectActionResult
}

/**
 * Answers a collect request exactly once. Approving creates a COLLECT payment (CREATED) before the PSP call, answers the PSP,
 * then confirms the payment through the PSP status check like every other payment. A per-request mutex plus the Room
 * compare-and-set make concurrent taps answer once.
 */
@Singleton
class RespondToCollectUseCase(
    private val collects: CollectRepository,
    private val payments: PaymentRepository,
    private val psp: PspClient,
    private val clock: Clock,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    @Inject constructor(collects: CollectRepository, payments: PaymentRepository, psp: PspClient, clock: Clock) :
        this(collects, payments, psp, clock, { UUID.randomUUID().toString() })

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val confirm = ConfirmPaymentUseCase(payments, psp, clock)

    suspend operator fun invoke(requestId: String, approve: Boolean): CollectActionResult =
        locks.getOrPut(requestId) { Mutex() }.withLock {
            val now = clock.instant()
            val request = collects.getById(requestId) ?: return CollectActionResult.NotFound
            when {
                request.status == CollectStatus.EXPIRED -> return CollectActionResult.Expired(request)
                request.status != CollectStatus.PENDING -> return CollectActionResult.AlreadyAnswered(request)
                request.isOverdue(now) -> {
                    collects.compareAndSetStatus(requestId, CollectStatus.PENDING, CollectStatus.EXPIRED, null, now)
                    return CollectActionResult.Expired(request.copy(status = CollectStatus.EXPIRED))
                }
            }
            if (!approve) return decline(request, now)
            approve(request, now)
        }

    private suspend fun decline(request: CollectRequest, now: Instant): CollectActionResult {
        try {
            psp.respondToCollect(request.id, approve = false)
        } catch (e: PspException) {
            return pspRejection(request, now, e)
        }
        collects.compareAndSetStatus(request.id, CollectStatus.PENDING, CollectStatus.DECLINED, null, now)
        return CollectActionResult.Declined(request.copy(status = CollectStatus.DECLINED, updatedAt = now))
    }

    private suspend fun approve(request: CollectRequest, now: Instant): CollectActionResult {
        val payment = Payment(
            id = idGenerator(), txnRef = collectTxnRef(request.id), payeeVpa = request.payeeVpa, payeeName = request.payeeName,
            amountPaise = request.amountPaise, note = request.note, status = PaymentStatus.CREATED, method = PaymentMethod.COLLECT,
            direction = PaymentDirection.OUTGOING, pspTxnId = null, upiTxnId = null, refundId = null, failureReason = null,
            createdAt = now, updatedAt = now, idempotencyKey = null,
        )
        payments.create(payment)
        try {
            psp.respondToCollect(request.id, approve = true)
        } catch (e: PspException) {
            payments.applyEvent(payment.id, PaymentEvent.Fail("PSP did not accept the collect approval: ${e.message}"))
            return pspRejection(request, now, e)
        }
        payments.applyEvent(payment.id, PaymentEvent.Initiate)
        collects.compareAndSetStatus(request.id, CollectStatus.PENDING, CollectStatus.APPROVED, payment.id, now)
        val confirmed = confirm(payment.id, appResponse = null).payment ?: payment
        return CollectActionResult.Approved(request.copy(status = CollectStatus.APPROVED, paymentId = payment.id, updatedAt = now), confirmed)
    }

    /** 410 = expired at the PSP; 409 = answered elsewhere (another device/process): the next refresh fetches the real answer. */
    private suspend fun pspRejection(request: CollectRequest, now: Instant, e: PspException): CollectActionResult = when {
        e is PspException.ClientError && e.status == 410 -> expire(request, now)
        e is PspException.ClientError && e.status == 409 -> CollectActionResult.AlreadyAnswered(request)
        else -> CollectActionResult.Unavailable(request, e)
    }

    private suspend fun expire(request: CollectRequest, now: Instant): CollectActionResult {
        collects.compareAndSetStatus(request.id, CollectStatus.PENDING, CollectStatus.EXPIRED, null, now)
        return CollectActionResult.Expired(request.copy(status = CollectStatus.EXPIRED))
    }

    companion object {
        /** Must match the PSP's convention for the order created by an approval (`FakePsp.collectTxnRef`). */
        fun collectTxnRef(requestId: String): String = "COL" + requestId.filter { it.isLetterOrDigit() }.take(32)
    }
}
