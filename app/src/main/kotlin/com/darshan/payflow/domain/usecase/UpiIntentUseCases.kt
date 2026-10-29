package com.darshan.payflow.domain.usecase

import com.darshan.payflow.core.BackoffPolicy
import com.darshan.payflow.core.RetryExecutor
import com.darshan.payflow.data.psp.PspClient
import com.darshan.payflow.data.psp.PspException
import com.darshan.payflow.data.psp.PspInitiateRequest
import com.darshan.payflow.data.psp.PspPaymentStatus
import com.darshan.payflow.data.upi.UpiAppResponse
import com.darshan.payflow.data.upi.UpiAppStatus
import com.darshan.payflow.data.upi.UpiLinkBuilder
import com.darshan.payflow.domain.model.IdempotencyConflictException
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentDirection
import com.darshan.payflow.domain.model.PaymentEvent
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.UpiPayRequest
import com.darshan.payflow.domain.repository.PaymentRepository
import java.time.Clock
import java.util.UUID
import kotlin.random.Random
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the UI needs to open the UPI app. */
data class UpiLaunch(val paymentId: String, val upiUri: String)

/**
 * Step 1 of the intent flow: store the payment (CREATED), register it with the PSP, move it to PENDING and return the
 * `upi://pay` link. If the PSP rejects the registration the payment is FAILED and nothing is launched.
 *
 * Idempotency: with an [idempotencyKey] the payment row (unique index on the key) is written *before* the PSP is called.
 * A retry, a double tap or a relaunch after process death with the same key returns the stored payment instead of creating
 * a second one; a key reused with different details is an [IdempotencyConflictException]. Callers in one process are
 * serialised per key so the PSP is called once; a CREATED row found after process death is taken over and completed.
 */
@Singleton
class PayViaUpiIntentUseCase(
    private val repository: PaymentRepository,
    private val psp: PspClient,
    private val clock: Clock,
    /** Retries for the PSP registration call (safe: the PSP dedupes by Idempotency-Key / txnRef). Tests default to none. */
    private val retry: RetryExecutor = RetryExecutor(BackoffPolicy.NONE),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    @Inject constructor(repository: PaymentRepository, psp: PspClient, clock: Clock, random: Random) :
        this(repository, psp, clock, RetryExecutor(BackoffPolicy.INITIATE, random), { UUID.randomUUID().toString() })

    private val keyLocks = ConcurrentHashMap<String, Mutex>()

    suspend operator fun invoke(request: UpiPayRequest, method: PaymentMethod, idempotencyKey: String? = null): Result<UpiLaunch> {
        if (idempotencyKey == null) return submit(request, method, null)
        return keyLocks.getOrPut(idempotencyKey) { Mutex() }.withLock {
            repository.findByIdempotencyKey(idempotencyKey)?.let { return reuse(it, request, idempotencyKey) }
            submit(request, method, idempotencyKey)
        }
    }

    /** Dedupe path: the key already has a payment. Same details → same launch; different details or spent → conflict. */
    private suspend fun reuse(existing: Payment, request: UpiPayRequest, key: String): Result<UpiLaunch> {
        if (existing.amountPaise != request.amountPaise || !existing.payeeVpa.equals(request.payeeVpa, ignoreCase = true)) {
            return Result.failure(IdempotencyConflictException(key, "different amount or payee"))
        }
        val link = UpiLinkBuilder.build(UpiPayRequest(existing.payeeVpa, existing.payeeName, existing.amountPaise, existing.note, existing.txnRef))
        return when (existing.status) {
            PaymentStatus.PENDING -> Result.success(UpiLaunch(existing.id, link))
            // Stored but the PSP was never reached (process died in between): take over and finish the registration.
            PaymentStatus.CREATED -> register(existing, key).map { UpiLaunch(existing.id, link) }
            PaymentStatus.SUCCESS, PaymentStatus.FAILED, PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED ->
                Result.failure(IdempotencyConflictException(key, "attempt already settled as ${existing.status}"))
        }
    }

    private suspend fun submit(request: UpiPayRequest, method: PaymentMethod, idempotencyKey: String?): Result<UpiLaunch> {
        val now = clock.instant()
        val payment = Payment(
            id = idGenerator(),
            txnRef = request.txnRef,
            payeeVpa = request.payeeVpa,
            payeeName = request.payeeName,
            amountPaise = request.amountPaise,
            note = request.note,
            status = PaymentStatus.CREATED,
            method = method,
            direction = PaymentDirection.OUTGOING,
            pspTxnId = null,
            upiTxnId = null,
            refundId = null,
            failureReason = null,
            createdAt = now,
            updatedAt = now,
            idempotencyKey = idempotencyKey,
        )
        try {
            repository.create(payment)
        } catch (e: Exception) {
            // Unique index on the key: another process inserted first. Reuse its row.
            val existing = idempotencyKey?.let { repository.findByIdempotencyKey(it) } ?: return Result.failure(e)
            return reuse(existing, request, idempotencyKey)
        }
        return register(payment, idempotencyKey).map { UpiLaunch(payment.id, UpiLinkBuilder.build(request)) }
    }

    /** CREATED → PSP registration → PENDING. A PSP rejection fails the payment: the UPI app was never opened. */
    private suspend fun register(payment: Payment, idempotencyKey: String?): Result<Unit> = try {
        retry.execute {
            psp.initiatePayment(PspInitiateRequest(payment.txnRef, payment.amountPaise, payment.payeeVpa, payment.payeeName, payment.note, idempotencyKey))
        }.getOrThrow()
        repository.applyEvent(payment.id, PaymentEvent.Initiate).getOrElse { error ->
            // Lost a race with another process that already moved it on: fine as long as it is not failed.
            val latest = repository.getById(payment.id) ?: throw error
            if (latest.status == PaymentStatus.FAILED) throw error
        }
        Result.success(Unit)
    } catch (e: PspException) {
        repository.applyEvent(payment.id, PaymentEvent.Fail("PSP rejected the payment: ${e.message}"))
        Result.failure(e)
    }
}

