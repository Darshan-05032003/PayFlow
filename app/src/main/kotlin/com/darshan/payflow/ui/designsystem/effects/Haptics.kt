package com.darshan.payflow.ui.designsystem.effects

import android.annotation.SuppressLint
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.staticCompositionLocalOf

enum class HapticEvent { Tick, Light, Confirm, Reject, Heavy }

/**
 * Tactile vocabulary. Read it with `LocalHaptics.current` or `PayFlowTheme.haptics`.
 * - [tick]: a detent, such as swipe-to-pay crossing a mark or the nav pill moving.
 * - [light]: an ordinary button press.
 * - [confirm]: a real achievement, such as a payment success.
 * - [reject]: something refused, such as a wrong PIN or an invalid field.
 * - [heavy]: a weighty moment, such as the final commit of a swipe.
 */
interface Haptics {
    fun tick()
    fun light()
    fun confirm()
    fun reject()
    fun heavy()
}

fun Haptics.perform(event: HapticEvent) = when (event) {
    HapticEvent.Tick -> tick()
    HapticEvent.Light -> light()
    HapticEvent.Confirm -> confirm()
    HapticEvent.Reject -> reject()
    HapticEvent.Heavy -> heavy()
}

/** Pure mapping, tested per API level. CONFIRM and REJECT only exist from API 30. */
@SuppressLint("InlinedApi")
fun feedbackConstantFor(event: HapticEvent, sdkInt: Int): Int = when (event) {
    HapticEvent.Tick -> HapticFeedbackConstants.CLOCK_TICK
    HapticEvent.Light -> HapticFeedbackConstants.KEYBOARD_TAP
    HapticEvent.Confirm -> if (sdkInt >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    HapticEvent.Reject -> if (sdkInt >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    HapticEvent.Heavy -> HapticFeedbackConstants.LONG_PRESS
}

/**
 * Plays through the view's haptic channel. It needs no `VIBRATE` permission, and it respects the phone's own touch-feedback
 * setting; on API 30+ the system backs CONFIRM and REJECT with vibration primitives where the hardware has them.
 */
class AndroidHaptics(private val view: View, private val sdkInt: Int = Build.VERSION.SDK_INT) : Haptics {
    private fun play(event: HapticEvent) {
        view.performHapticFeedback(feedbackConstantFor(event, sdkInt))
    }
    override fun tick() = play(HapticEvent.Tick)
    override fun light() = play(HapticEvent.Light)
    override fun confirm() = play(HapticEvent.Confirm)
    override fun reject() = play(HapticEvent.Reject)
    override fun heavy() = play(HapticEvent.Heavy)
}

/** Applies the in-app "Haptics" setting. [enabled] is read on every event, so a change applies at once. */
class GuardedHaptics(private val delegate: Haptics, private val enabled: () -> Boolean) : Haptics {
    override fun tick() { if (enabled()) delegate.tick() }
    override fun light() { if (enabled()) delegate.light() }
    override fun confirm() { if (enabled()) delegate.confirm() }
    override fun reject() { if (enabled()) delegate.reject() }
    override fun heavy() { if (enabled()) delegate.heavy() }
}

object NoOpHaptics : Haptics {
    override fun tick() = Unit
    override fun light() = Unit
    override fun confirm() = Unit
    override fun reject() = Unit
    override fun heavy() = Unit
}

val LocalHaptics = staticCompositionLocalOf<Haptics> { NoOpHaptics }
