package com.darshan.payflow.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.domain.model.StatusSource
import com.darshan.payflow.domain.model.PaymentDirection
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PaymentDaoTest {
    private lateinit var db: PayFlowDatabase
    private lateinit var dao: PaymentDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PayFlowDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.paymentDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `insert and read back`() = runTest {
        val entity = testPayment("a").toEntity()
        dao.insert(entity)
        assertThat(dao.getById("a")).isEqualTo(entity)
        assertThat(dao.observeById("a").first()).isEqualTo(entity)
        assertThat(dao.getById("missing")).isNull()
    }

    @Test
    fun `txnRef is unique`() = runTest {
        dao.insert(testPayment("a", txnRef = "same").toEntity())
        val ex = runCatching { dao.insert(testPayment("b", txnRef = "same").toEntity()) }.exceptionOrNull()
        assertThat(ex).isInstanceOf(SQLiteConstraintException::class.java)
    }

    @Test
    fun `idempotency key is unique when present and nullable otherwise`() = runTest {
        dao.insert(testPayment("a", idempotencyKey = "k1").toEntity())
        dao.insert(testPayment("b", idempotencyKey = null).toEntity())
        dao.insert(testPayment("c", idempotencyKey = null).toEntity())
        val ex = runCatching { dao.insert(testPayment("d", idempotencyKey = "k1").toEntity()) }.exceptionOrNull()
        assertThat(ex).isInstanceOf(SQLiteConstraintException::class.java)
        assertThat(dao.count()).isEqualTo(3)
    }

    @Test
    fun `compare and set updates only from the expected status`() = runTest {
        dao.insert(testPayment("a").toEntity())
        val wrong = dao.compareAndSetStatus(
            id = "a", expectedStatus = "PENDING", newStatus = "SUCCESS",
            pspTxnId = "p", upiTxnId = null, refundId = null, failureReason = null, statusSource = "PSP", updatedAt = 1L,
        )
        assertThat(wrong).isEqualTo(0)
        assertThat(dao.getById("a")!!.status).isEqualTo("CREATED")
        val right = dao.compareAndSetStatus(
            id = "a", expectedStatus = "CREATED", newStatus = "PENDING",
            pspTxnId = null, upiTxnId = null, refundId = null, failureReason = null, statusSource = "PSP", updatedAt = 2L,
        )
        assertThat(right).isEqualTo(1)
        assertThat(dao.getById("a")!!.status).isEqualTo("PENDING")
        assertThat(dao.getById("a")!!.updatedAt).isEqualTo(2L)
    }

    @Test
    fun `history is newest first and filterable by status and text`() = runTest {
        dao.insert(testPayment("old", createdAt = FIXED_INSTANT.minusSeconds(100), status = PaymentStatus.SUCCESS, payeeName = "Alice").toEntity())
        dao.insert(testPayment("new", createdAt = FIXED_INSTANT, status = PaymentStatus.FAILED, payeeName = "Bob", payeeVpa = "bob@upi").toEntity())
        dao.insert(testPayment("mid", createdAt = FIXED_INSTANT.minusSeconds(50), status = PaymentStatus.PENDING, payeeName = "Carol", payeeVpa = "carol@upi").toEntity())

        val all = dao.observeHistory(PaymentStatus.entries.map { it.name }, "").first()
        assertThat(all.map { it.id }).containsExactly("new", "mid", "old").inOrder()

        val failed = dao.observeHistory(listOf("FAILED"), "").first()
        assertThat(failed.map { it.id }).containsExactly("new")

        val byName = dao.observeHistory(PaymentStatus.entries.map { it.name }, "ali").first()
        assertThat(byName.map { it.id }).containsExactly("old")

        val byVpa = dao.observeHistory(PaymentStatus.entries.map { it.name }, "bob@").first()
        assertThat(byVpa.map { it.id }).containsExactly("new")

        assertThat(dao.observeRecent(2).first().map { it.id }).containsExactly("new", "mid").inOrder()
    }

    @Test
    fun `summary aggregates in sql and is zero when empty`() = runTest {
        val empty = dao.observeSummary().first()
        assertThat(empty.total).isEqualTo(0)
        assertThat(empty.sentPaise).isEqualTo(0)

        dao.insert(testPayment("a", status = PaymentStatus.SUCCESS, amountPaise = 100).toEntity())
        dao.insert(testPayment("b", status = PaymentStatus.SUCCESS, amountPaise = 300, direction = com.darshan.payflow.domain.model.PaymentDirection.INCOMING).toEntity())
        dao.insert(testPayment("c", status = PaymentStatus.PENDING, amountPaise = 999).toEntity())
        dao.insert(testPayment("d", status = PaymentStatus.FAILED, amountPaise = 999).toEntity())
        dao.insert(testPayment("e", status = PaymentStatus.REFUNDED, amountPaise = 999).toEntity())
        val s = dao.observeSummary().first()
        assertThat(s.total).isEqualTo(5)
        assertThat(s.success).isEqualTo(2)
        assertThat(s.pending).isEqualTo(1)
        assertThat(s.failed).isEqualTo(1)
        assertThat(s.refunded).isEqualTo(1)
        assertThat(s.sentPaise).isEqualTo(100)
        assertThat(s.receivedPaise).isEqualTo(300)
    }

    @Test
    fun `summary since a cut-off only counts payments created at or after it`() = runTest {
        val cutOff = FIXED_INSTANT
        dao.insert(testPayment("old", status = PaymentStatus.SUCCESS, amountPaise = 100, createdAt = cutOff.minusSeconds(1)).toEntity())
        dao.insert(testPayment("edge", status = PaymentStatus.SUCCESS, amountPaise = 200, createdAt = cutOff).toEntity())
        dao.insert(testPayment("new", status = PaymentStatus.PENDING, amountPaise = 300, createdAt = cutOff.plusSeconds(60)).toEntity())
        val s = dao.observeSummarySince(cutOff.toEpochMilli()).first()
        assertThat(s.total).isEqualTo(2)
        assertThat(s.success).isEqualTo(1)
        assertThat(s.pending).isEqualTo(1)
        assertThat(s.sentPaise).isEqualTo(200)
        assertThat(dao.observeSummarySince(cutOff.plusSeconds(3_600).toEpochMilli()).first().total).isEqualTo(0)
    }

    // --- security regression tests (feature 1 review) ---

    @Test
    fun `search text is bound, never spliced into sql`() = runTest {
        dao.insert(testPayment("a", payeeName = "Alice").toEntity())
        val all = PaymentStatus.entries.map { it.name }
        for (hostile in listOf("'; DROP TABLE payments; --", "\" OR 1=1 --", "%' OR '1'='1", "zzz\uFEFF", "zzz\u0000")) {
            val rows = dao.observeHistory(all, hostile).first()
            assertThat(rows).isEmpty()
        }
        assertThat(dao.count()).isEqualTo(1) // table still exists and is intact
    }

    @Test
    fun `like wildcards in the search only widen the match within the bound parameter`() = runTest {
        dao.insert(testPayment("a", payeeName = "Alice").toEntity())
        dao.insert(testPayment("b", payeeName = "Bob", payeeVpa = "bob@upi").toEntity())
        val all = PaymentStatus.entries.map { it.name }
        assertThat(dao.observeHistory(all, "%").first()).hasSize(2) // harmless: equals "match everything"
        assertThat(dao.observeHistory(all, "_lice").first().map { it.id }).containsExactly("a")
    }

    @Test
    fun `unicode, rtl and very long payee names round trip unchanged`() = runTest {
        val rtl = "\u202Eecila\u202C \u0645\u0631\u062D\u0628\u0627 \uD83D\uDE00"
        val long = "x".repeat(10_000)
        dao.insert(testPayment("rtl", payeeName = rtl).toEntity())
        dao.insert(testPayment("long", payeeName = long, payeeVpa = "long@upi").toEntity())
        assertThat(dao.getById("rtl")!!.payeeName).isEqualTo(rtl)
        assertThat(dao.getById("long")!!.payeeName).hasLength(10_000)
        assertThat(dao.observeHistory(PaymentStatus.entries.map { it.name }, "\u0645\u0631").first().map { it.id }).containsExactly("rtl")
    }

    @Test
    fun `recent payees are distinct outgoing payees, most recently paid first`() = runTest {
        dao.insertAll(
            listOf(
                testPayment("1", payeeVpa = "alice@okbank", payeeName = "Alice", createdAt = FIXED_INSTANT.minusSeconds(300)).toEntity(),
                testPayment("2", payeeVpa = "bob@upi", payeeName = "Bob", createdAt = FIXED_INSTANT.minusSeconds(200)).toEntity(),
                testPayment("3", payeeVpa = "alice@okbank", payeeName = "Alice K", createdAt = FIXED_INSTANT.minusSeconds(100)).toEntity(),
                testPayment("4", payeeVpa = "me@upi", payeeName = "Me", direction = PaymentDirection.INCOMING).toEntity(),
            ),
        )
        val payees = dao.observeRecentPayees(5).first()
        assertThat(payees.map { it.vpa }).containsExactly("alice@okbank", "bob@upi").inOrder()
        assertThat(payees.first().name).isEqualTo("Alice K") // the name used most recently
        assertThat(dao.observeRecentPayees(1).first()).hasSize(1)
    }

    @Test
    fun `compare and set writes the status source`() = runTest {
        dao.insert(testPayment("p", status = PaymentStatus.PENDING).copy(handlerPackage = "com.example.gpay").toEntity())
        val rows = dao.compareAndSetStatus("p", "PENDING", "SUCCESS", null, "UPI1", null, null, "UPI_APP", 1L)
        assertThat(rows).isEqualTo(1)
        val stored = dao.getById("p")!!
        assertThat(stored.statusSource).isEqualTo("UPI_APP")
        assertThat(stored.handlerPackage).isEqualTo("com.example.gpay")
        assertThat(stored.toDomain().statusSource).isEqualTo(StatusSource.UPI_APP)
    }

    @Test
    fun `stale pending lookups for the reconciler skip upi app payments`() = runTest {
        dao.insertAll(
            listOf(
                testPayment("sandbox", status = PaymentStatus.PENDING).toEntity(),
                testPayment("app", status = PaymentStatus.PENDING).copy(handlerPackage = "com.example.gpay").toEntity(),
                testPayment("chooser", status = PaymentStatus.PENDING).copy(handlerPackage = com.darshan.payflow.domain.model.Payment.VIA_SYSTEM_CHOOSER).toEntity(),
            ),
        )
        assertThat(dao.pendingOlderThan(FIXED_INSTANT.toEpochMilli() + 1, 10).map { it.id }).containsExactly("sandbox")
    }
}
