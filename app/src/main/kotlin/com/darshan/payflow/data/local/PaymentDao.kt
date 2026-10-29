package com.darshan.payflow.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PaymentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(payment: PaymentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(payments: List<PaymentEntity>)

    @Query("SELECT * FROM payments WHERE id = :id")
    suspend fun getById(id: String): PaymentEntity?

    @Query("SELECT * FROM payments WHERE id = :id")
    fun observeById(id: String): Flow<PaymentEntity?>

    @Query("SELECT COUNT(*) FROM payments")
    suspend fun count(): Int

    /** Atomic compare-and-set: the row changes only if it is still in [expectedStatus]. Returns rows updated (0 or 1). */
    @Query(
        """
        UPDATE payments SET status = :newStatus,
            psp_txn_id = COALESCE(:pspTxnId, psp_txn_id),
            upi_txn_id = COALESCE(:upiTxnId, upi_txn_id),
            refund_id = COALESCE(:refundId, refund_id),
            failure_reason = COALESCE(:failureReason, failure_reason),
            updated_at = :updatedAt
        WHERE id = :id AND status = :expectedStatus
        """,
    )
    suspend fun compareAndSetStatus(
        id: String,
        expectedStatus: String,
        newStatus: String,
        pspTxnId: String?,
        upiTxnId: String?,
        refundId: String?,
        failureReason: String?,
        updatedAt: Long,
    ): Int

    @Query(
        """
        SELECT * FROM payments
        WHERE status IN (:statuses)
          AND (:query = '' OR payee_name LIKE '%' || :query || '%' OR payee_vpa LIKE '%' || :query || '%')
        ORDER BY created_at DESC
        """,
    )
    fun observeHistory(statuses: List<String>, query: String): Flow<List<PaymentEntity>>

    @Query(
        """
        SELECT * FROM payments
        WHERE status IN (:statuses)
          AND (:query = '' OR payee_name LIKE '%' || :query || '%' OR payee_vpa LIKE '%' || :query || '%')
        ORDER BY created_at DESC LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun historyPage(statuses: List<String>, query: String, limit: Int, offset: Int): List<PaymentEntity>

    @Query("SELECT * FROM payments ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<PaymentEntity>>

    @Query("SELECT * FROM payments WHERE mandate_id = :mandateId ORDER BY created_at DESC")
    fun observeByMandate(mandateId: String): Flow<List<PaymentEntity>>

    @Query(
        """
        SELECT COUNT(*) AS total,
            COALESCE(SUM(CASE WHEN status IN ('SUCCESS', 'REFUND_PENDING') THEN 1 ELSE 0 END), 0) AS success,
            COALESCE(SUM(CASE WHEN status IN ('CREATED', 'PENDING') THEN 1 ELSE 0 END), 0) AS pending,
            COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed,
            COALESCE(SUM(CASE WHEN status = 'REFUNDED' THEN 1 ELSE 0 END), 0) AS refunded,
            COALESCE(SUM(CASE WHEN status IN ('SUCCESS', 'REFUND_PENDING') AND direction = 'OUTGOING' THEN amount_paise ELSE 0 END), 0) AS sent_paise,
            COALESCE(SUM(CASE WHEN status IN ('SUCCESS', 'REFUND_PENDING') AND direction = 'INCOMING' THEN amount_paise ELSE 0 END), 0) AS received_paise
        FROM payments
        """,
    )
    fun observeSummary(): Flow<SummaryRow>
}
