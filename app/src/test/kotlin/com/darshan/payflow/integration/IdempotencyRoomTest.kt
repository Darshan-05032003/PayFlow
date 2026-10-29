package com.darshan.payflow.integration

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.local.PayFlowDatabase
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.repository.PaymentRepositoryImpl
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.UpiPayRequest
import com.darshan.payflow.domain.usecase.PayViaUpiIntentUseCase
import com.darshan.payflow.testutil.fixedClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Idempotency against the real Room unique index: concurrent submits and a simulated process death. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class IdempotencyRoomTest {
    private lateinit var db: PayFlowDatabase
    private val request = UpiPayRequest("alice@okbank", "Alice", 12_300, "Lunch", "PFROOM1")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PayFlowDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun hundredConcurrentSubmitsWithOneKeyCreateOnePayment() = runBlocking {
        val psp = FakePsp(fixedClock)
        val repo = PaymentRepositoryImpl(db.paymentDao(), fixedClock)
        val counter = AtomicInteger()
        val pay = PayViaUpiIntentUseCase(repo, psp, fixedClock) { "p${counter.incrementAndGet()}" }
        val results = (1..100).map { async(Dispatchers.IO) { pay(request, PaymentMethod.UPI_INTENT, idempotencyKey = "same-key") } }.awaitAll()
        assertThat(results.all { it.isSuccess }).isTrue()
        assertThat(results.map { it.getOrThrow().paymentId }.toSet()).hasSize(1)
        assertThat(db.paymentDao().count()).isEqualTo(1)
        assertThat(psp.initiateCalls).isEqualTo(1)
    }

    @Test
    fun processDeathBetweenStoreAndPspCallIsRecoveredByTheKey() = runBlocking {
        val psp = FakePsp(fixedClock)
        // "Before" death: the row with the key exists but the PSP was never called (status CREATED).
        val repoBefore = PaymentRepositoryImpl(db.paymentDao(), fixedClock)
        repoBefore.create(com.darshan.payflow.testutil.testPayment("p-dead", txnRef = "PFROOM1", idempotencyKey = "k-dead", amountPaise = 12_300))
        // "After" death: a fresh repository and use case over the same database.
        val repoAfter = PaymentRepositoryImpl(db.paymentDao(), fixedClock)
        val launch = PayViaUpiIntentUseCase(repoAfter, psp, fixedClock) { "p-new" }.invoke(request, PaymentMethod.UPI_INTENT, idempotencyKey = "k-dead").getOrThrow()
        assertThat(launch.paymentId).isEqualTo("p-dead")
        assertThat(repoAfter.getById("p-dead")!!.status).isEqualTo(com.darshan.payflow.domain.model.PaymentStatus.PENDING)
        assertThat(db.paymentDao().count()).isEqualTo(1)
        assertThat(psp.initiateCalls).isEqualTo(1)
        assertThat(repoAfter.findByIdempotencyKey("k-dead")!!.id).isEqualTo("p-dead")
    }
}
