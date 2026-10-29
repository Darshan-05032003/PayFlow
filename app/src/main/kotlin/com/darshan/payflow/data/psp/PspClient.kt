package com.darshan.payflow.data.psp

import com.darshan.payflow.domain.model.CollectStatus
import com.darshan.payflow.domain.model.MandateStatus

/** Registers a payment order with the PSP before the UPI app is opened. */
data class PspInitiateRequest(
    val txnRef: String,
    val amountPaise: Long,
    val payeeVpa: String,
    val payeeName: String,
    val note: String,
    val idempotencyKey: String?,
)

data class PspInitiateResponse(val pspTxnId: String, val txnRef: String)

/** Authoritative status of a payment as the PSP sees it. */
sealed interface PspPaymentStatus {
    data class Pending(val txnRef: String) : PspPaymentStatus
    data class Success(val txnRef: String, val pspTxnId: String, val upiTxnId: String?, val amountPaise: Long, val payeeVpa: String) : PspPaymentStatus
    data class Failed(val txnRef: String, val code: String, val reason: String) : PspPaymentStatus
    data class NotFound(val txnRef: String) : PspPaymentStatus
}

/** Result of asking the PSP who owns a VPA. */
sealed interface VpaVerification {
    data class Verified(val vpa: String, val payeeName: String) : VpaVerification
    data class NotFound(val vpa: String) : VpaVerification
}

/** An incoming collect ("pay me") request as the PSP relays it. */
data class PspCollectRequest(
    val id: String,
    val payeeVpa: String,
    val payeeName: String,
    val amountPaise: Long,
    val note: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val status: CollectStatus,
)

data class PspCollectAnswer(val id: String, val status: CollectStatus, val pspTxnId: String?, val txnRef: String?)

/** Mandate registration request; dates are ISO `yyyy-MM-dd`, frequency is the [com.darshan.payflow.domain.model.MandateFrequency] name. */
data class PspMandateRequest(
    val payeeVpa: String,
    val payeeName: String,
    val maxAmountPaise: Long,
    val frequency: String,
    val startDate: String,
    val endDate: String,
    val note: String,
)

data class PspMandate(val umn: String, val status: MandateStatus)

/** Typed transport/API failures. `retryable` drives the backoff logic. */
sealed class PspException(message: String, val retryable: Boolean, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable? = null) : PspException("network error", retryable = true, cause = cause)
    class Timeout(cause: Throwable? = null) : PspException("timeout", retryable = true, cause = cause)
    class Server(val status: Int) : PspException("server error $status", retryable = true)
    class RateLimited(val retryAfterMillis: Long?) : PspException("rate limited", retryable = true) {
        companion object { const val MAX_RETRY_AFTER_MILLIS = 60_000L }
    }
    class Unauthorized : PspException("unauthorized", retryable = false)
    class ClientError(val status: Int, val body: String?) : PspException("client error $status", retryable = false)
    class Malformed(detail: String) : PspException("malformed response: $detail", retryable = false)
}

/**
 * The payment service provider. Two implementations: [FakePsp] (in-memory sandbox, the default: there is no real
 * backend) and [HttpPspClient] (OkHttp against the documented REST shape, exercised with MockWebServer).
 */
interface PspClient {
    suspend fun initiatePayment(request: PspInitiateRequest): PspInitiateResponse
    suspend fun getPaymentStatus(txnRef: String): PspPaymentStatus

    /** Looks a VPA up in the PSP directory and returns the registered payee name. */
    suspend fun verifyVpa(vpa: String): VpaVerification

    suspend fun listCollectRequests(): List<PspCollectRequest>

    /** Approving creates a PSP order under `txnRef` that must then be confirmed through [getPaymentStatus]. */
    suspend fun respondToCollect(requestId: String, approve: Boolean): PspCollectAnswer

    suspend fun createMandate(request: PspMandateRequest): PspMandate

    /** [action] is a [com.darshan.payflow.domain.model.MandateAction] name (PAUSE / RESUME / REVOKE). */
    suspend fun updateMandate(umn: String, action: String): PspMandate

    /** A debit under the mandate; creates an order under [txnRef] to be confirmed through [getPaymentStatus]. */
    suspend fun executeMandate(umn: String, txnRef: String, amountPaise: Long): PspInitiateResponse
}
