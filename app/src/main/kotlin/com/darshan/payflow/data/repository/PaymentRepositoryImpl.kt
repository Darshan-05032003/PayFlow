package com.darshan.payflow.data.repository

import com.darshan.payflow.data.local.PaymentDao
import com.darshan.payflow.data.local.toDomain
import com.darshan.payflow.data.local.toEntity
import com.darshan.payflow.domain.model.HistoryFilter
import com.darshan.payflow.domain.model.HistorySummary
import com.darshan.payflow.domain.model.IllegalTransitionException
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentEvent
import com.darshan.payflow.domain.model.PaymentNotFoundException
import com.darshan.payflow.domain.model.PaymentStateMachine
import com.darshan.payflow.domain.repository.PaymentRepository
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class PaymentRepositoryImpl @Inject constructor(
    private val dao: PaymentDao,
    private val clock: Clock,
) : PaymentRepository {

    override fun observeHistory(filter: HistoryFilter, query: String): Flow<List<Payment>> =
        dao.observeHistory(filter.statusNames(), sanitizeQuery(query)).map { rows -> rows.map { it.toDomain() } }

    override fun observeRecent(limit: Int): Flow<List<Payment>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeById(id: String): Flow<Payment?> = dao.observeById(id).map { it?.toDomain() }

    override suspend fun getById(id: String): Payment? = dao.getById(id)?.toDomain()

    override suspend fun create(payment: Payment) = dao.insert(payment.toEntity())

    override suspend fun applyEvent(id: String, event: PaymentEvent): Result<Payment> {
        // Retry the compare-and-set a few times: a concurrent writer may have moved the row between our read
        // and our update. The SQL `WHERE status = :expected` guard makes the update itself atomic.
        repeat(MAX_CAS_ATTEMPTS) {
            val current = dao.getById(id)?.toDomain() ?: return Result.failure(PaymentNotFoundException(id))
            val updated = PaymentStateMachine.apply(current, event, clock.instant()).getOrElse { return Result.failure(it) }
            val rows = dao.compareAndSetStatus(
                id = id,
                expectedStatus = current.status.name,
                newStatus = updated.status.name,
                pspTxnId = updated.pspTxnId.takeIf { it != current.pspTxnId },
                upiTxnId = updated.upiTxnId.takeIf { it != current.upiTxnId },
                refundId = updated.refundId.takeIf { it != current.refundId },
                failureReason = updated.failureReason.takeIf { it != current.failureReason },
                updatedAt = updated.updatedAt.toEpochMilli(),
            )
            if (rows == 1) return Result.success(updated)
        }
        val latest = dao.getById(id)?.toDomain() ?: return Result.failure(PaymentNotFoundException(id))
        return Result.failure(IllegalTransitionException(latest.status, event))
    }

    override fun observeSummary(): Flow<HistorySummary> = dao.observeSummary().map { it.toDomain() }

    override fun observeSummarySince(since: Instant): Flow<HistorySummary> =
        dao.observeSummarySince(since.toEpochMilli()).map { it.toDomain() }

    override suspend fun findByIdempotencyKey(key: String): Payment? = dao.getByIdempotencyKey(key)?.toDomain()

    override suspend fun pendingOlderThan(before: java.time.Instant): List<Payment> =
        dao.pendingOlderThan(before.toEpochMilli(), RECONCILE_BATCH).map { it.toDomain() }

    override fun observeByMandate(mandateId: String): Flow<List<Payment>> =
        dao.observeByMandate(mandateId).map { rows -> rows.map { it.toDomain() } }

    companion object {
        private const val MAX_CAS_ATTEMPTS = 3
        private const val RECONCILE_BATCH = 200

        /**
         * A NUL byte inside a LIKE pattern makes SQLite treat it as the end of the pattern (a leading NUL turns the search into
         * match-all), and a leading U+FEFF (BOM) was observed to do the same. Control and invisible FORMAT characters
         * (BOM, zero-width joiners, bidi overrides) are never meaningful in a search, so they are stripped.
         */
        fun sanitizeQuery(query: String): String =
            query.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }.trim()
    }
}
