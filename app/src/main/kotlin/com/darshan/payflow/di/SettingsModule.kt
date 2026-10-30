package com.darshan.payflow.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.darshan.payflow.data.settings.DataStorePaymentModeRepository
import com.darshan.payflow.data.upi.UpiAppLauncher
import com.darshan.payflow.domain.repository.PaymentModeRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsPrefs

/** Non-secret app settings (payment mode). Secrets go through `SecureStore`, never here. */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {
    @Provides @Singleton @SettingsPrefs
    fun settingsPrefs(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { context.preferencesDataStoreFile("payflow_settings") }

    @Provides @Singleton
    fun upiProfileRepository(store: com.darshan.payflow.data.security.SecureStore): com.darshan.payflow.domain.model.UpiProfileRepository =
        com.darshan.payflow.data.profile.SecureUpiProfileRepository(store)

    @Provides @Singleton
    fun paymentModeRepository(@SettingsPrefs prefs: DataStore<Preferences>, launcher: UpiAppLauncher): PaymentModeRepository =
        DataStorePaymentModeRepository(prefs, launcher)
}
