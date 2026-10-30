package com.darshan.payflow.domain.usecase

import com.darshan.payflow.data.upi.UpiAppResponse
import com.darshan.payflow.data.upi.UpiAppStatus
import com.darshan.payflow.data.upi.UpiLaunchResult
import com.darshan.payflow.data.upi.UpiResponseParser
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentEvent
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.StatusSource
import com.darshan.payflow.domain.repository.PaymentRepository
import javax.inject.Inject

/** Outcome of reading a UPI app's answer. Everything here is *reported by the app*; nothing is verified by a bank. */
sealed interface UpiAppResult {
    val payment: Payment?

    /** The app reported success for our reference. */
    data class ReportedSuccess(override val payment: Payment) : UpiAppResult
    /** The app reported a failure (or the user declined inside it). */
    data class ReportedFailure(override val payment: Payment) : UpiAppResult
    /** No usable answer (cancelled, app killed, SUBMITTED, garbage): still PENDING, the user must check in the UPI app. */
    data class Unknown(override val payment: Payment) : UpiAppResult
    /** The answer carried a different `txnRef`: refused, payment stays PENDING. A spoofed or replayed response never settles anything. */
    data class Mismatch(override val payment: Payment, val reportedTxnRef: String) : UpiAppResult
    /** The payment had already settled (duplicate delivery, double callback). */
    data class AlreadySettled(override val payment: Payment) : UpiAppResult
    data object NotFound : UpiAppResult { override val payment: Payment? = null }
}

/**
 * UPI-apps mode, step 2: the chosen app came back. The `response` extra is parsed defensively and matched against *our* `txnRef`:
 * a response that names another reference is refused. SUCCESS / FAILURE move the payment with [StatusSource.UPI_APP]; anything
 * else leaves it PENDING for the user to check in their UPI app (and settle by hand via [MarkPaymentManuallyUseCase]).
 */
class RecordUpiAppResultUseCase @Inject constructor(private val repository: PaymentRepository) {
    suspend operator fun invoke(paymentId: String, result: UpiLaunchResult, appLabel: String? = null): UpiAppResult {
        val payment = repository.getById(paymentId) ?: return UpiAppResult.NotFound
        if (payment.status != PaymentStatus.PENDING && payment.status != PaymentStatus.CREATED) return UpiAppResult.AlreadySettled(payment)
        val response: UpiAppResponse = when (result) {
            UpiLaunchResult.Cancelled -> return UpiAppResult.Unknown(payment)
            is UpiLaunchResult.Responded -> UpiResponseParser.parse(result.raw)
        }
        val echoedRef = response.txnRef
        if (echoedRef != null && !echoedRef.equals(payment.txnRef, ignoreCase = true)) return UpiAppResult.Mismatch(payment, echoedRef)
        val who = appLabel ?: payment.handlerPackage ?: "the UPI app"
        return when (response.status) {
            UpiAppStatus.SUCCESS -> {
                val event = PaymentEvent.Confirm(pspTxnId = null, upiTxnId = response.txnId ?: response.approvalRefNo, source = StatusSource.UPI_APP)
                repository.applyEvent(payment.id, event).fold(
                    onSuccess = { UpiAppResult.ReportedSuccess(it) },
                    onFailure = { settledMeanwhile(payment.id) },
                )
            }
            UpiAppStatus.FAILURE -> {
                val code = response.responseCode?.let { " (code $it)" } ?: ""
                repository.applyEvent(payment.id, PaymentEvent.Fail("Reported as failed by $who$code", StatusSource.UPI_APP)).fold(
                    onSuccess = { UpiAppResult.ReportedFailure(it) },
                    onFailure = { settledMeanwhile(payment.id) },
                )
            }
            UpiAppStatus.SUBMITTED, UpiAppStatus.UNKNOWN -> UpiAppResult.Unknown(payment)
        }
    }

    private suspend fun settledMeanwhile(id: String): UpiAppResult =
        repository.getById(id)?.let { UpiAppResult.AlreadySettled(it) } ?: UpiAppResult.NotFound
}

sealed interface ManualMarkResult {
    data class Marked(val payment: Payment) : ManualMarkResult
    /** Only a pending UPI-app payment can be marked; sandbox payments are settled by the PSP. */
    data class NotAllowed(val payment: Payment) : ManualMarkResult
    data object NotFound : ManualMarkResult
}

/** The user checked their UPI app and tells PayFlow what happened. Recorded with [StatusSource.USER], never as "verified". */
class MarkPaymentManuallyUseCase @Inject constructor(private val repository: PaymentRepository) {
    suspend operator fun invoke(paymentId: String, paid: Boolean): ManualMarkResult {
        val payment = repository.getById(paymentId) ?: return ManualMarkResult.NotFound
        if (!payment.canBeMarkedManually) return ManualMarkResult.NotAllowed(payment)
        val event = if (paid) PaymentEvent.Confirm(null, null, StatusSource.USER) else PaymentEvent.Fail(REASON_NOT_PAID, StatusSource.USER)
        return repository.applyEvent(payment.id, event).fold(
            onSuccess = { ManualMarkResult.Marked(it) },
            onFailure = { repository.getById(paymentId)?.let { ManualMarkResult.NotAllowed(it) } ?: ManualMarkResult.NotFound },
        )
    }

    companion object {
        const val REASON_NOT_PAID = "Marked as not paid by you"
    }
}
