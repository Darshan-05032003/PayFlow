package com.darshan.payflow.ui.designsystem.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/** Every duration in the app, in milliseconds. Screens read them through [MotionToken], not directly. */
object MotionDurations {
    const val MicroFast = 90
    const val Micro = 120
    const val MicroSlow = 150
    const val SmallFast = 200
    const val Small = 240
    const val SmallSlow = 300
    const val ScreenFast = 350
    const val Screen = 420
    const val ScreenSlow = 500

    /** Delay between neighbouring items of a staggered entrance. */
    const val Stagger = 40

    /** A long list stops adding delay here, so item 200 does not arrive 8 seconds late. */
    const val StaggerMax = 320

    /** Every essential transition becomes a fade this short under reduced motion. */
    const val ReducedFade = 150

    const val Sheen = 900
    const val Shimmer = 1400
    const val Ambient = 2400
    const val Shake = 420

    /** Gap between the steps of a sequence that plays one item at a time, such as the Security centre's checks. */
    const val SequenceStep = 280
}

/** The only easing curves. There is deliberately no linear curve: UI motion always has weight. */
enum class MotionEasing(val curve: Easing) {
    /** Default for things that move within the screen. */
    Emphasized(CubicBezierEasing(0.2f, 0f, 0f, 1f)),

    /** Things arriving: quick start, long settle. */
    EmphasizedDecelerate(CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)),

    /** Things leaving: gentle start, quick exit. */
    EmphasizedAccelerate(CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)),

    /** Fades and ambient loops. */
    Standard(CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
}

/** A spring by its physical values, kept separate from Compose's generic `SpringSpec` so it can be compared in tests. */
data class SpringToken(val stiffness: Float, val dampingRatio: Float) {
    fun <T> spec(): SpringSpec<T> = spring(dampingRatio = dampingRatio, stiffness = stiffness)
}

/** Aurum Noir springs: calm and weighted. Anything touched or dragged uses one of these. */
object MotionSprings {
    /** Press-in, small state changes. No overshoot. */
    val Snappy = SpringToken(stiffness = 1200f, dampingRatio = 1.0f)

    /** Release, arrivals that should feel alive. Slight overshoot. */
    val Bouncy = SpringToken(stiffness = 420f, dampingRatio = 0.72f)

    /** Large or heavy things: sheets, rolling digits. */
    val Gentle = SpringToken(stiffness = 160f, dampingRatio = 0.95f)
}

/** How much a motion matters, which decides what happens to it under reduced motion. */
enum class MotionRole {
    /** Confirms a touch. Stays, but short and without overshoot. */
    Feedback,

    /** Explains a change of state or place. Becomes a short fade. */
    Essential,

    /** Pure atmosphere. Turned off. */
    Decorative,
}

/** What a [MotionToken] resolves to. Pure data: unit tests assert on it without a clock. */
sealed interface MotionPlan {
    data class Spring(val spring: SpringToken) : MotionPlan
    data class Tween(val durationMs: Int, val easing: MotionEasing) : MotionPlan
    data class Fade(val durationMs: Int) : MotionPlan
    data object None : MotionPlan
}

/** The motion vocabulary. Components and screens name one of these; [PayFlowMotion] decides how it plays. */
enum class MotionToken(val role: MotionRole, val fullPlan: MotionPlan) {
    /** Finger down on anything tappable. */
    Press(MotionRole.Feedback, MotionPlan.Spring(MotionSprings.Snappy)),

    /** Finger up: returns with a little life. */
    Release(MotionRole.Feedback, MotionPlan.Spring(MotionSprings.Bouncy)),

    /** A small state flip: toggle thumb, nav pill. */
    Snap(MotionRole.Essential, MotionPlan.Spring(MotionSprings.Snappy)),

    /** An element arriving with energy: chips, toasts, badges. */
    Bounce(MotionRole.Essential, MotionPlan.Spring(MotionSprings.Bouncy)),

    /** A large element coming to rest: sheet, odometer digits. */
    Settle(MotionRole.Essential, MotionPlan.Spring(MotionSprings.Gentle)),

    Micro(MotionRole.Essential, MotionPlan.Tween(MotionDurations.Micro, MotionEasing.Emphasized)),
    Small(MotionRole.Essential, MotionPlan.Tween(MotionDurations.Small, MotionEasing.Emphasized)),

    /** Screen-sized change arriving. */
    Screen(MotionRole.Essential, MotionPlan.Tween(MotionDurations.Screen, MotionEasing.EmphasizedDecelerate)),

    /** Screen-sized change leaving. */
    ScreenExit(MotionRole.Essential, MotionPlan.Tween(MotionDurations.SmallFast, MotionEasing.EmphasizedAccelerate)),

    /** Error nudge on an input. */
    Shake(MotionRole.Essential, MotionPlan.Tween(MotionDurations.Shake, MotionEasing.Standard)),

    /** Breathing rings, slow pulses, the branded loader. */
    Ambient(MotionRole.Decorative, MotionPlan.Tween(MotionDurations.Ambient, MotionEasing.Standard)),
    Shimmer(MotionRole.Decorative, MotionPlan.Tween(MotionDurations.Shimmer, MotionEasing.Standard)),
    Sheen(MotionRole.Decorative, MotionPlan.Tween(MotionDurations.Sheen, MotionEasing.Emphasized)),
}
