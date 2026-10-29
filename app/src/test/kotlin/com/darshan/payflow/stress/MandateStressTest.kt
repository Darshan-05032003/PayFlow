package com.darshan.payflow.stress

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.local.PayFlowDatabase
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.repository.MandateRepositoryImpl
import com.darshan.payflow.data.repository.PaymentRepositoryImpl
import com.darshan.payflow.domain.model.HistoryFilter
import com.darshan.payflow.domain.model.MandateAction
import com.darshan.payflow.domain.model.MandateFrequency
import com.darshan.payflow.domain.model.MandateStatus
import com.darshan.payflow.domain.usecase.CreateMandateRequest
import com.darshan.payflow.domain.usecase.CreateMandateResult
import com.darshan.payflow.domain.usecase.CreateMandateUseCase
import com.darshan.payflow.domain.usecase.ExecuteMandateResult
import com.darshan.payflow.domain.usecase.ExecuteMandateUseCase
import com.darshan.payflow.domain.usecase.UpdateMandateUseCase
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
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

/** Feature 5 stress: 10,000 debit attempts against 100 mandates' rules; 1,000 racing pause/resume/revoke actions. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MandateStressTest {
    private lateinit var db: PayFlowDatabase
    private val today = FIXED_INSTANT.atZone(ZoneOffset.UTC).toLocalDate()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PayFlowDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun tenThousandDebitAttemptsNeverBreakTheRules() = runBlocking {
        val psp = FakePsp(fixedClock)
        val mandates = MandateRepositoryImpl(db.mandateDao())
        val payments = PaymentRepositoryImpl(db.paymentDao(), fixedClock)
        val counter = AtomicInteger()
        val create = CreateMandateUseCase(mandates, psp, fixedClock, ZoneOffset.UTC) { "m${counter.incrementAndGet()}" }
        val ids = (1..100).map { i ->
            (create(CreateMandateRequest("merchant$i@okbank", "Merchant $i", 1_000L * i, MandateFrequency.entries[i % MandateFrequency.entries.size], today, today.plusYears(1), "")) as CreateMandateResult.Created).mandate.id
        }
        val payCounter = AtomicInteger()
        val execute = ExecuteMandateUseCase(mandates, payments, psp, fixedClock, ZoneOffset.UTC) { "p${payCounter.incrementAndGet()}" }
        val outcomes = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val ms = measureTimeMillis {
            (0 until 10_000).map { n ->
                async(Dispatchers.IO) {
                    val id = ids[n % 100]
                    val amount = if (n % 7 == 0) 1_000L * (n % 100 + 1) + 1 else 500L // every 7th attempt exceeds the cap
                    val r = execute(id, amount)
                    val key = if (r is ExecuteMandateResult.Rejected) "Rejected.${r.check}" else r::class.simpleName!!
                    outcomes.getOrPut(key) { AtomicInteger() }.incrementAndGet()
                }
            }.awaitAll()
        }
        val all = mandates.observeAll().first()
        val executed = outcomes["Executed"]!!.get()
        val successPayments = payments.observeHistory(HistoryFilter.SUCCESS, "").first().size
        println("STRESS [mandate-10k] total=${ms}ms outcomes=$outcomes successPayments=$successPayments")
        // One debit per period: AS_PRESENTED mandates (every 6th) may run many times, the others exactly once.
        all.forEach { m -> if (m.frequency != MandateFrequency.AS_PRESENTED) assertThat(m.executionCount).isEqualTo(1) }
        assertThat(executed).isEqualTo(all.sumOf { it.executionCount })
        assertThat(successPayments).isEqualTo(executed)
        assertThat(outcomes["Rejected.AmountExceedsMax"]!!.get()).isAtLeast(1_000)
    }

    @Test
    fun thousandRacingLifecycleActionsStayConsistent() = runBlocking {
        val psp = FakePsp(fixedClock)
        val mandates = MandateRepositoryImpl(db.mandateDao())
        val create = CreateMandateUseCase(mandates, psp, fixedClock, ZoneOffset.UTC) { "race" }
        create(CreateMandateRequest("netflix@okbank", "Netflix", 10_000, MandateFrequency.MONTHLY, today, today.plusYears(1), ""))
        val update = UpdateMandateUseCase(mandates, psp, fixedClock)
        val actions = listOf(MandateAction.PAUSE, MandateAction.RESUME, MandateAction.PAUSE, MandateAction.RESUME, MandateAction.REVOKE)
        val ms = measureTimeMillis {
            (0 until 1_000).map { i -> async(Dispatchers.IO) { update("race", actions[i % actions.size]) } }.awaitAll()
        }
        val local = mandates.getById("race")!!.status
        val remote = psp.mandates.values.single().status
        println("STRESS [mandate-race-1000] total=${ms}ms local=$local remote=$remote")
        assertThat(local).isEqualTo(MandateStatus.REVOKED) // revoke is terminal and 200 of the actions were revokes
        assertThat(remote).isEqualTo(local)
    }
}
