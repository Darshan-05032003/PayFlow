package com.darshan.payflow.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.darshan.payflow.BuildConfig
import com.darshan.payflow.data.security.AndroidBiometricAuthenticator
import com.darshan.payflow.data.security.AndroidDeviceIdentity
import com.darshan.payflow.data.security.AndroidTamperDetector
import com.darshan.payflow.data.security.BiometricAuthenticator
import com.darshan.payflow.data.security.CloudProjectNumber
import com.darshan.payflow.data.security.CryptoBox
import com.darshan.payflow.data.security.DefaultPaymentGuard
import com.darshan.payflow.data.security.DeviceIdentity
import com.darshan.payflow.data.security.EncryptedSecureStore
import com.darshan.payflow.data.security.ExpectedSignature
import com.darshan.payflow.data.security.FakeIntegrityVerifier
import com.darshan.payflow.data.security.FakeOtpSender
import com.darshan.payflow.data.security.IntegrityChecker
import com.darshan.payflow.data.security.IntegrityVerifier
import com.darshan.payflow.data.security.KeystoreCryptoBox
import com.darshan.payflow.data.security.OtpSender
import com.darshan.payflow.data.security.PaymentGuard
import com.darshan.payflow.data.security.PlayIntegrityChecker
import com.darshan.payflow.data.security.SecureStore
import com.darshan.payflow.data.security.TamperDetector
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Production security wiring. Replaced in Hilt tests by `FakeSecurityModule` (Robolectric has no AndroidKeyStore).
 * Real: Keystore AES-GCM, BiometricPrompt, root/tamper probes, Play Integrity *request*.
 * Simulated (no backend): SMS OTP delivery and Play Integrity token *verification*.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {
    @Binds @Singleton abstract fun cryptoBox(impl: KeystoreCryptoBox): CryptoBox
    @Binds @Singleton abstract fun biometric(impl: AndroidBiometricAuthenticator): BiometricAuthenticator
    @Binds @Singleton abstract fun deviceIdentity(impl: AndroidDeviceIdentity): DeviceIdentity
    @Binds @Singleton abstract fun otpSender(impl: FakeOtpSender): OtpSender
    @Binds @Singleton abstract fun tamperDetector(impl: AndroidTamperDetector): TamperDetector
    @Binds @Singleton abstract fun integrityChecker(impl: PlayIntegrityChecker): IntegrityChecker
    @Binds @Singleton abstract fun paymentGuard(impl: DefaultPaymentGuard): PaymentGuard

    companion object {
        @Provides @Singleton
        fun secureStore(@ApplicationContext context: Context, box: CryptoBox): SecureStore = EncryptedSecureStore(securePrefs(context), box)

        @Provides @Singleton
        fun integrityVerifier(): IntegrityVerifier = FakeIntegrityVerifier() // server-side verification is out of scope: no backend

        @Provides @CloudProjectNumber
        fun cloudProjectNumber(): Long = BuildConfig.PLAY_INTEGRITY_CLOUD_PROJECT

        @Provides @ExpectedSignature
        fun expectedSignature(): String? = BuildConfig.EXPECTED_SIGNING_SHA256.takeIf { it.isNotBlank() }

        private fun securePrefs(context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { context.preferencesDataStoreFile("payflow_secure") }
    }
}
