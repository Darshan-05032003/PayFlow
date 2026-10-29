package com.darshan.payflow.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.repository.MandateRepositoryImpl
import com.darshan.payflow.domain.model.Mandate
import com.darshan.payflow.domain.model.MandateFrequency
import com.darshan.payflow.domain.model.MandateStatus
import com.darshan.payflow.testutil.FIXED_INSTANT
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
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MandateDaoTest {
    private lateinit var db: PayFlowDatabase
    private lateinit var repo: MandateRepositoryImpl

    private fun mandate(id: String, end: LocalDate = LocalDate.of(2027, 1, 1)) = Mandate(
        id, "UMN$id", "netflix@okbank", "Netflix", 64_900, MandateFrequency.MONTHLY, LocalDate.of(2026, 10, 29), end,
        MandateStatus.ACTIVE, "", FIXED_INSTANT, FIXED_INSTANT, null, 0,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PayFlowDatabase::class.java).allowMainThreadQueries().build()
        repo = MandateRepositoryImpl(db.mandateDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `round trip, unique umn and observe`() = runTest {
        repo.create(mandate("a"))
        assertThat(repo.getById("a")).isEqualTo(mandate("a"))
        assertThat(repo.observeById("a").first()).isEqualTo(mandate("a"))
        assertThat(runCatching { repo.create(mandate("b").copy(umn = "UMNa")) }.isFailure).isTrue()
        assertThat(repo.observeAll().first()).hasSize(1)
    }

    @Test
    fun `status cas and execution counter are atomic`() = runTest {
        repo.create(mandate("a"))
        val wins = (1..50).map { async(Dispatchers.IO) { repo.compareAndSetStatus("a", MandateStatus.ACTIVE, MandateStatus.PAUSED, FIXED_INSTANT) } }.awaitAll()
        assertThat(wins.count { it }).isEqualTo(1)
        val execs = (1..50).map { async(Dispatchers.IO) { repo.recordExecution("a", FIXED_INSTANT, expectedCount = 0) } }.awaitAll()
        assertThat(execs.count { it }).isEqualTo(1)
        assertThat(repo.getById("a")!!.executionCount).isEqualTo(1)
        assertThat(repo.getById("a")!!.lastExecutedAt).isEqualTo(FIXED_INSTANT)
    }

    @Test
    fun `expire ended mandates skips terminal ones`() = runTest {
        repo.create(mandate("old", end = LocalDate.of(2026, 10, 1)))
        repo.create(mandate("live"))
        repo.create(mandate("revoked", end = LocalDate.of(2026, 10, 1)).copy(status = MandateStatus.REVOKED))
        assertThat(repo.expireEndedBefore(LocalDate.of(2026, 10, 29), FIXED_INSTANT)).isEqualTo(1)
        assertThat(repo.getById("old")!!.status).isEqualTo(MandateStatus.EXPIRED)
        assertThat(repo.getById("live")!!.status).isEqualTo(MandateStatus.ACTIVE)
        assertThat(repo.getById("revoked")!!.status).isEqualTo(MandateStatus.REVOKED)
    }

    @Test
    fun `payments can be listed per mandate`() = runTest {
        val payments = db.paymentDao()
        payments.insert(com.darshan.payflow.testutil.testPayment("p1", txnRef = "T1").copy(mandateId = "a").toEntity())
        payments.insert(com.darshan.payflow.testutil.testPayment("p2", txnRef = "T2").copy(mandateId = "b").toEntity())
        payments.insert(com.darshan.payflow.testutil.testPayment("p3", txnRef = "T3").toEntity())
        assertThat(payments.observeByMandate("a").first().map { it.id }).containsExactly("p1")
    }
}
