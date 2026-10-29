package com.darshan.payflow.domain.repository

import com.darshan.payflow.domain.model.CollectRequest
import com.darshan.payflow.domain.model.CollectStatus
import java.time.Instant
import kotlinx.coroutines.flow.Flow

interface CollectRepository {
    fun observeAll(): Flow<List<CollectRequest>>
    suspend fun getById(id: String): CollectRequest?

    /** Inserts or refreshes rows from the PSP. A locally answered request is never reverted to PENDING by a stale server row. */
    suspend fun upsertAll(requests: List<CollectRequest>)

    /** Atomic status change; returns false if the row is not in [expected]. */
    suspend fun compareAndSetStatus(id: String, expected: CollectStatus, new: CollectStatus, paymentId: String?, updatedAt: Instant): Boolean

    /** Marks PENDING rows whose expiry has passed as EXPIRED; returns their ids. */
    suspend fun expireOverdue(now: Instant): List<String>
}
