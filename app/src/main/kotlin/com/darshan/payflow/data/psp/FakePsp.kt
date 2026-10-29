package com.darshan.payflow.data.psp

import com.darshan.payflow.domain.model.CollectStatus
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class FakeOutcome { SUCCESS, FAILED, PENDING_UNTIL_SETTLED }

/**
 * Knobs for the sandbox PSP. Latency uses `delay`, so tests with a `TestDispatcher` run on virtual time.
 * Rates are per status call and independent; `failureRate` picks FAILED as the final outcome at initiate time.
 */
data class FakePspConfig(
    val latencyMillis: Long = 0,
    val pendingPolls: Int = 0,
    val outcome: FakeOutcome = FakeOutcome.SUCCESS,
    val failureRate: Double = 0.0,
    val timeoutRate: Double = 0.0,
    val serverErrorRate: Double = 0.0,
    val rateLimitRate: Double = 0.0,
)

/** Simulated incoming collect request held by the sandbox PSP. */
class FakeCollect(
    val id: String,
    val payeeVpa: String,
    val payeeName: String,
    val amountPaise: Long,
    val note: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    @Volatile var status: CollectStatus = CollectStatus.PENDING,
)

class FakeOrder(
    val txnRef: String,
    val pspTxnId: String,
    val amountPaise: Long,
    val payeeVpa: String,
    @Volatile var finalOutcome: FakeOutcome,
    val createdAtMillis: Long,
) {
    val polls = AtomicInteger()
}

