package com.darshan.payflow.stress

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.core.BackoffPolicy
import com.darshan.payflow.data.local.PayFlowDatabase
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.psp.FakePspConfig
import com.darshan.payflow.data.repository.PaymentRepositoryImpl
import com.darshan.payflow.domain.model.HistoryFilter
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.UpiPayRequest
import com.darshan.payflow.domain.usecase.ConfirmPaymentUseCase
import com.darshan.payflow.domain.usecase.ConfirmResult
import com.darshan.payflow.domain.usecase.PayViaUpiIntentUseCase
import com.darshan.payflow.domain.usecase.PollPaymentStatusUseCase
import com.darshan.payflow.testutil.fixedClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/** Feature 6 stress: same idempotency key 1,000x concurrently; a polling storm against a flaky PSP; backoff jitter histogram. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PollingAndIdempotencyStressTest {
    private lateinit var db: PayFlowDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PayFlowDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun sameIdempotencyKeyThousandTimesConcurrentlyCreatesExactlyOnePayment() = runBlocking {
        val psp = FakePsp(fixedClock)
        val repo = PaymentRepositoryImpl(db.paymentDao(), fixedClock)
        val counter = AtomicInteger()
        val pay = PayViaUpiIntentUseCase(repo, psp, fixedClock) { "p${counter.incrementAndGet()}" }
        val request = UpiPayRequest("alice@okbank", "Alice", 50_000, "Rent", "PFSTRESSKEY")
        val ms = measureTimeMillis {
            val results = (1..1_000).map { async(Dispatchers.IO) { pay(request, PaymentMethod.UPI_INTENT, idempotencyKey = "one-key") } }.awaitAll()
            assertThat(results.count { it.isSuccess }).isEqualTo(1_000)
            assertThat(results.map { it.getOrThrow().paymentId }.toSet()).hasSize(1)
        }
        println("STRESS [idempotency-1000] total=${ms}ms payments=${db.paymentDao().count()} pspInitiateCalls=${psp.initiateCalls}")
        assertThat(db.paymentDao().count()).isEqualTo(1)
        assertThat(psp.initiateCalls).isEqualTo(1)
    }

    @Test
    fun pollingStormAgainstAFlakyPsp() = runBlocking {
        val psp = FakePsp(fixedClock, FakePspConfig(pendingPolls = 2, timeoutRate = 0.15, serverErrorRate = 0.1, rateLimitRate = 0.05, failureRate = 0.2), seed = 11)
        val repo = PaymentRepositoryImpl(db.paymentDao(), fixedClock)
        val counter = AtomicInteger()
        val pay = PayViaUpiIntentUseCase(repo, psp, fixedClock) { "p${counter.incrementAndGet()}" }
        val ids = (1..1_000).map { i ->
            async(Dispatchers.IO) { pay(UpiPayRequest("u$i@okbank", "U$i", 100L + i, "", "S$i"), PaymentMethod.UPI_INTENT, idempotencyKey = "k$i") }
        }.awaitAll().mapNotNull { it.getOrNull()?.paymentId }
        // Real-time policy with tiny delays so the storm finishes quickly but the shape (backoff, jitter, Retry-After cap) is real.
        val policy = BackoffPolicy(initialDelayMillis = 2, maxDelayMillis = 20, multiplier = 2.0, jitterFactor = 1.0, maxAttempts = 12)
        val poller = PollPaymentStatusUseCase(ConfirmPaymentUseCase(repo, psp, fixedClock), policy, Random(3))
        val before = psp.statusCalls
        val outcomes = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val ms = measureTimeMillis {
            // Each payment is polled by 3 racing callers (screen, detail, reconciler): single flight must collapse them.
            ids.flatMap { id -> List(3) { id } }.map { id ->
                async(Dispatchers.IO) { outcomes.getOrPut(poller(id)::class.simpleName!!) { AtomicInteger() }.incrementAndGet() }
            }.awaitAll()
        }
        val summary = repo.observeSummary().first()
        println("STRESS [polling-storm-1000x3] total=${ms}ms initiated=${ids.size} statusCalls=${psp.statusCalls - before} outcomes=$outcomes final=success:${summary.success} failed:${summary.failed} pending:${summary.pending}")
        assertThat(summary.pending).isAtMost(ids.size / 10) // the few that exhausted 12 attempts stay honestly pending
        assertThat(psp.statusCalls - before).isLessThan(ids.size * 12) // collapsed, not 3 pollers x attempts
        assertThat(repo.observeHistory(HistoryFilter.ALL, "").first().none { it.status == com.darshan.payflow.domain.model.PaymentStatus.CREATED }).isTrue()
    }

    @Test
    fun jitterPreventsAThunderingHerd() {
        val noJitter = BackoffPolicy(initialDelayMillis = 1_000, maxDelayMillis = 30_000, multiplier = 2.0, jitterFactor = 0.0, maxAttempts = 5)
        val fullJitter = noJitter.copy(jitterFactor = 1.0)
        fun peakBucket(policy: BackoffPolicy): Int {
            val random = Random(99)
            val buckets = HashMap<Long, Int>()
            repeat(10_000) { buckets.merge(policy.delayMillis(attempt = 3, random = random) / 10, 1, Int::plus) } // 10 ms buckets
            return buckets.values.max()
        }
        val herd = peakBucket(noJitter)
        val spread = peakBucket(fullJitter)
        println("STRESS [backoff-jitter-10k] peakRetriesIn10ms noJitter=$herd fullJitter=$spread")
        assertThat(herd).isEqualTo(10_000)
        assertThat(spread).isLessThan(100)
    }
}
