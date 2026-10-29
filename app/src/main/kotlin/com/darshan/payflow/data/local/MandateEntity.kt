package com.darshan.payflow.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.darshan.payflow.domain.model.Mandate
import com.darshan.payflow.domain.model.MandateFrequency
import com.darshan.payflow.domain.model.MandateStatus
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "mandates", indices = [Index(value = ["umn"], unique = true), Index("status"), Index("created_at")])
data class MandateEntity(
    @PrimaryKey val id: String,
    val umn: String,
    @ColumnInfo(name = "payee_vpa") val payeeVpa: String,
    @ColumnInfo(name = "payee_name") val payeeName: String,
    @ColumnInfo(name = "max_amount_paise") val maxAmountPaise: Long,
    val frequency: String,
    @ColumnInfo(name = "start_date") val startDate: String,
    @ColumnInfo(name = "end_date") val endDate: String,
    val status: String,
    val note: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_executed_at") val lastExecutedAt: Long?,
    @ColumnInfo(name = "execution_count") val executionCount: Int,
)

fun Mandate.toEntity() = MandateEntity(
    id, umn, payeeVpa, payeeName, maxAmountPaise, frequency.name, startDate.toString(), endDate.toString(), status.name, note,
    createdAt.toEpochMilli(), updatedAt.toEpochMilli(), lastExecutedAt?.toEpochMilli(), executionCount,
)

fun MandateEntity.toDomain() = Mandate(
    id, umn, payeeVpa, payeeName, maxAmountPaise, MandateFrequency.valueOf(frequency), LocalDate.parse(startDate), LocalDate.parse(endDate),
    MandateStatus.valueOf(status), note, Instant.ofEpochMilli(createdAt), Instant.ofEpochMilli(updatedAt), lastExecutedAt?.let(Instant::ofEpochMilli), executionCount,
)

@Dao
interface MandateDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: MandateEntity)

    @Query("SELECT * FROM mandates WHERE id = :id")
    suspend fun getById(id: String): MandateEntity?

    @Query("SELECT * FROM mandates WHERE id = :id")
    fun observeById(id: String): Flow<MandateEntity?>

    @Query("SELECT * FROM mandates ORDER BY created_at DESC")
    fun observeAll(): Flow<List<MandateEntity>>

    @Query("UPDATE mandates SET status = :newStatus, updated_at = :updatedAt WHERE id = :id AND status = :expectedStatus")
    suspend fun compareAndSetStatus(id: String, expectedStatus: String, newStatus: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE mandates SET last_executed_at = :executedAt, execution_count = execution_count + 1, updated_at = :executedAt
        WHERE id = :id AND execution_count = :expectedCount
        """,
    )
    suspend fun recordExecution(id: String, executedAt: Long, expectedCount: Int): Int

    @Query(
        """
        UPDATE mandates SET last_executed_at = :previousExecutedAt, execution_count = :previousCount
        WHERE id = :id AND execution_count = :previousCount + 1
        """,
    )
    suspend fun releaseExecution(id: String, previousExecutedAt: Long?, previousCount: Int): Int

    @Query(
        """
        UPDATE mandates SET status = 'EXPIRED', updated_at = :now
        WHERE status IN ('ACTIVE', 'PAUSED') AND end_date < :today
        """,
    )
    suspend fun expireEndedBefore(today: String, now: Long): Int
}
