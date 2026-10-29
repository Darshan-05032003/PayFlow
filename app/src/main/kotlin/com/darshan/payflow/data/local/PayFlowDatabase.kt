package com.darshan.payflow.data.local

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PaymentEntity::class, CollectRequestEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class PayFlowDatabase : RoomDatabase() {
    abstract fun paymentDao(): PaymentDao
    abstract fun collectDao(): CollectDao

    companion object {
        const val NAME = "payflow.db"
    }
}