/** In-memory sandbox PSP. Thread-safe; deterministic for a given [seed]. */
class FakePsp(
    private val clock: Clock,
    private val config: FakePspConfig = FakePspConfig(),
    seed: Int = 1,
) : PspClient {
    private val random = Random(seed)
    private val mutex = Mutex()
    val orders = ConcurrentHashMap<String, FakeOrder>()
    val collectRequests = ConcurrentHashMap<String, FakeCollect>()
    private val _initiateCalls = AtomicInteger()
    private val _statusCalls = AtomicInteger()
    private val _verifyCalls = AtomicInteger()
    val initiateCalls: Int get() = _initiateCalls.get()
    val statusCalls: Int get() = _statusCalls.get()
    val verifyCalls: Int get() = _verifyCalls.get()

    override suspend fun initiatePayment(request: PspInitiateRequest): PspInitiateResponse {
        _initiateCalls.incrementAndGet()
        simulateLatency()
        injectTransientFailure()
        return mutex.withLock {
            val existing = orders[request.txnRef]
            if (existing != null) {
                if (existing.amountPaise != request.amountPaise || existing.payeeVpa != request.payeeVpa) {
                    throw PspException.ClientError(409, "txnRef already used with different details")
                }
                return PspInitiateResponse(existing.pspTxnId, existing.txnRef)
            }
            val outcome = when {
                config.outcome == FakeOutcome.PENDING_UNTIL_SETTLED -> FakeOutcome.PENDING_UNTIL_SETTLED
                config.outcome == FakeOutcome.FAILED -> FakeOutcome.FAILED
                random.nextDouble() < config.failureRate -> FakeOutcome.FAILED
                else -> FakeOutcome.SUCCESS
            }
            val order = FakeOrder(request.txnRef, "PSP${orders.size + 1}${request.txnRef.takeLast(6)}", request.amountPaise, request.payeeVpa, outcome, clock.millis())
            orders[request.txnRef] = order
            PspInitiateResponse(order.pspTxnId, order.txnRef)
        }
    }

    override suspend fun getPaymentStatus(txnRef: String): PspPaymentStatus {
        _statusCalls.incrementAndGet()
        simulateLatency()
        injectTransientFailure()
        val order = orders[txnRef] ?: return PspPaymentStatus.NotFound(txnRef)
        val poll = order.polls.incrementAndGet()
        if (poll <= config.pendingPolls) return PspPaymentStatus.Pending(txnRef)
        return when (order.finalOutcome) {
            FakeOutcome.PENDING_UNTIL_SETTLED -> PspPaymentStatus.Pending(txnRef)
            FakeOutcome.SUCCESS -> PspPaymentStatus.Success(txnRef, order.pspTxnId, "UPI${order.pspTxnId}", order.amountPaise, order.payeeVpa)
            FakeOutcome.FAILED -> PspPaymentStatus.Failed(txnRef, "U30", "Declined by the sandbox bank")
        }
    }

    override suspend fun verifyVpa(vpa: String): VpaVerification {
        _verifyCalls.incrementAndGet()
        simulateLatency()
        injectTransientFailure()
        val normalized = vpa.trim().lowercase()
        val user = normalized.substringBefore('@')
        val handle = normalized.substringAfter('@', "")
        if (handle !in KNOWN_HANDLES || user in UNKNOWN_USERS) return VpaVerification.NotFound(normalized)
        return VpaVerification.Verified(normalized, DIRECTORY[normalized] ?: syntheticName(user))
    }

    override suspend fun listCollectRequests(): List<PspCollectRequest> {
        simulateLatency()
        injectTransientFailure()
        val now = clock.millis()
        return collectRequests.values.sortedByDescending { it.createdAtMillis }.map { c ->
            val status = if (c.status == CollectStatus.PENDING && c.expiresAtMillis <= now) CollectStatus.EXPIRED else c.status
            PspCollectRequest(c.id, c.payeeVpa, c.payeeName, c.amountPaise, c.note, c.createdAtMillis, c.expiresAtMillis, status)
        }
    }

    override suspend fun respondToCollect(requestId: String, approve: Boolean): PspCollectAnswer {
        simulateLatency()
        injectTransientFailure()
        val request = collectRequests[requestId] ?: throw PspException.ClientError(404, "unknown collect request")
        return mutex.withLock {
            if (request.status == CollectStatus.PENDING && request.expiresAtMillis <= clock.millis()) request.status = CollectStatus.EXPIRED
            when (request.status) {
                CollectStatus.EXPIRED -> throw PspException.ClientError(410, "collect request expired")
                CollectStatus.APPROVED, CollectStatus.DECLINED -> throw PspException.ClientError(409, "collect request already answered")
                CollectStatus.PENDING -> Unit
            }
            if (!approve) {
                request.status = CollectStatus.DECLINED
                return PspCollectAnswer(requestId, CollectStatus.DECLINED, null, null)
            }
            val txnRef = collectTxnRef(requestId)
            val order = FakeOrder(txnRef, "PSP${orders.size + 1}${txnRef.takeLast(6)}", request.amountPaise, request.payeeVpa, FakeOutcome.SUCCESS, clock.millis())
            orders[txnRef] = order
            request.status = CollectStatus.APPROVED
            PspCollectAnswer(requestId, CollectStatus.APPROVED, order.pspTxnId, txnRef)
        }
    }

    /** Simulates a payee sending this device a collect request. */
    fun simulateIncomingCollect(id: String, payeeVpa: String, payeeName: String, amountPaise: Long, note: String, ttlMillis: Long) {
        val now = clock.millis()
        collectRequests[id] = FakeCollect(id, payeeVpa, payeeName, amountPaise, note, now, now + ttlMillis)
    }

    /** Acts as the payer / remitter bank: settles a pending order. */
    fun settle(txnRef: String, approved: Boolean) {
        orders[txnRef]?.finalOutcome = if (approved) FakeOutcome.SUCCESS else FakeOutcome.FAILED
    }

    private fun syntheticName(user: String): String {
        val words = user.split('.', '_', '-').map { w -> w.filter { it.isLetter() } }.filter { it.isNotEmpty() }
        val name = words.joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }.take(60)
        return if (name.length >= 2) name else "Payee"
    }

    private suspend fun simulateLatency() {
        if (config.latencyMillis > 0) delay(config.latencyMillis)
    }

    companion object {
        val KNOWN_HANDLES = setOf("okbank", "upi", "ybl", "paytm", "okicici", "oksbi", "okaxis", "okhdfcbank", "ibl", "axl", "apl")
        val UNKNOWN_USERS = setOf("unknown", "nobody", "invalid")
        val DIRECTORY = mapOf(
            "alice@okbank" to "Alice Kumar",
            "shop@okbank" to "Chai Point",
            "bob@upi" to "Bob Mathew",
            "landlord@ybl" to "Mr Rao",
            "9876543210@upi" to "Ravi Sharma",
        )

        /** Order reference the sandbox uses for an approved collect request (alphanumeric, ≤ 35 chars). */
        fun collectTxnRef(requestId: String): String = "COL" + requestId.filter { it.isLetterOrDigit() }.take(32)
    }

    private fun injectTransientFailure() {
        val roll = synchronized(random) { random.nextDouble() }
        var threshold = config.timeoutRate
        if (roll < threshold) throw PspException.Timeout()
        threshold += config.serverErrorRate
        if (roll < threshold) throw PspException.Server(503)
        threshold += config.rateLimitRate
        if (roll < threshold) throw PspException.RateLimited(1_000)
    }
}
