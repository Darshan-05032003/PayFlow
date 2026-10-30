package com.darshan.payflow.data.psp

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** The real OkHttp network path against MockWebServer: success, 4xx, 401, 429, 5xx, timeout, malformed JSON. */
class HttpPspClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: HttpPspClient
    private val request = PspInitiateRequest("PF1", 12_300, "alice@okbank", "Alice", "Lunch", idempotencyKey = "idem-1")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val ok = OkHttpClient.Builder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .callTimeout(1_000, TimeUnit.MILLISECONDS)
            .build()
        client = HttpPspClient(server.url("/"), ok, Json { ignoreUnknownKeys = true }, apiToken = "test-token")
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `initiate posts json with idempotency header and parses 201`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(201).body("""{"pspTxnId":"psp-9","txnRef":"PF1","status":"PENDING"}""").build())
        val response = client.initiatePayment(request)
        assertThat(response).isEqualTo(PspInitiateResponse("psp-9", "PF1"))
        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.url.encodedPath).isEqualTo("/v1/payments")
        assertThat(recorded.headers["Idempotency-Key"]).isEqualTo("idem-1")
        assertThat(recorded.headers["Authorization"]).isEqualTo("Bearer test-token")
        assertThat(recorded.headers["Content-Type"]).startsWith("application/json")
        val body = recorded.body!!.utf8()
        assertThat(body).contains("\"txnRef\":\"PF1\"")
        assertThat(body).contains("\"amountPaise\":12300")
    }

    @Test
    fun `status maps pending, success and failed bodies`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"txnRef":"PF1","status":"PENDING"}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"txnRef":"PF1","status":"SUCCESS","pspTxnId":"p1","upiTxnId":"u1","amountPaise":12300,"payeeVpa":"alice@okbank"}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"txnRef":"PF1","status":"FAILED","failureCode":"U30","failureReason":"Declined by bank"}""").build())
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":"not found"}""").build())
        assertThat(client.getPaymentStatus("PF1")).isEqualTo(PspPaymentStatus.Pending("PF1"))
        assertThat(client.getPaymentStatus("PF1")).isEqualTo(PspPaymentStatus.Success("PF1", "p1", "u1", 12_300, "alice@okbank"))
        assertThat(client.getPaymentStatus("PF1")).isEqualTo(PspPaymentStatus.Failed("PF1", "U30", "Declined by bank"))
        assertThat(client.getPaymentStatus("PF1")).isEqualTo(PspPaymentStatus.NotFound("PF1"))
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/v1/payments/PF1")
    }

    @Test
    fun `txnRef is path encoded so it cannot escape the resource`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).body("{}").build())
        client.getPaymentStatus("../admin?x=1")
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/v1/payments/..%2Fadmin%3Fx=1")
    }

    @Test
    fun `http errors become typed exceptions`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":"bad amount"}""").build())
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "3").build())
        server.enqueue(MockResponse.Builder().code(500).build())
        server.enqueue(MockResponse.Builder().code(503).build())

        val bad = runCatching { client.initiatePayment(request) }.exceptionOrNull() as PspException.ClientError
        assertThat(bad.status).isEqualTo(400)
        assertThat(runCatching { client.initiatePayment(request) }.exceptionOrNull()).isInstanceOf(PspException.Unauthorized::class.java)
        val limited = runCatching { client.initiatePayment(request) }.exceptionOrNull() as PspException.RateLimited
        assertThat(limited.retryAfterMillis).isEqualTo(3_000)
        assertThat((runCatching { client.initiatePayment(request) }.exceptionOrNull() as PspException.Server).status).isEqualTo(500)
        assertThat((runCatching { client.getPaymentStatus("PF1") }.exceptionOrNull() as PspException.Server).status).isEqualTo(503)
    }

    @Test
    fun `timeout becomes a Timeout exception`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("{}").bodyDelay(5, TimeUnit.SECONDS).build())
        assertThat(runCatching { client.getPaymentStatus("PF1") }.exceptionOrNull()).isInstanceOf(PspException.Timeout::class.java)
    }

    @Test
    fun `connection drop becomes a Network exception`() = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        assertThat(runCatching { client.getPaymentStatus("PF1") }.exceptionOrNull()).isInstanceOf(PspException.Network::class.java)
    }

    @Test
    fun `malformed json, wrong types and unknown status become Malformed`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("<html>not json</html>").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"txnRef":"PF1","status":"SUCCESS","amountPaise":"lots"}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"txnRef":"PF1","status":"WEIRD"}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"txnRef":"OTHER","status":"PENDING"}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"txnRef":"PF1","status":"SUCCESS"}""").build())
        repeat(5) {
            assertThat(runCatching { client.getPaymentStatus("PF1") }.exceptionOrNull()).isInstanceOf(PspException.Malformed::class.java)
        }
    }

    @Test
    fun `retry-after is capped and garbage is ignored`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "999999999").build())
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "soon").build())
        assertThat((runCatching { client.getPaymentStatus("PF1") }.exceptionOrNull() as PspException.RateLimited).retryAfterMillis).isEqualTo(PspException.RateLimited.MAX_RETRY_AFTER_MILLIS)
        assertThat((runCatching { client.getPaymentStatus("PF1") }.exceptionOrNull() as PspException.RateLimited).retryAfterMillis).isNull()
    }

    // --- feature 4: VPA verify + collect endpoints ---

    @Test
    fun `verify vpa maps valid, invalid and errors`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"vpa":"alice@okbank","valid":true,"payeeName":"Alice Kumar"}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"vpa":"nobody@okbank","valid":false}""").build())
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"vpa":"alice@okbank","valid":true}""").build())
        server.enqueue(MockResponse.Builder().code(429).build())
        assertThat(client.verifyVpa("alice@okbank")).isEqualTo(VpaVerification.Verified("alice@okbank", "Alice Kumar"))
        assertThat(client.verifyVpa("nobody@okbank")).isEqualTo(VpaVerification.NotFound("nobody@okbank"))
        assertThat(client.verifyVpa("nobody@okbank")).isEqualTo(VpaVerification.NotFound("nobody@okbank"))
        assertThat(runCatching { client.verifyVpa("alice@okbank") }.exceptionOrNull()).isInstanceOf(PspException.Malformed::class.java)
        assertThat(runCatching { client.verifyVpa("alice@okbank") }.exceptionOrNull()).isInstanceOf(PspException.RateLimited::class.java)
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/v1/vpa/alice@okbank")
    }

    @Test
    fun `collect requests are listed and answered`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """[{"id":"req-1","payeeVpa":"shop@okbank","payeeName":"Chai Point","amountPaise":4500,"note":"Table 4",
                   "createdAt":1793268930000,"expiresAt":1793269530000,"status":"PENDING"},
                   {"id":"bad","payeeVpa":"not a vpa","payeeName":"x","amountPaise":-5,"note":"","createdAt":1,"expiresAt":2,"status":"PENDING"}]""",
            ).build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"req-1","status":"APPROVED","pspTxnId":"psp-7","txnRef":"COLreq-1"}""").build())
        server.enqueue(MockResponse.Builder().code(410).body("""{"error":"expired"}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""not json""").build())

        val list = client.listCollectRequests()
        assertThat(list.map { it.id }).containsExactly("req-1") // the hostile row is dropped, not fatal
        assertThat(list.single().amountPaise).isEqualTo(4_500)

        val answer = client.respondToCollect("req-1", approve = true)
        assertThat(answer).isEqualTo(PspCollectAnswer("req-1", com.darshan.payflow.domain.model.CollectStatus.APPROVED, "psp-7", "COLreq-1"))
        val expired = runCatching { client.respondToCollect("req-1", approve = true) }.exceptionOrNull() as PspException.ClientError
        assertThat(expired.status).isEqualTo(410)
        assertThat(runCatching { client.respondToCollect("req-1", approve = false) }.exceptionOrNull()).isInstanceOf(PspException.Malformed::class.java)

        server.takeRequest() // list
        val respond = server.takeRequest()
        assertThat(respond.method).isEqualTo("POST")
        assertThat(respond.url.encodedPath).isEqualTo("/v1/collect/req-1/respond")
        assertThat(respond.body!!.utf8()).contains("\"approve\":true")
    }

    // --- feature 5: mandates ---

    @Test
    fun `mandate create, update and execute endpoints`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(201).body("""{"umn":"UMN-1","status":"ACTIVE"}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"umn":"UMN-1","status":"PAUSED"}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"umn":"UMN-1","status":"BANANA"}""").build())
        server.enqueue(MockResponse.Builder().code(201).body("""{"pspTxnId":"psp-5","txnRef":"MND1","status":"PENDING"}""").build())
        server.enqueue(MockResponse.Builder().code(422).body("""{"error":"max amount"}""").build())

        val created = client.createMandate(PspMandateRequest("netflix@okbank", "Netflix", 64_900, "MONTHLY", "2026-10-29", "2027-10-29", "plan"))
        assertThat(created).isEqualTo(PspMandate("UMN-1", com.darshan.payflow.domain.model.MandateStatus.ACTIVE))
        assertThat(client.updateMandate("UMN-1", "PAUSE")).isEqualTo(PspMandate("UMN-1", com.darshan.payflow.domain.model.MandateStatus.PAUSED))
        assertThat(runCatching { client.updateMandate("UMN-1", "RESUME") }.exceptionOrNull()).isInstanceOf(PspException.Malformed::class.java)
        assertThat(client.executeMandate("UMN-1", "MND1", 500)).isEqualTo(PspInitiateResponse("psp-5", "MND1"))
        assertThat((runCatching { client.executeMandate("UMN-1", "MND2", 999_999) }.exceptionOrNull() as PspException.ClientError).status).isEqualTo(422)

        val create = server.takeRequest()
        assertThat(create.method).isEqualTo("POST"); assertThat(create.url.encodedPath).isEqualTo("/v1/mandates")
        assertThat(create.body!!.utf8()).contains("\"frequency\":\"MONTHLY\"")
        val update = server.takeRequest()
        assertThat(update.url.encodedPath).isEqualTo("/v1/mandates/UMN-1"); assertThat(update.body!!.utf8()).contains("\"action\":\"PAUSE\"")
        server.takeRequest()
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/v1/mandates/UMN-1/execute")
    }

    @Test
    fun `refund endpoint`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"refundId":"rf-9","txnRef":"PF1","amountPaise":12300}""").build())
        server.enqueue(MockResponse.Builder().code(409).body("""{"error":"not captured"}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"txnRef":"PF1","status":"REFUNDED","pspTxnId":"p1","amountPaise":12300,"payeeVpa":"alice@okbank","refundId":"rf-9"}""").build())
        assertThat(client.refund("PF1", 12_300, "rf-key")).isEqualTo(PspRefund("rf-9", "PF1", 12_300))
        assertThat((runCatching { client.refund("PF1", 12_300, "rf-key") }.exceptionOrNull() as PspException.ClientError).status).isEqualTo(409)
        assertThat(client.getPaymentStatus("PF1")).isEqualTo(PspPaymentStatus.Refunded("PF1", "p1", 12_300, "rf-9"))
        val req = server.takeRequest()
        assertThat(req.url.encodedPath).isEqualTo("/v1/payments/PF1/refund")
        assertThat(req.headers["Idempotency-Key"]).isEqualTo("rf-key")
    }
}
