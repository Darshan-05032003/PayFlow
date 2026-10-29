package com.darshan.payflow.domain.repository

import com.darshan.payflow.domain.model.HistoryFilter
import com.darshan.payflow.domain.model.HistorySummary
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentEvent
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
}
