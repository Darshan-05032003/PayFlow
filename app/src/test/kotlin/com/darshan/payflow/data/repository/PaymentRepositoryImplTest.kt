package com.darshan.payflow.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.local.PayFlowDatabase
import com.darshan.payflow.domain.model.HistoryFilter
import com.darshan.payflow.domain.model.IllegalTransitionException
import com.darshan.payflow.domain.model.PaymentEvent
import com.darshan.payflow.domain.model.PaymentNotFoundException
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.fixedClock
import com.darshan.payflow.testutil.testPayment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PaymentRepositoryImplTest {
    private lateinit var db: PayFlowDatabase
    private lateinit var repo: PaymentRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PayFlowDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = PaymentRepositoryImpl(db.paymentDao(), fixedClock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `create then apply events updates the stored payment`() = runTest {
        repo.create(testPayment("a"))
        val later = Clock.fixed(FIXED_INSTANT.plusSeconds(5), ZoneOffset.UTC)
        val repoLater = PaymentRepositoryImpl(db.paymentDao(), later)

        val pending = repoLater.applyEvent("a", PaymentEvent.Initiate).getOrThrow()
        assertThat(pending.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(pending.updatedAt).isEqualTo(FIXED_INSTANT.plusSeconds(5))

        val success = repoLater.applyEvent("a", PaymentEvent.Confirm("psp-1", "upi-1")).getOrThrow()
        assertThat(success.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(repo.getById("a")).isEqualTo(success)
        assertThat(repo.observeById("a").first()).isEqualTo(success)
    }

    @Test
    fun `illegal transition is rejected and leaves the row untouched`() = runTest {
        repo.create(testPayment("a"))
        val result = repo.applyEvent("a", PaymentEvent.Confirm("psp", null))
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalTransitionException::class.java)
        assertThat(repo.getById("a")!!.status).isEqualTo(PaymentStatus.CREATED)
        assertThat(repo.getById("a")!!.pspTxnId).isNull()
    }

    @Test
    fun `unknown payment id fails with PaymentNotFoundException`() = runTest {
        val result = repo.applyEvent("ghost", PaymentEvent.Initiate)
        assertThat(result.exceptionOrNull()).isInstanceOf(PaymentNotFoundException::class.java)
    }

    @Test
    fun `concurrent confirmations of one pending payment succeed exactly once`() = runTest {
        repo.create(testPayment("a", status = PaymentStatus.PENDING))
        val results = (1..50).map { i ->
            async(Dispatchers.IO) { repo.applyEvent("a", PaymentEvent.Confirm("psp-$i", null)) }
        }.awaitAll()
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.count { it.exceptionOrNull() is IllegalTransitionException }).isEqualTo(49)
        assertThat(repo.getById("a")!!.status).isEqualTo(PaymentStatus.SUCCESS)
    }

    @Test
    fun `history, recent and summary flows map entities to domain`() = runTest {
        repo.create(testPayment("a", status = PaymentStatus.SUCCESS, createdAt = FIXED_INSTANT.minusSeconds(1)))
        repo.create(testPayment("b", status = PaymentStatus.FAILED, payeeName = "Zed"))
        assertThat(repo.observeHistory(HistoryFilter.ALL, "").first().map { it.id }).containsExactly("b", "a").inOrder()
        assertThat(repo.observeHistory(HistoryFilter.FAILED, "").first().map { it.id }).containsExactly("b")
        assertThat(repo.observeHistory(HistoryFilter.ALL, "zed").first().map { it.id }).containsExactly("b")
        assertThat(repo.observeRecent(1).first().map { it.id }).containsExactly("b")
        val summary = repo.observeSummary().first()
        assertThat(summary.total).isEqualTo(2)
        assertThat(summary.success).isEqualTo(1)
        assertThat(summary.failed).isEqualTo(1)
        val recentOnly = repo.observeSummarySince(FIXED_INSTANT).first()
        assertThat(recentOnly.total).isEqualTo(1)
        assertThat(recentOnly.failed).isEqualTo(1)
        assertThat(recentOnly.success).isEqualTo(0)
    }

    @Test
    fun `duplicate create fails`() = runTest {
        repo.create(testPayment("a"))
        assertThat(runCatching { repo.create(testPayment("a")) }.isFailure).isTrue()
    }

    @Test
    fun `control characters in the search cannot widen the match (NUL terminates a LIKE pattern in sqlite)`() = runTest {
        repo.create(testPayment("a", payeeName = "Alice"))
        assertThat(repo.observeHistory(HistoryFilter.ALL, "\u0000zzz").first()).isEmpty()
        assertThat(repo.observeHistory(HistoryFilter.ALL, "\uFEFFzzz").first()).isEmpty()
        assertThat(repo.observeHistory(HistoryFilter.ALL, "\u202Ezzz").first()).isEmpty()
        assertThat(repo.observeHistory(HistoryFilter.ALL, "ali\u0007ce").first().map { it.id }).containsExactly("a")
        // Only-invisible input is the same as no search text at all.
        assertThat(repo.observeHistory(HistoryFilter.ALL, "\u0000").first()).hasSize(1)
        assertThat(PaymentRepositoryImpl.sanitizeQuery("\u0000 a\tb\uFEFF \u001F")).isEqualTo("ab")
    }
}
