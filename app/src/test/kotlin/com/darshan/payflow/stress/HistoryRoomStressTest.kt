package com.darshan.payflow.stress

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.local.PayFlowDatabase
import com.darshan.payflow.data.local.toEntity
import com.darshan.payflow.data.repository.PaymentRepositoryImpl
import com.darshan.payflow.domain.model.HistoryFilter
import com.darshan.payflow.domain.model.PaymentEvent
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.fixedClock
import com.darshan.payflow.testutil.testPayment
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
import kotlin.system.measureTimeMillis

/** Feature 1 stress: 100k transactions in Room, 1,000 concurrent payments through the state machine. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HistoryRoomStressTest {
    private lateinit var db: PayFlowDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PayFlowDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun usedMb() = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / (1024 * 1024) }

    @Test
    fun hundredThousandTransactionsInRoom() = runBlocking {
        val dao = db.paymentDao()
        val statuses = PaymentStatus.entries
        val before = usedMb()
        val insertMs = measureTimeMillis {
            (0 until 100).forEach { batch ->
                val rows = (0 until 1_000).map { i ->
                    val n = batch * 1_000 + i
                    testPayment(
                        id = "p$n", txnRef = "PF$n", status = statuses[n % statuses.size],
                        payeeName = "Payee ${n % 500}", amountPaise = 100L + n,
                        createdAt = FIXED_INSTANT.plusSeconds(n.toLong()),
                    ).toEntity()
                }
                dao.insertAll(rows)
            }
        }
        assertThat(dao.count()).isEqualTo(100_000)

        val allNames = statuses.map { it.name }
        val recentMs = measureTimeMillis { assertThat(dao.observeRecent(5).first()).hasSize(5) }
        val filterMs = measureTimeMillis {
            val failed = dao.observeHistory(listOf("FAILED"), "").first()
            assertThat(failed.size).isEqualTo((0 until 100_000).count { statuses[it % statuses.size] == PaymentStatus.FAILED })
        }
        val searchMs = measureTimeMillis {
            assertThat(dao.observeHistory(allNames, "Payee 42").first().size).isGreaterThan(0)
        }
        val summaryMs = measureTimeMillis { assertThat(dao.observeSummary().first().total).isEqualTo(100_000) }
        val fullMs = measureTimeMillis { assertThat(dao.observeHistory(allNames, "").first()).hasSize(100_000) }
        val pageMs = measureTimeMillis { assertThat(dao.historyPage(allNames, "", 50, 0)).hasSize(50) }
        println(
            "STRESS [room-100k] insert=${insertMs}ms recent5=${recentMs}ms filterFailed=${filterMs}ms " +
                "search=${searchMs}ms summary=${summaryMs}ms fullList=${fullMs}ms page50=${pageMs}ms memDelta=${usedMb() - before}MB",
        )
        assertThat(recentMs).isLessThan(2_000)
        assertThat(summaryMs).isLessThan(2_000)
    }

    @Test
    fun thousandConcurrentPaymentsThroughTheStateMachine() = runBlocking {
        val repo = PaymentRepositoryImpl(db.paymentDao(), fixedClock)
        val ms = measureTimeMillis {
            (1..1_000).map { i ->
                async(Dispatchers.IO) {
                    val id = "c$i"
                    repo.create(testPayment(id, txnRef = "T$i"))
                    repo.applyEvent(id, PaymentEvent.Initiate).getOrThrow()
                    if (i % 10 == 0) repo.applyEvent(id, PaymentEvent.Fail("declined")).getOrThrow()
                    else repo.applyEvent(id, PaymentEvent.Confirm("psp$i", "upi$i")).getOrThrow()
                }
            }.awaitAll()
        }
        val summary = repo.observeSummary().first()
        println("STRESS [state-machine-1000] total=${ms}ms success=${summary.success} failed=${summary.failed}")
        assertThat(summary.total).isEqualTo(1_000)
        assertThat(summary.success).isEqualTo(900)
        assertThat(summary.failed).isEqualTo(100)
        assertThat(repo.observeHistory(HistoryFilter.PENDING, "").first()).isEmpty()
    }
}
