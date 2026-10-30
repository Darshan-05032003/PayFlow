package com.darshan.payflow.stress

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.checkoutkit.core.CheckoutConfig
import com.darshan.checkoutkit.core.CheckoutKit
import com.darshan.checkoutkit.core.CheckoutRequest
import com.darshan.checkoutkit.core.CheckoutResult
import com.darshan.checkoutkit.core.PaymentMethod
import com.darshan.checkoutkit.core.PendingPolicy
import com.darshan.checkoutkit.core.retry.RetryPolicy
import com.darshan.payflow.data.checkout.CheckoutMerchant
import com.darshan.payflow.data.checkout.PayFlowPaymentGateway
import com.darshan.payflow.data.local.PayFlowDatabase
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.psp.FakePspConfig
import com.darshan.payflow.data.repository.PaymentRepositoryImpl
import com.darshan.payflow.domain.model.HistoryFilter
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.usecase.CheckoutOrder
import com.darshan.payflow.domain.usecase.RecordCheckoutResultUseCase
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
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Feature 8 stress: 1,000 concurrent CheckoutKit sessions through the PayFlow gateway adapter, every outcome recorded once in Room. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CheckoutKitStressTest {
    private lateinit var db: PayFlowDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PayFlowDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun thousandConcurrentCheckoutSessions() = runBlocking {
        val psp = FakePsp(fixedClock, FakePspConfig(pendingPolls = 1, failureRate = 0.2, timeoutRate = 0.05, serverErrorRate = 0.05), seed = 8)
        val merchant = CheckoutMerchant("payflow.store@okbank", "PayFlow Store")
        val gateway = PayFlowPaymentGateway(psp, merchant)
        val kit = CheckoutKit(
            gateway,
            CheckoutConfig(
                retryPolicy = RetryPolicy(maxAttempts = 3, initialDelay = 1.milliseconds, maxDelay = 5.milliseconds, attemptTimeout = 5.seconds),
                pendingPolicy = PendingPolicy(initialInterval = 1.milliseconds, maxInterval = 5.milliseconds, maxPolls = 6),
            ),
        )
        val repo = PaymentRepositoryImpl(db.paymentDao(), fixedClock)
        val counter = AtomicInteger()
        val record = RecordCheckoutResultUseCase(repo, fixedClock, { "ck${counter.incrementAndGet()}" }) { gateway.txnRefForOrder(it) }
        val outcomes = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val ms = measureTimeMillis {
            (1..1_000).map { i ->
                async(Dispatchers.IO) {
                    val order = CheckoutOrder("PF-S$i", 100L + i, "item $i", merchant.vpa, merchant.name)
                    val result = kit.createSession(CheckoutRequest(order.orderId, order.amountPaise, description = order.description)).submit(PaymentMethod.Upi("payer$i@okbank"))
                    outcomes.getOrPut(result::class.simpleName!!) { AtomicInteger() }.incrementAndGet()
                    record(order, result)
                }
            }.awaitAll()
        }
        val summary = repo.observeSummary().first()
        val all = repo.observeHistory(HistoryFilter.ALL, "").first()
        println("STRESS [checkoutkit-1000] total=${ms}ms outcomes=$outcomes recorded=${all.size} success=${summary.success} failed=${summary.failed} pending=${summary.pending} pspInitiate=${psp.initiateCalls} pspStatus=${psp.statusCalls}")
        assertThat(all.size).isEqualTo(1_000)
        assertThat(all.map { it.txnRef }.toSet()).hasSize(1_000)
        // Every SUCCESS row must correspond to an order the PSP actually captured for the same amount.
        all.filter { it.status == PaymentStatus.SUCCESS }.forEach { p -> assertThat(psp.orders[p.txnRef]!!.amountPaise).isEqualTo(p.amountPaise) }
        assertThat(summary.success).isEqualTo(outcomes["Success"]!!.get())
    }
}
