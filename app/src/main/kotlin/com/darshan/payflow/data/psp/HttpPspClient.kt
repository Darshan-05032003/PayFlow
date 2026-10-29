package com.darshan.payflow.data.psp

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * OkHttp implementation of [PspClient] against a REST shape: `POST /v1/payments`, `GET /v1/payments/{txnRef}`.
 * No sandbox server exists for it; it is exercised against MockWebServer so the network code path is real.
 */
class HttpPspClient(
    private val baseUrl: HttpUrl,
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val apiToken: String? = null,
) : PspClient {

    @Serializable
    private data class InitiateBody(val txnRef: String, val amountPaise: Long, val payeeVpa: String, val payeeName: String, val note: String)

    @Serializable
    private data class InitiateDto(val pspTxnId: String, val txnRef: String, val status: String? = null)

    @Serializable
    private data class StatusDto(
        val txnRef: String,
        val status: String,
        val pspTxnId: String? = null,
        val upiTxnId: String? = null,
        val amountPaise: Long? = null,
        val payeeVpa: String? = null,
        @SerialName("failureCode") val failureCode: String? = null,
        @SerialName("failureReason") val failureReason: String? = null,
    )

    override suspend fun initiatePayment(request: PspInitiateRequest): PspInitiateResponse {
        val body = json.encodeToString(InitiateBody(request.txnRef, request.amountPaise, request.payeeVpa, request.payeeName, request.note))
        val http = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("v1/payments").build())
            .post(body.toRequestBody(JSON))
            .apply { request.idempotencyKey?.let { header("Idempotency-Key", it) } }
            .auth()
            .build()
        return execute(http) { text ->
            val dto = json.decodeFromString<InitiateDto>(text)
            PspInitiateResponse(dto.pspTxnId, dto.txnRef)
        }
    }

    override suspend fun getPaymentStatus(txnRef: String): PspPaymentStatus {
        val http = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("v1/payments").addPathSegment(txnRef).build())
            .get()
            .auth()
            .build()
        return execute(http, notFound = { PspPaymentStatus.NotFound(txnRef) }) { text ->
            val dto = json.decodeFromString<StatusDto>(text)
            if (dto.txnRef != txnRef) throw PspException.Malformed("txnRef mismatch")
            when (dto.status.uppercase()) {
                "PENDING" -> PspPaymentStatus.Pending(txnRef)
                "SUCCESS" -> PspPaymentStatus.Success(
                    txnRef = txnRef,
                    pspTxnId = dto.pspTxnId ?: throw PspException.Malformed("missing pspTxnId"),
                    upiTxnId = dto.upiTxnId,
                    amountPaise = dto.amountPaise ?: throw PspException.Malformed("missing amountPaise"),
                    payeeVpa = dto.payeeVpa ?: throw PspException.Malformed("missing payeeVpa"),
                )
                "FAILED" -> PspPaymentStatus.Failed(txnRef, dto.failureCode ?: "UNKNOWN", dto.failureReason ?: "Payment failed")
                else -> throw PspException.Malformed("unknown status ${dto.status.take(20)}")
            }
        }
    }

    private fun Request.Builder.auth(): Request.Builder = apply { apiToken?.let { header("Authorization", "Bearer $it") } }

    private suspend fun <T> execute(request: Request, notFound: (() -> T)? = null, parse: (String) -> T): T {
        val response = try {
            client.newCall(request).await()
        } catch (e: SocketTimeoutException) {
            throw PspException.Timeout(e)
        } catch (e: InterruptedIOException) {
            throw PspException.Timeout(e)
        } catch (e: IOException) {
            throw PspException.Network(e)
        }
        response.use { r ->
            val text = try {
                r.body.string()
            } catch (e: SocketTimeoutException) {
                throw PspException.Timeout(e)
            } catch (e: InterruptedIOException) {
                throw PspException.Timeout(e)
            } catch (e: IOException) {
                throw PspException.Network(e)
            }
            when {
                r.isSuccessful -> return try {
                    parse(text)
                } catch (e: SerializationException) {
                    throw PspException.Malformed(e.message?.take(120) ?: "json")
                } catch (e: IllegalArgumentException) {
                    throw PspException.Malformed(e.message?.take(120) ?: "json")
                }
                r.code == 401 || r.code == 403 -> throw PspException.Unauthorized()
                r.code == 404 && notFound != null -> return notFound()
                r.code == 429 -> throw PspException.RateLimited(parseRetryAfter(r.header("Retry-After")))
                r.code >= 500 -> throw PspException.Server(r.code)
                else -> throw PspException.ClientError(r.code, text.take(512))
            }
        }
    }

    private fun parseRetryAfter(header: String?): Long? {
        val seconds = header?.trim()?.toLongOrNull() ?: return null
        if (seconds < 0) return null
        return (seconds * 1_000).coerceAtMost(PspException.RateLimited.MAX_RETRY_AFTER_MILLIS)
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
