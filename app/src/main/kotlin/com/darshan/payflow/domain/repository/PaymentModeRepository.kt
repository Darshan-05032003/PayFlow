package com.darshan.payflow.domain.repository

import com.darshan.payflow.domain.model.PaymentMode
import kotlinx.coroutines.flow.Flow

/** Persisted payment-mode setting. The default depends on the device: UPI apps when at least one is installed, else sandbox. */
interface PaymentModeRepository {
    val mode: Flow<PaymentMode>
    suspend fun current(): PaymentMode
    suspend fun set(mode: PaymentMode)
}
