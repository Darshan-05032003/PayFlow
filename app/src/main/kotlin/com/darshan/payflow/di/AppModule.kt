package com.darshan.payflow.di

import android.content.Context
import androidx.room.Room
import com.darshan.payflow.core.DefaultDispatcherProvider
import com.darshan.payflow.core.DispatcherProvider
import com.darshan.payflow.data.local.PayFlowDatabase
import com.darshan.payflow.data.local.PaymentDao
import com.darshan.payflow.data.repository.PaymentRepositoryImpl
import com.darshan.payflow.domain.repository.PaymentRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun clock(): Clock = Clock.systemUTC()

    @Provides @Singleton
    fun dispatchers(): DispatcherProvider = DefaultDispatcherProvider()

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): PayFlowDatabase =
        Room.databaseBuilder(context, PayFlowDatabase::class.java, PayFlowDatabase.NAME).build()

    @Provides
    fun paymentDao(db: PayFlowDatabase): PaymentDao = db.paymentDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton
    abstract fun paymentRepository(impl: PaymentRepositoryImpl): PaymentRepository
}
