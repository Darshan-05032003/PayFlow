package com.darshan.payflow.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.repository.CollectRepositoryImpl
import com.darshan.payflow.domain.model.CollectRequest
import com.darshan.payflow.domain.model.CollectStatus
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CollectDaoTest {
    private lateinit var db: PayFlowDatabase
    private lateinit var repo: CollectRepositoryImpl

    private fun request(id: String, status: CollectStatus = CollectStatus.PENDING, expiresIn: Long = 600) = CollectRequest(
        id, "shop@okbank", "Chai Point", 4_500, "Table 4", FIXED_INSTANT, FIXED_INSTANT.plusSeconds(expiresIn), FIXED_INSTANT, status, null,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PayFlowDatabase::class.java).allowMainThreadQueries().build()
        repo = CollectRepositoryImpl(db.collectDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `upsert keeps the local answer when the psp still says pending`() = runTest {
        repo.upsertAll(listOf(request("a")))
        assertThat(repo.compareAndSetStatus("a", CollectStatus.PENDING, CollectStatus.APPROVED, "pay-1", FIXED_INSTANT)).isTrue()
        repo.upsertAll(listOf(request("a"))) // stale PENDING from the server
        val stored = repo.getById("a")!!
        assertThat(stored.status).isEqualTo(CollectStatus.APPROVED)
        assertThat(stored.paymentId).isEqualTo("pay-1")
    }

    @Test
    fun `compare and set is atomic under concurrency`() = runTest {
        repo.upsertAll(listOf(request("a")))
        val wins = (1..50).map { async(Dispatchers.IO) { repo.compareAndSetStatus("a", CollectStatus.PENDING, CollectStatus.APPROVED, "p$it", FIXED_INSTANT) } }.awaitAll()
        assertThat(wins.count { it }).isEqualTo(1)
    }

    @Test
    fun `expire overdue only touches pending rows and returns their ids`() = runTest {
        repo.upsertAll(listOf(request("soon", expiresIn = 10), request("later", expiresIn = 1_000), request("done", CollectStatus.DECLINED, expiresIn = 10)))
        val expired = repo.expireOverdue(FIXED_INSTANT.plusSeconds(60))
        assertThat(expired).containsExactly("soon")
        assertThat(repo.getById("done")!!.status).isEqualTo(CollectStatus.DECLINED)
        assertThat(repo.observeAll().first().map { it.id to it.status }).containsExactly(
            "soon" to CollectStatus.EXPIRED, "later" to CollectStatus.PENDING, "done" to CollectStatus.DECLINED,
        )
    }
}
