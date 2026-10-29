package com.darshan.payflow.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [PaymentEntity::class], version = 1, exportSchema = true)
abstract class PayFlowDatabase : RoomDatabase() {
    abstract fun paymentDao(): PaymentDao

    companion object {
        const val NAME = "payflow.db"
    }
}
