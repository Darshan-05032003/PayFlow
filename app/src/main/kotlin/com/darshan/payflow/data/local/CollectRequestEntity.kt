package com.darshan.payflow.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.darshan.payflow.domain.model.CollectRequest
import com.darshan.payflow.domain.model.CollectStatus
import java.time.Instant
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "collect_requests", indices = [Index("status"), Index("created_at")])
data class CollectRequestEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "payee_vpa") val payeeVpa: String,
    @ColumnInfo(name = "payee_name") val payeeName: String,
    @ColumnInfo(name = "amount_paise") val amountPaise: Long,
    val note: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val status: String,
    @ColumnInfo(name = "payment_id") val paymentId: String?,
)

fun CollectRequest.toEntity() = CollectRequestEntity(
    id, payeeVpa, payeeName, amountPaise, note, createdAt.toEpochMilli(), expiresAt.toEpochMilli(), updatedAt.toEpochMilli(), status.name, paymentId,
)

fun CollectRequestEntity.toDomain() = CollectRequest(
    id, payeeVpa, payeeName, amountPaise, note, Instant.ofEpochMilli(createdAt), Instant.ofEpochMilli(expiresAt),
    Instant.ofEpochMilli(updatedAt), CollectStatus.valueOf(status), paymentId,
)

@Dao
interface CollectDao {
    @Upsert
    suspend fun upsert(entity: CollectRequestEntity)

    @Query("SELECT * FROM collect_requests WHERE id = :id")
    suspend fun getById(id: String): CollectRequestEntity?

    @Query("SELECT * FROM collect_requests ORDER BY created_at DESC")
    fun observeAll(): Flow<List<CollectRequestEntity>>

    @Query(
        """
        UPDATE collect_requests SET status = :newStatus, payment_id = COALESCE(:paymentId, payment_id), updated_at = :updatedAt
        WHERE id = :id AND status = :expectedStatus
        """,
    )
    suspend fun compareAndSetStatus(id: String, expectedStatus: String, newStatus: String, paymentId: String?, updatedAt: Long): Int

    @Query("SELECT id FROM collect_requests WHERE status = 'PENDING' AND expires_at <= :now")
    suspend fun pendingOverdueIds(now: Long): List<String>

    @Query("UPDATE collect_requests SET status = 'EXPIRED', updated_at = :now WHERE status = 'PENDING' AND expires_at <= :now")
    suspend fun expireOverdue(now: Long): Int

    /** Server rows never revert a local answer (APPROVED / DECLINED / EXPIRED) back to PENDING. */
    @Transaction
    suspend fun merge(incoming: List<CollectRequestEntity>) {
        for (row in incoming) {
            val existing = getById(row.id)
            val merged = if (existing != null && existing.status != CollectStatus.PENDING.name && row.status == CollectStatus.PENDING.name) {
                row.copy(status = existing.status, paymentId = existing.paymentId, updatedAt = existing.updatedAt)
            } else {
                row
            }
            upsert(merged)
        }
    }
}
