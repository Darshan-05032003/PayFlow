package com.darshan.payflow.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.darshan.payflow.data.upi.UpiAppLauncher
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.repository.PaymentModeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed mode. Until the user chooses, the mode is derived from the device: [PaymentMode.UPI_APPS] when the launcher
 * can see a UPI app, [PaymentMode.SANDBOX] otherwise (emulators, JVM tests). The setting itself is not a secret, so it lives in
 * plain preferences; changing it is only reachable from the Security screen, which sits behind the app lock.
 */
class DataStorePaymentModeRepository(
    private val prefs: DataStore<Preferences>,
    private val launcher: UpiAppLauncher,
) : PaymentModeRepository {
    override val mode: Flow<PaymentMode> = prefs.data.map { it[KEY]?.let(PaymentMode::fromNameOrNull) ?: defaultMode() }

    override suspend fun current(): PaymentMode = mode.first()

    override suspend fun set(mode: PaymentMode) {
        prefs.edit { it[KEY] = mode.name }
    }

    private fun defaultMode(): PaymentMode = if (launcher.isUpiAppInstalled()) PaymentMode.UPI_APPS else PaymentMode.SANDBOX

    companion object {
        val KEY = stringPreferencesKey("payment_mode")
    }
}

/** JVM/test implementation. */
class InMemoryPaymentModeRepository(initial: PaymentMode = PaymentMode.SANDBOX) : PaymentModeRepository {
    private val state = MutableStateFlow(initial)
    override val mode: Flow<PaymentMode> = state
    override suspend fun current(): PaymentMode = state.value
    override suspend fun set(mode: PaymentMode) { state.value = mode }
}
