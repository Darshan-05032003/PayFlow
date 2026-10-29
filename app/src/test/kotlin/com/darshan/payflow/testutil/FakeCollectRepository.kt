package com.darshan.payflow.testutil

import com.darshan.payflow.domain.model.CollectRequest
import com.darshan.payflow.domain.model.CollectStatus
import com.darshan.payflow.domain.repository.CollectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant

class FakeCollectRepository : CollectRepository {
    private val items = MutableStateFlow<Map<String, CollectRequest>>(emptyMap())
    var failure: Throwable? = null

    override fun observeAll(): Flow<List<CollectRequest>> = items.map { m ->
        failure?.let { throw it }
        m.values.sortedByDescending { it.createdAt }
    }

    override suspend fun getById(id: String): CollectRequest? = items.value[id]

    override suspend fun upsertAll(requests: List<CollectRequest>) = items.update { it + requests.associateBy { r -> r.id } }

    override suspend fun compareAndSetStatus(id: String, expected: CollectStatus, new: CollectStatus, paymentId: String?, updatedAt: Instant): Boolean {
        val current = items.value[id] ?: return false
        if (current.status != expected) return false
        items.update { it + (id to current.copy(status = new, paymentId = paymentId ?: current.paymentId, updatedAt = updatedAt)) }
        return true
    }

    override suspend fun expireOverdue(now: Instant): List<String> {
        val overdue = items.value.values.filter { it.status == CollectStatus.PENDING && it.expiresAt <= now }
        overdue.forEach { compareAndSetStatus(it.id, CollectStatus.PENDING, CollectStatus.EXPIRED, null, now) }
        return overdue.map { it.id }
    }
}
