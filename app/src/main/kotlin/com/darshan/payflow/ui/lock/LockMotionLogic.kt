package com.darshan.payflow.ui.lock

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.darshan.payflow.data.security.PinManager
import kotlin.math.hypot
import kotlin.math.max

/** The rules behind the lock screen's motion, kept out of Compose so they can be tested without a clock. */
object LockMotionLogic {
    /**
     * How many dots look filled. A refused PIN has already cleared the digits, so the dots stay full while they shake and
     * only then drain. A new digit wins at once: feedback never holds up typing.
     */
    fun visibleDots(entered: Int, rejecting: Boolean, length: Int = PinManager.PIN_LENGTH): Int =
        if (rejecting && entered == 0) length else entered.coerceIn(0, length)

    /** A refusal plays only when the serial moved, so recomposing or rotating never replays it. */
    fun shouldPlayRejection(seen: Int, current: Int): Boolean = current > seen

    /** Seconds shown in the countdown: rounded up, so it never reads 0 while the keypad is still disabled. */
    fun lockoutSeconds(remainingMillis: Long): Long = (remainingMillis.coerceAtLeast(0) + 999) / 1_000

    fun keypadEnabled(state: LockUiState): Boolean = when (state) {
        is LockUiState.Locked -> state.lockoutRemainingMillis <= 0
        is LockUiState.Setup -> true
        else -> false
    }

    /** Radius that just covers the whole window from [origin], whichever corner is furthest. */
    fun revealRadius(origin: Offset, size: Size): Float {
        val dx = max(origin.x, size.width - origin.x)
        val dy = max(origin.y, size.height - origin.y)
        return hypot(dx, dy)
    }

    /** Where the reveal opens from: the fingerprint key, the PIN dots, or the middle of the window if neither was measured. */
    fun revealOrigin(method: UnlockMethod?, pinAnchor: Offset?, biometricAnchor: Offset?, size: Size): Offset {
        val anchor = if (method == UnlockMethod.Biometric) biometricAnchor ?: pinAnchor else pinAnchor
        return anchor ?: Offset(size.width / 2f, size.height / 2f)
    }
}

/** Measured centres of the lock screen's two unlock controls, in window coordinates. Plain fields: nothing recomposes on them. */
class RevealAnchors {
    var pin: Offset? = null
    var biometric: Offset? = null
}
