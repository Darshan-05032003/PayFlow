package com.darshan.payflow.data.repository

import com.darshan.payflow.data.local.MandateDao
import com.darshan.payflow.data.local.toDomain
import com.darshan.payflow.data.local.toEntity
import com.darshan.payflow.domain.model.Mandate
import com.darshan.payflow.domain.model.MandateStatus
import com.darshan.payflow.domain.repository.MandateRepository
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class MandateRepositoryImpl @Inject constructor(private val dao: MandateDao) : MandateRepository {
    override fun observeAll(): Flow<List<Mandate>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }
    override fun observeById(id: String): Flow<Mandate?> = dao.observeById(id).map { it?.toDomain() }
    override suspend fun getById(id: String): Mandate? = dao.getById(id)?.toDomain()
    override suspend fun create(mandate: Mandate) = dao.insert(mandate.toEntity())

    override suspend fun compareAndSetStatus(id: String, expected: MandateStatus, new: MandateStatus, updatedAt: Instant): Boolean =
        dao.compareAndSetStatus(id, expected.name, new.name, updatedAt.toEpochMilli()) == 1

    override suspend fun recordExecution(id: String, executedAt: Instant, expectedCount: Int): Boolean =
        dao.recordExecution(id, executedAt.toEpochMilli(), expectedCount) == 1

    override suspend fun releaseExecution(id: String, previousExecutedAt: Instant?, previousCount: Int): Boolean =
        dao.releaseExecution(id, previousExecutedAt?.toEpochMilli(), previousCount) == 1

    /** ISO dates compare correctly as strings (yyyy-MM-dd). */
    override suspend fun expireEndedBefore(today: LocalDate, now: Instant): Int = dao.expireEndedBefore(today.toString(), now.toEpochMilli())
}
