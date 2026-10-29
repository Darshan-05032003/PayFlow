package com.darshan.payflow.data.repository

import com.darshan.payflow.data.local.CollectDao
import com.darshan.payflow.data.local.toDomain
import com.darshan.payflow.data.local.toEntity
import com.darshan.payflow.domain.model.CollectRequest
import com.darshan.payflow.domain.model.CollectStatus
import com.darshan.payflow.domain.repository.CollectRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class CollectRepositoryImpl @Inject constructor(private val dao: CollectDao) : CollectRepository {
    override fun observeAll(): Flow<List<CollectRequest>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }
    override suspend fun getById(id: String): CollectRequest? = dao.getById(id)?.toDomain()
    override suspend fun upsertAll(requests: List<CollectRequest>) = dao.merge(requests.map { it.toEntity() })

    override suspend fun compareAndSetStatus(id: String, expected: CollectStatus, new: CollectStatus, paymentId: String?, updatedAt: Instant): Boolean =
        dao.compareAndSetStatus(id, expected.name, new.name, paymentId, updatedAt.toEpochMilli()) == 1

    override suspend fun expireOverdue(now: Instant): List<String> {
        val ids = dao.pendingOverdueIds(now.toEpochMilli())
        if (ids.isNotEmpty()) dao.expireOverdue(now.toEpochMilli())
        return ids
    }
}