sealed interface ConfirmResult {
    val payment: Payment?

    data class Success(override val payment: Payment) : ConfirmResult
    data class Failed(override val payment: Payment) : ConfirmResult
    data class Pending(override val payment: Payment) : ConfirmResult
    /** The PSP could not be reached; the payment stays PENDING and must be checked again. */
    data class Unresolved(override val payment: Payment, val error: PspException) : ConfirmResult
    /** The PSP reports success for different details (amount / payee / ref): never confirmed. */
    data class Mismatch(override val payment: Payment, val reported: PspPaymentStatus.Success) : ConfirmResult
    data object NotFound : ConfirmResult { override val payment: Payment? = null }
}

/**
 * Step 2: the UPI app came back (or didn't). Whatever it said, ask the PSP. Only a PSP `Success` whose txnRef, amount and
 * payee match our stored payment moves it to SUCCESS. A spoofed app response can never produce a success on its own.
 */
class ConfirmPaymentUseCase @Inject constructor(
    private val repository: PaymentRepository,
    private val psp: PspClient,
    @Suppress("unused") private val clock: Clock,
) {
    suspend operator fun invoke(paymentId: String, appResponse: UpiAppResponse?): ConfirmResult {
        val payment = repository.getById(paymentId) ?: return ConfirmResult.NotFound
        if (payment.status.isTerminal || payment.status == PaymentStatus.SUCCESS) return terminal(payment)

        val status = try {
            psp.getPaymentStatus(payment.txnRef)
        } catch (e: PspException) {
            return ConfirmResult.Unresolved(payment, e)
        }
        return when (status) {
            is PspPaymentStatus.Pending -> ConfirmResult.Pending(payment)
            is PspPaymentStatus.NotFound -> fail(payment, "PSP has no record of ${payment.txnRef}", appResponse)
            is PspPaymentStatus.Failed -> fail(payment, "PSP ${status.code}: ${status.reason}", appResponse)
            is PspPaymentStatus.Success -> {
                val matches = status.txnRef == payment.txnRef &&
                    status.amountPaise == payment.amountPaise &&
                    status.payeeVpa.equals(payment.payeeVpa, ignoreCase = true)
                if (!matches) return ConfirmResult.Mismatch(payment, status)
                val updated = repository.applyEvent(payment.id, PaymentEvent.Confirm(status.pspTxnId, status.upiTxnId ?: appResponse?.txnId))
                    .getOrElse { repository.getById(payment.id) ?: return ConfirmResult.NotFound }
                terminal(updated)
            }
        }
    }

    private suspend fun fail(payment: Payment, reason: String, appResponse: UpiAppResponse?): ConfirmResult {
        val detail = if (appResponse?.status == UpiAppStatus.FAILURE && appResponse.responseCode != null) {
            "$reason (app code ${appResponse.responseCode})"
        } else {
            reason
        }
        val updated = repository.applyEvent(payment.id, PaymentEvent.Fail(detail)).getOrElse { repository.getById(payment.id) ?: return ConfirmResult.NotFound }
        return terminal(updated)
    }

    private fun terminal(payment: Payment): ConfirmResult = when (payment.status) {
        PaymentStatus.SUCCESS, PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED -> ConfirmResult.Success(payment)
        PaymentStatus.FAILED -> ConfirmResult.Failed(payment)
        PaymentStatus.CREATED, PaymentStatus.PENDING -> ConfirmResult.Pending(payment)
    }
}
