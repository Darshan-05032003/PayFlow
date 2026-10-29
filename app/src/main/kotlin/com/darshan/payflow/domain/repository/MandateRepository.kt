package com.darshan.payflow.domain.repository

import com.darshan.payflow.domain.model.Mandate
import com.darshan.payflow.domain.model.MandateStatus
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

interface MandateRepository {
    fun observeAll(): Flow<List<Mandate>>
    fun observeById(id: String): Flow<Mandate?>
    suspend fun getById(id: String): Mandate?
    suspend fun create(mandate: Mandate)
    suspend fun compareAndSetStatus(id: String, expected: MandateStatus, new: MandateStatus, updatedAt: Instant): Boolean

    /** Reserves the current period: succeeds only if the stored execution count is still [expectedCount]. */
    suspend fun recordExecution(id: String, executedAt: Instant, expectedCount: Int): Boolean

    /** Undo of [recordExecution] after a PSP rejection; applies only if the count is still `previousCount + 1`. */
    suspend fun releaseExecution(id: String, previousExecutedAt: Instant?, previousCount: Int): Boolean

    /** Marks non-terminal mandates whose end date is before [today] as EXPIRED; returns the count. */
    suspend fun expireEndedBefore(today: LocalDate, now: Instant): Int
}
