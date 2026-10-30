package com.darshan.payflow.di

import com.darshan.checkoutkit.core.CheckoutConfig
import com.darshan.checkoutkit.core.CheckoutKit
import com.darshan.checkoutkit.core.PendingPolicy
import com.darshan.checkoutkit.core.retry.RetryPolicy
import com.darshan.payflow.data.checkout.CheckoutMerchant
import com.darshan.payflow.data.checkout.PayFlowPaymentGateway
import com.darshan.payflow.ui.checkout.CheckoutViewModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/** CheckoutKit (JitPack `checkoutkit-ui:v1.0.0`) wired to PayFlow's sandbox PSP through [PayFlowPaymentGateway]. */
@Module
@InstallIn(SingletonComponent::class)
object CheckoutModule {
    @Provides @Singleton
    fun merchant(): CheckoutMerchant = CheckoutMerchant(CheckoutViewModel.MERCHANT_VPA, CheckoutViewModel.MERCHANT_NAME)

    @Provides @Singleton
    fun checkoutKit(gateway: PayFlowPaymentGateway): CheckoutKit = CheckoutKit(
        gateway,
        CheckoutConfig(
            retryPolicy = RetryPolicy(maxAttempts = 3, initialDelay = 1.seconds, maxDelay = 8.seconds, attemptTimeout = 15.seconds),
            pendingPolicy = PendingPolicy(initialInterval = 2.seconds, maxInterval = 10.seconds, maxPolls = 12),
        ),
    )
}
