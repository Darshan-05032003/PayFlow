package com.darshan.payflow.testutil

import com.darshan.payflow.domain.model.Mandate
import com.darshan.payflow.domain.model.MandateStatus
import com.darshan.payflow.domain.repository.MandateRepository
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class FakeMandateRepository : MandateRepository {
    private val items = MutableStateFlow<Map<String, Mandate>>(emptyMap())
    var failure: Throwable? = null

    override fun observeAll(): Flow<List<Mandate>> = items.map { m -> failure?.let { throw it }; m.values.sortedByDescending { it.createdAt } }
    override fun observeById(id: String): Flow<Mandate?> = items.map { failure?.let { e -> throw e }; it[id] }
    override suspend fun getById(id: String): Mandate? = items.value[id]
    override suspend fun create(mandate: Mandate) { check(items.value[mandate.id] == null); items.update { it + (mandate.id to mandate) } }

    override suspend fun compareAndSetStatus(id: String, expected: MandateStatus, new: MandateStatus, updatedAt: Instant): Boolean {
        val current = items.value[id] ?: return false
        if (current.status != expected) return false
        items.update { it + (id to current.copy(status = new, updatedAt = updatedAt)) }
        return true
    }

    override suspend fun recordExecution(id: String, executedAt: Instant, expectedCount: Int): Boolean {
        val current = items.value[id] ?: return false
        if (current.executionCount != expectedCount) return false
        items.update { it + (id to current.copy(lastExecutedAt = executedAt, executionCount = expectedCount + 1, updatedAt = executedAt)) }
        return true
    }

    override suspend fun releaseExecution(id: String, previousExecutedAt: Instant?, previousCount: Int): Boolean {
        val current = items.value[id] ?: return false
        if (current.executionCount != previousCount + 1) return false
        items.update { it + (id to current.copy(lastExecutedAt = previousExecutedAt, executionCount = previousCount)) }
        return true
    }

    override suspend fun expireEndedBefore(today: LocalDate, now: Instant): Int {
        val ended = items.value.values.filter { !it.status.isTerminal && it.endDate.isBefore(today) }
        ended.forEach { compareAndSetStatus(it.id, it.status, MandateStatus.EXPIRED, now) }
        return ended.size
    }

    suspend fun all() = observeAll().first()
}
