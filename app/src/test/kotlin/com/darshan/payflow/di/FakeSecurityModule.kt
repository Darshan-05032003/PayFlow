package com.darshan.payflow.di

import com.darshan.payflow.data.security.AllowAllPaymentGuard
import com.darshan.payflow.data.security.BiometricAuthenticator
import com.darshan.payflow.data.security.CloudProjectNumber
import com.darshan.payflow.data.security.CryptoBox
import com.darshan.payflow.data.security.DeviceIdentity
import com.darshan.payflow.data.security.ExpectedSignature
import com.darshan.payflow.data.security.FakeIntegrityVerifier
import com.darshan.payflow.data.security.FakeOtpSender
import com.darshan.payflow.data.security.InMemoryCryptoBox
import com.darshan.payflow.data.security.InMemorySecureStore
import com.darshan.payflow.data.security.IntegrityChecker
import com.darshan.payflow.data.security.IntegrityResult
import com.darshan.payflow.data.security.IntegrityVerifier
import com.darshan.payflow.data.security.OtpSender
import com.darshan.payflow.data.security.PaymentGuard
import com.darshan.payflow.data.security.SecureStore
import com.darshan.payflow.data.security.TamperDetector
import com.darshan.payflow.data.security.TamperReport
import com.darshan.payflow.testutil.FakeBiometricAuthenticator
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/** Replaces the Keystore / BiometricPrompt / Play Integrity wiring in Hilt Robolectric tests. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [SecurityModule::class])
object FakeSecurityModule {
    @Provides @Singleton fun cryptoBox(): CryptoBox = InMemoryCryptoBox()
    @Provides @Singleton fun secureStore(box: CryptoBox): SecureStore = InMemorySecureStore(box)
    @Provides @Singleton fun biometric(): BiometricAuthenticator = FakeBiometricAuthenticator()
    @Provides @Singleton fun deviceIdentity(): DeviceIdentity = object : DeviceIdentity { override fun fingerprint() = "test-device" }
    @Provides @Singleton fun otpSenderImpl(): FakeOtpSender = FakeOtpSender()
    @Provides @Singleton fun otpSender(impl: FakeOtpSender): OtpSender = impl
    @Provides @Singleton fun tamperDetector(): TamperDetector = object : TamperDetector { override suspend fun scan() = TamperReport.CLEAN }
    @Provides @Singleton fun integrityChecker(): IntegrityChecker = object : IntegrityChecker {
        override val isConfigured = false
        override suspend fun requestToken(requestHash: String): IntegrityResult = IntegrityResult.NotConfigured
    }
    @Provides @Singleton fun integrityVerifier(): IntegrityVerifier = FakeIntegrityVerifier()
    @Provides @Singleton fun paymentGuard(): PaymentGuard = AllowAllPaymentGuard()
    @Provides @CloudProjectNumber fun cloudProjectNumber(): Long = 0
    @Provides @ExpectedSignature fun expectedSignature(): String? = null
}
