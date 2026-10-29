package com.darshan.payflow.testutil

import com.darshan.payflow.domain.model.HistoryFilter
import com.darshan.payflow.domain.model.HistorySummary
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentEvent
import com.darshan.payflow.domain.model.PaymentNotFoundException
import com.darshan.payflow.domain.model.PaymentStateMachine
import com.darshan.payflow.domain.repository.PaymentRepository
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** In-memory repository for ViewModel unit tests. Set [failure] to make every flow throw. */
class FakePaymentRepository(private val clock: Clock = fixedClock) : PaymentRepository {
    private val payments = MutableStateFlow<Map<String, Payment>>(emptyMap())
    var failure: Throwable? = null

    private fun <T> Flow<T>.failing(): Flow<T> = map { failure?.let { throw it } ?: it }

    fun seed(vararg items: Payment) = payments.update { map -> map + items.associateBy { it.id } }

    override fun observeHistory(filter: HistoryFilter, query: String): Flow<List<Payment>> =
        payments.map { map ->
            map.values
                .filter { filter.matches(it.status) }
                .filter { query.isBlank() || it.payeeName.contains(query, true) || it.payeeVpa.contains(query, true) }
                .sortedByDescending { it.createdAt }
        }.failing()

    override fun observeRecent(limit: Int): Flow<List<Payment>> =
        payments.map { map -> map.values.sortedByDescending { it.createdAt }.take(limit) }.failing()

    override fun observeById(id: String): Flow<Payment?> = payments.map { it[id] }.failing()

    override suspend fun getById(id: String): Payment? = payments.value[id]

    override suspend fun create(payment: Payment) {
        check(payments.value[payment.id] == null) { "duplicate id" }
        payments.update { it + (payment.id to payment) }
    }

    override suspend fun applyEvent(id: String, event: PaymentEvent): Result<Payment> {
        val current = payments.value[id] ?: return Result.failure(PaymentNotFoundException(id))
        return PaymentStateMachine.apply(current, event, clock.instant()).onSuccess { updated ->
            payments.update { it + (id to updated) }
        }
    }

    override fun observeByMandate(mandateId: String): Flow<List<Payment>> =
        payments.map { map -> map.values.filter { it.mandateId == mandateId }.sortedByDescending { it.createdAt } }.failing()

    override fun observeSummary(): Flow<HistorySummary> =
        payments.map { map -> HistorySummary.of(map.values) }.failing()
}
