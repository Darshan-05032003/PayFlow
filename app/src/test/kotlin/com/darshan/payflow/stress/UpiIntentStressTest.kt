package com.darshan.payflow.stress

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.local.PayFlowDatabase
import com.darshan.payflow.data.psp.FakeOutcome
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.psp.FakePspConfig
import com.darshan.payflow.data.psp.PspPaymentStatus
import com.darshan.payflow.data.repository.PaymentRepositoryImpl
import com.darshan.payflow.data.upi.UpiResponseParser
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.UpiPayRequest
import com.darshan.payflow.domain.usecase.ConfirmPaymentUseCase
import com.darshan.payflow.domain.usecase.ConfirmResult
import com.darshan.payflow.domain.usecase.PayViaUpiIntentUseCase
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

/** Feature 2 stress: 1,000 concurrent intent payments with a flaky PSP; 10,000 spoofed app responses. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UpiIntentStressTest {
    private lateinit var db: PayFlowDatabase
    private lateinit var repo: PaymentRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PayFlowDatabase::class.java).build()
        repo = PaymentRepositoryImpl(db.paymentDao(), fixedClock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun thousandConcurrentIntentPaymentsAgainstAFlakyPsp() = runBlocking {
        val psp = FakePsp(fixedClock, FakePspConfig(failureRate = 0.2, timeoutRate = 0.1, serverErrorRate = 0.05, pendingPolls = 1), seed = 42)
        val counter = AtomicInteger()
        val pay = PayViaUpiIntentUseCase(repo, psp, fixedClock) { "p${counter.incrementAndGet()}" }
        val confirm = ConfirmPaymentUseCase(repo, psp, fixedClock)
        val outcomes = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val ms = measureTimeMillis {
            (1..1_000).map { i ->
                async(Dispatchers.IO) {
                    val launch = pay(UpiPayRequest("user$i@okbank", "User $i", 100L + i, "", "SR$i"), PaymentMethod.UPI_INTENT)
                    if (launch.isFailure) { outcomes.getOrPut("initiate-failed") { AtomicInteger() }.incrementAndGet(); return@async }
                    var result: ConfirmResult = confirm(launch.getOrThrow().paymentId, null)
                    var attempts = 1
                    while ((result is ConfirmResult.Unresolved || result is ConfirmResult.Pending) && attempts < 8) {
                        result = confirm(launch.getOrThrow().paymentId, null); attempts++
                    }
                    outcomes.getOrPut(result::class.simpleName!!) { AtomicInteger() }.incrementAndGet()
                }
            }.awaitAll()
        }
        // Every stored payment must agree with the PSP's truth.
        var disagreements = 0
        val all = repo.observeHistory(com.darshan.payflow.domain.model.HistoryFilter.ALL, "").first().associateBy { it.txnRef }
        for ((ref, order) in psp.orders) {
            val stored = all.getValue(ref)
            val truth = order.finalOutcome
            if (stored.status == PaymentStatus.SUCCESS && truth != FakeOutcome.SUCCESS) disagreements++
            if (stored.status == PaymentStatus.FAILED && truth == FakeOutcome.SUCCESS && stored.failureReason?.startsWith("PSP") != true) disagreements++
        }
        println("STRESS [intent-1000] total=${ms}ms outcomes=$outcomes statusCalls=${psp.statusCalls} disagreements=$disagreements")
        assertThat(disagreements).isEqualTo(0)
        assertThat(outcomes.values.sumOf { it.get() }).isEqualTo(1_000)
    }

    @Test
    fun tenThousandSpoofedAppResponsesNeverYieldSuccess() = runBlocking {
        val psp = FakePsp(fixedClock, FakePspConfig(outcome = FakeOutcome.FAILED))
        val pay = PayViaUpiIntentUseCase(repo, psp, fixedClock) { "victim" }
        pay(UpiPayRequest("shop@okbank", "Shop", 50_000, "", "VICTIM1"), PaymentMethod.UPI_INTENT).getOrThrow()
        val confirm = ConfirmPaymentUseCase(repo, psp, fixedClock)
        val random = Random(7)
        val ms = measureTimeMillis {
            (1..10_000).map { i ->
                async(Dispatchers.Default) {
                    val raw = listOf(
                        "Status=SUCCESS&txnRef=VICTIM1&txnId=F$i&responseCode=00&ApprovalRefNo=$i",
                        "status=success&TXNREF=VICTIM1",
                        "Status=SUCCESS&txnRef=" + "A".repeat(random.nextInt(1, 5_000)),
                        "Status=SUCCESS%00&txnRef=VICTIM1",
                        "‮Status=SUCCESS&txnRef=VICTIM1",
                    )[i % 5]
                    confirm("victim", UpiResponseParser.parse(raw))
                }
            }.awaitAll()
        }
        val stored = repo.getById("victim")!!
        println("STRESS [spoof-10k] total=${ms}ms finalStatus=${stored.status} statusCalls=${psp.statusCalls}")
        assertThat(stored.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(psp.orders["VICTIM1"]!!.finalOutcome).isEqualTo(FakeOutcome.FAILED)
    }

    @Test
    fun pspMismatchStormNeverConfirms() = runBlocking {
        val psp = object : com.darshan.payflow.data.psp.PspClient {
            val calls = AtomicInteger()
            override suspend fun initiatePayment(request: com.darshan.payflow.data.psp.PspInitiateRequest) = com.darshan.payflow.data.psp.PspInitiateResponse("p", request.txnRef)
            override suspend fun getPaymentStatus(txnRef: String): PspPaymentStatus {
                val n = calls.incrementAndGet()
                return PspPaymentStatus.Success(txnRef, "p$n", "u$n", amountPaise = 1L + (n % 3), payeeVpa = if (n % 2 == 0) "shop@okbank" else "evil@x")
            }
        }
        PayViaUpiIntentUseCase(repo, psp, fixedClock) { "m" }(UpiPayRequest("shop@okbank", "Shop", 50_000, "", "M1"), PaymentMethod.UPI_INTENT).getOrThrow()
        val confirm = ConfirmPaymentUseCase(repo, psp, fixedClock)
        val results = (1..2_000).map { async(Dispatchers.Default) { confirm("m", null) } }.awaitAll()
        println("STRESS [mismatch-2000] mismatches=${results.count { it is ConfirmResult.Mismatch }} finalStatus=${repo.getById("m")!!.status}")
        assertThat(results.all { it is ConfirmResult.Mismatch }).isTrue()
        assertThat(repo.getById("m")!!.status).isEqualTo(PaymentStatus.PENDING)
    }
}
