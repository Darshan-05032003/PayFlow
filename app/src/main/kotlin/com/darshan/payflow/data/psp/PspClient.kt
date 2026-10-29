package com.darshan.payflow.data.psp

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
}
