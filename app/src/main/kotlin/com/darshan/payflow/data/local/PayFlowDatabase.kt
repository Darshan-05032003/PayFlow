package com.darshan.payflow.data.local

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PaymentEntity::class, CollectRequestEntity::class, MandateEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class PayFlowDatabase : RoomDatabase() {
    abstract fun paymentDao(): PaymentDao
    abstract fun collectDao(): CollectDao
    abstract fun mandateDao(): MandateDao

    companion object {
        const val NAME = "payflow.db"
    }
}
