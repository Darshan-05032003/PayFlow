package com.darshan.payflow.data.psp

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
    private val _initiateCalls = AtomicInteger()
    private val _statusCalls = AtomicInteger()
    val initiateCalls: Int get() = _initiateCalls.get()
    val statusCalls: Int get() = _statusCalls.get()

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

    /** Acts as the payer / remitter bank: settles a pending order. */
    fun settle(txnRef: String, approved: Boolean) {
        orders[txnRef]?.finalOutcome = if (approved) FakeOutcome.SUCCESS else FakeOutcome.FAILED
    }

    private suspend fun simulateLatency() {
        if (config.latencyMillis > 0) delay(config.latencyMillis)
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
