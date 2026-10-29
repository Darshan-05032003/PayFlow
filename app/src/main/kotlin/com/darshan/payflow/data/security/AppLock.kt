package com.darshan.payflow.data.security

import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Session lock state. The app starts locked when a PIN is configured, unlocks after PIN/biometric, and relocks when it comes
 * back from the background after more than [GRACE_SECONDS].
 */
@Singleton
class AppLock @Inject constructor(private val pins: PinManager, private val clock: Clock) {
    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()
    private var unlockedOnce = false
    private var backgroundAtMillis: Long? = null

    /** Re-evaluates on start: locked if a PIN exists and this session has not been unlocked yet. */
    suspend fun refresh() {
        _isLocked.value = pins.isSet() && !unlockedOnce
    }

    fun unlock() {
        unlockedOnce = true
        _isLocked.value = false
    }

    fun lock() {
        _isLocked.value = true
    }

    fun onBackground() {
        backgroundAtMillis = clock.millis()
    }

    suspend fun onForeground() {
        val since = backgroundAtMillis ?: return
        backgroundAtMillis = null
        if (pins.isSet() && clock.millis() - since > GRACE_SECONDS * 1_000) lock()
    }

    companion object {
        const val GRACE_SECONDS = 60L
    }
}
