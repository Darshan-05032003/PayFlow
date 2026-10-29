package com.darshan.payflow.stress

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.local.PayFlowDatabase
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.psp.FakePspConfig
import com.darshan.payflow.data.repository.CollectRepositoryImpl
import com.darshan.payflow.data.repository.PaymentRepositoryImpl
import com.darshan.payflow.domain.model.CollectStatus
import com.darshan.payflow.domain.model.HistoryFilter
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.usecase.CollectActionResult
import com.darshan.payflow.domain.usecase.RefreshCollectRequestsUseCase
import com.darshan.payflow.domain.usecase.RespondToCollectUseCase
import com.darshan.payflow.domain.usecase.VerifyVpaUseCase
import com.darshan.payflow.domain.usecase.VpaVerifyResult
import com.darshan.payflow.testutil.FIXED_INSTANT
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
import java.time.Clock
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

/** Feature 4 stress: 10k VPA verifications under 429/5xx; 1,000 collect requests answered concurrently; one request answered 200x. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CollectAndVerifyStressTest {
    private lateinit var db: PayFlowDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PayFlowDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun tenThousandVpaVerificationsUnderRateLimiting() = runBlocking {
        val psp = FakePsp(fixedClock, FakePspConfig(rateLimitRate = 0.2, serverErrorRate = 0.1), seed = 3)
        val verify = VerifyVpaUseCase(psp)
        val counts = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val ms = measureTimeMillis {
            (1..10_000).map { i ->
                async(Dispatchers.Default) {
                    val r = verify(listOf("alice@okbank", "user$i@ybl", "unknown@okbank", "bad vpa")[i % 4])
                    counts.getOrPut(r::class.simpleName!!) { AtomicInteger() }.incrementAndGet()
                }
            }.awaitAll()
        }
        println("STRESS [vpa-verify-10k] total=${ms}ms results=$counts pspCalls=${psp.verifyCalls}")
        assertThat(counts.values.sumOf { it.get() }).isEqualTo(10_000)
        assertThat(counts["InvalidFormat"]!!.get()).isEqualTo(2_500) // never hit the PSP
        assertThat(psp.verifyCalls).isEqualTo(7_500)
    }

    @Test
    fun thousandCollectRequestsAnsweredConcurrently() = runBlocking {
        val psp = FakePsp(fixedClock, FakePspConfig(timeoutRate = 0.05), seed = 5)
        repeat(1_000) { i ->
            psp.simulateIncomingCollect("req-$i", "shop$i@okbank", "Shop $i", 100L + i, "", ttlMillis = if (i % 10 == 0) 1_000 else 600_000)
        }
        val payments = PaymentRepositoryImpl(db.paymentDao(), fixedClock)
        val collects = CollectRepositoryImpl(db.collectDao())
        val later = Clock.fixed(FIXED_INSTANT.plusSeconds(5), ZoneOffset.UTC) // the 10 % short-TTL requests are now expired
        RefreshCollectRequestsUseCase(collects, psp, later).invoke().getOrThrow()
        val counter = AtomicInteger()
        val respond = RespondToCollectUseCase(collects, payments, psp, later) { "p${counter.incrementAndGet()}" }
        val outcomes = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val ms = measureTimeMillis {
            (0 until 1_000).map { i ->
                async(Dispatchers.IO) {
                    val r = respond("req-$i", approve = i % 3 != 0)
                    outcomes.getOrPut(r::class.simpleName!!) { AtomicInteger() }.incrementAndGet()
                }
            }.awaitAll()
        }
        val statuses = collects.observeAll().first().groupingBy { it.status }.eachCount()
        val paid = payments.observeHistory(HistoryFilter.SUCCESS, "").first().size
        val pendingPayments = payments.observeHistory(HistoryFilter.PENDING, "").first().size
        println("STRESS [collect-1000] total=${ms}ms outcomes=$outcomes statuses=$statuses successfulPayments=$paid pendingPayments=$pendingPayments")
        assertThat(statuses[CollectStatus.EXPIRED]).isEqualTo(100)
        // An approval the PSP accepted whose status check then timed out stays PENDING (never FAILED): approved == success + pending.
        assertThat(outcomes["Approved"]!!.get()).isEqualTo(paid + pendingPayments)
        // every APPROVED request points at exactly one payment that is SUCCESS or still PENDING
        val approved = collects.observeAll().first().filter { it.status == CollectStatus.APPROVED }
        assertThat(approved.map { it.paymentId }.toSet()).hasSize(approved.size)
        approved.forEach { assertThat(payments.getById(it.paymentId!!)!!.status).isIn(listOf(PaymentStatus.SUCCESS, PaymentStatus.PENDING)) }
        // Unavailable answers left their request PENDING with a FAILED (never-opened) payment, so they can be retried.
        assertThat(statuses[CollectStatus.PENDING]).isEqualTo(outcomes["Unavailable"]?.get() ?: 0)
    }

    @Test
    fun oneRequestAnsweredTwoHundredTimesConcurrentlyCreatesOnePayment() = runBlocking {
        val psp = FakePsp(fixedClock).also { it.simulateIncomingCollect("hot", "shop@okbank", "Shop", 5_000, "", 600_000) }
        val payments = PaymentRepositoryImpl(db.paymentDao(), fixedClock)
        val collects = CollectRepositoryImpl(db.collectDao())
        RefreshCollectRequestsUseCase(collects, psp, fixedClock).invoke().getOrThrow()
        val counter = AtomicInteger()
        val respond = RespondToCollectUseCase(collects, payments, psp, fixedClock) { "p${counter.incrementAndGet()}" }
        val results = (1..200).map { i -> async(Dispatchers.IO) { respond("hot", approve = i % 2 == 0) } }.awaitAll()
        val all = payments.observeHistory(HistoryFilter.ALL, "").first()
        println("STRESS [collect-hot-200] approved=${results.count { it is CollectActionResult.Approved }} declined=${results.count { it is CollectActionResult.Declined }} already=${results.count { it is CollectActionResult.AlreadyAnswered }} payments=${all.size}")
        assertThat(results.count { it is CollectActionResult.Approved || it is CollectActionResult.Declined }).isEqualTo(1)
        assertThat(all.size).isAtMost(1)
    }
}
