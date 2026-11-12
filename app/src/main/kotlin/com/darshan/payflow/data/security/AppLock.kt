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
 *
 * Only [unlock] may open a lock. [refresh] runs for every new activity, so it can only add a lock, never clear one (unless the
 * PIN itself is gone). [onForeground] locks before its first suspension, so no frame can show the app in between; the store is
 * asked afterwards only to find out whether a PIN still exists.
 */
@Singleton
class AppLock @Inject constructor(private val pins: PinManager, private val clock: Clock) {
    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()
    private var unlockedOnce = false
    private var backgroundAtMillis: Long? = null

    /** Assume a PIN until the store has said otherwise: the wrong guess here shows a lock for a moment, never the app. */
    private var pinMayExist = true

    /** True once [refresh] has had the store's answer in this process. Until then [isLocked]'s `false` means nothing. */
    @Volatile private var resolved = false

    /** Re-evaluates on start: locked if a PIN exists and this session has not been unlocked yet. Returns whether one exists. */
    suspend fun refresh(): Boolean {
        val pinSet = pins.isSet()
        pinMayExist = pinSet
        if (!pinSet) _isLocked.value = false else if (!unlockedOnce) _isLocked.value = true
        resolved = true
        return pinSet
    }

    /**
     * Whether the app is locked, if the store has answered in this process; null until then. A screen created after the answer
     * can start in its final state instead of waiting; one created before it must wait for [refresh].
     */
    fun resolvedLockState(): Boolean? = if (resolved) _isLocked.value else null

    fun unlock() {
        unlockedOnce = true
        pinMayExist = true
        _isLocked.value = false
    }

    fun lock() {
        unlockedOnce = false
        _isLocked.value = true
    }

    fun onBackground() {
        backgroundAtMillis = clock.millis()
    }

    suspend fun onForeground() {
        val since = backgroundAtMillis ?: return
        backgroundAtMillis = null
        val away = clock.millis() - since
        // A negative value means the clock moved back while the app was away, which proves nothing about how long it was.
        if (away in 0..GRACE_SECONDS * 1_000) return
        if (pinMayExist) lock()
        val pinSet = pins.isSet()
        pinMayExist = pinSet
        if (pinSet) lock() else _isLocked.value = false
    }

    companion object {
        const val GRACE_SECONDS = 60L
    }
}
