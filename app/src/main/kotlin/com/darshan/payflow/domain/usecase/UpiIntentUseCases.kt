package com.darshan.payflow.domain.usecase

import com.darshan.payflow.data.psp.PspClient
import com.darshan.payflow.data.psp.PspException
import com.darshan.payflow.data.psp.PspInitiateRequest
import com.darshan.payflow.data.psp.PspPaymentStatus
import com.darshan.payflow.data.upi.UpiAppResponse
import com.darshan.payflow.data.upi.UpiAppStatus
import com.darshan.payflow.data.upi.UpiLinkBuilder
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentDirection
import com.darshan.payflow.domain.model.PaymentEvent
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.UpiPayRequest
import com.darshan.payflow.domain.repository.PaymentRepository
import java.time.Clock
import java.util.UUID
import javax.inject.Inject

/** What the UI needs to open the UPI app. */
data class UpiLaunch(val paymentId: String, val upiUri: String)

/**
 * Step 1 of the intent flow: store the payment (CREATED), register it with the PSP, move it to PENDING and return the
 * `upi://pay` link. If the PSP rejects the registration the payment is FAILED and nothing is launched.
 */
class PayViaUpiIntentUseCase(
    private val repository: PaymentRepository,
    private val psp: PspClient,
    private val clock: Clock,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    @Inject constructor(repository: PaymentRepository, psp: PspClient, clock: Clock) : this(repository, psp, clock, { UUID.randomUUID().toString() })

    suspend operator fun invoke(request: UpiPayRequest, method: PaymentMethod, idempotencyKey: String? = null): Result<UpiLaunch> {
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
        repository.create(payment)
        return try {
            psp.initiatePayment(PspInitiateRequest(request.txnRef, request.amountPaise, request.payeeVpa, request.payeeName, request.note, idempotencyKey))
            repository.applyEvent(payment.id, PaymentEvent.Initiate).getOrThrow()
            Result.success(UpiLaunch(payment.id, UpiLinkBuilder.build(request)))
        } catch (e: PspException) {
            repository.applyEvent(payment.id, PaymentEvent.Fail("PSP rejected the payment: ${e.message}"))
            Result.failure(e)
        }
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
