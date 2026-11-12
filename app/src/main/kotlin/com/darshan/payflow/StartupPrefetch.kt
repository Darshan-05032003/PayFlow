package com.darshan.payflow

import com.darshan.payflow.core.DispatcherProvider
import com.darshan.payflow.data.security.AppLock
import com.darshan.payflow.data.security.PinManager
import com.darshan.payflow.ui.designsystem.effects.UiPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Starts the reads the first frame waits for as soon as the process starts, so they overlap the activity's own startup
 * instead of beginning when the first composition asks.
 *
 * - The look-and-feel settings: [UiPreferences] begins reading the moment it exists, and this class holds it.
 * - The lock: whether a PIN exists (no decryption needed), and if one does, its attempts and lockout (one decrypt).
 *
 * Nothing here opens anything: a failed read leaves the lock unanswered, so the splash and the lock screen's Checking state
 * wait for the lock's own read, exactly as they would without this class.
 */
@Singleton
class StartupPrefetch @Inject constructor(
    private val appLock: AppLock,
    private val pins: PinManager,
    @Suppress("unused") private val uiPreferences: UiPreferences,
    private val dispatchers: DispatcherProvider,
) {
    fun start(scope: CoroutineScope) {
        scope.launch(dispatchers.io) {
            runCatching { if (appLock.refresh()) pins.lockStatus() }
        }
    }
}
