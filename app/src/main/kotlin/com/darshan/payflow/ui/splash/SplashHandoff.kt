package com.darshan.payflow.ui.splash

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.animation.core.Easing
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreenViewProvider
import com.darshan.payflow.ui.designsystem.motion.MotionDurations
import com.darshan.payflow.ui.designsystem.motion.MotionEasing
import com.darshan.payflow.ui.designsystem.motion.MotionLevel
import com.darshan.payflow.ui.designsystem.motion.resolveMotionLevel

/** The splash's rules, without the Android classes. */
object SplashPolicy {
    /** A settings read that hangs must not trap a payments app on its splash. The lock still covers the app afterwards. */
    const val MAX_HOLD_MS = 2_500L

    /** How long a finished launch may wait for the icon animation to end. It never delays a slow launch further. */
    const val MAX_ICON_WAIT_MS = MotionDurations.ScreenFast.toLong()

    /** The icon grows by this much as the splash lifts off, as if the app were coming towards the user. */
    const val EXIT_ICON_SCALE = 1.2f

    fun keepOnScreen(themeLoaded: Boolean, lockResolved: Boolean, heldForMs: Long): Boolean =
        (!themeLoaded || !lockResolved) && heldForMs < MAX_HOLD_MS

    /** Lets a running icon animation finish, so the ring is never cut off half-drawn. */
    fun exitDelayMs(nowMs: Long, iconStartMs: Long, iconDurationMs: Long): Long =
        (iconStartMs + iconDurationMs - nowMs).coerceIn(0, MAX_ICON_WAIT_MS)
}

/**
 * Holds the splash until the app knows its theme and whether it is locked, then fades the graphite away over the first real
 * frame while the icon grows slightly. Reduced motion removes it at once. If the system never hands the splash over, as
 * happens on warm starts, the listener simply doesn't run and nothing waits for it.
 */
fun SplashScreen.holdUntilReadyThenHandOff(themeLoaded: () -> Boolean, lockResolved: () -> Boolean, reduceMotion: () -> Boolean) {
    val start = SystemClock.uptimeMillis()
    setKeepOnScreenCondition { SplashPolicy.keepOnScreen(themeLoaded(), lockResolved(), SystemClock.uptimeMillis() - start) }
    setOnExitAnimationListener { provider ->
        val systemScale = if (ValueAnimator.areAnimatorsEnabled()) 1f else 0f
        if (resolveMotionLevel(reduceMotion(), systemScale) == MotionLevel.Reduced) provider.remove() else provider.playExit()
    }
}

private fun SplashScreenViewProvider.playExit() {
    val delay = SplashPolicy.exitDelayMs(SystemClock.uptimeMillis(), iconAnimationStartMillis, iconAnimationDurationMillis)
    val duration = MotionDurations.Small.toLong()
    iconView.animate()
        .scaleX(SplashPolicy.EXIT_ICON_SCALE)
        .scaleY(SplashPolicy.EXIT_ICON_SCALE)
        .setStartDelay(delay)
        .setDuration(duration)
        .setInterpolator(MotionEasing.Emphasized.curve.asInterpolator())
        .start()
    view.animate()
        .alpha(0f)
        .setStartDelay(delay)
        .setDuration(duration)
        .setInterpolator(MotionEasing.Standard.curve.asInterpolator())
        .withEndAction { remove() }
        .start()
}

private fun Easing.asInterpolator() = TimeInterpolator { transform(it) }
