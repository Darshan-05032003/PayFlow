package com.darshan.payflow.domain.repository

import com.darshan.payflow.domain.model.HistoryFilter
import com.darshan.payflow.domain.model.HistorySummary
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentEvent
import java.time.Instant
import kotlinx.coroutines.flow.Flow

interface PaymentRepository {
    fun observeHistory(filter: HistoryFilter, query: String): Flow<List<Payment>>
    fun observeRecent(limit: Int): Flow<List<Payment>>
    fun observeById(id: String): Flow<Payment?>
    suspend fun getById(id: String): Payment?

    /** Inserts a new payment. Fails if the id, txnRef or idempotency key already exists. */
    suspend fun create(payment: Payment)

    /**
     * Applies [event] atomically (compare-and-set on the current status). Fails with
     * [com.darshan.payflow.domain.model.IllegalTransitionException] or
     * [com.darshan.payflow.domain.model.PaymentNotFoundException]; never partially updates.
     */
    suspend fun applyEvent(id: String, event: PaymentEvent): Result<Payment>

    fun observeSummary(): Flow<HistorySummary>

    /** Summary of payments created at or after [since] (the home dashboard uses the start of the current month). */
    fun observeSummarySince(since: Instant): Flow<HistorySummary>

    /** The payment stored under an idempotency key, if any (the key is written before the PSP is called). */
    suspend fun findByIdempotencyKey(key: String): Payment?

    /** CREATED / PENDING payments created at or before [before], oldest first. */
    suspend fun pendingOlderThan(before: java.time.Instant): List<Payment>
    fun observeByMandate(mandateId: String): Flow<List<Payment>>
}
