package com.darshan.payflow.ui.designsystem.motion

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntOffset

enum class MotionLevel { Full, Reduced }

/**
 * Pure rule for the motion level. Reduced when the in-app setting is on, or when the system animator scale is 0.
 * A broken scale value counts as "off": the safe reading for someone who may have motion sensitivity.
 */
fun resolveMotionLevel(reduceMotionSetting: Boolean, systemAnimatorScale: Float): MotionLevel =
    if (reduceMotionSetting || !(systemAnimatorScale > 0f)) MotionLevel.Reduced else MotionLevel.Full

/**
 * The motion scheme: read it with `PayFlowTheme.motion` or [LocalMotion].
 *
 * Decorative motion checks [decorativeEnabled] and does nothing when it is false. Everything else asks [spec] or the
 * transition helpers, which turn into short fades under reduced motion. A control flow must never depend on an animation
 * finishing: reduced motion changes how a step looks, never whether it happens.
 */
@Immutable
class PayFlowMotion(val level: MotionLevel) {
    val reduced: Boolean get() = level == MotionLevel.Reduced
    val decorativeEnabled: Boolean get() = level == MotionLevel.Full

    fun plan(token: MotionToken): MotionPlan = when {
        level == MotionLevel.Full -> token.fullPlan
        token.role == MotionRole.Decorative -> MotionPlan.None
        token.role == MotionRole.Feedback -> MotionPlan.Tween(MotionDurations.MicroFast, MotionEasing.Standard)
        else -> MotionPlan.Fade(MotionDurations.ReducedFade)
    }

    fun <T> spec(token: MotionToken): FiniteAnimationSpec<T> = when (val plan = plan(token)) {
        is MotionPlan.Spring -> plan.spring.spec()
        is MotionPlan.Tween -> tween(plan.durationMs, easing = plan.easing.curve)
        is MotionPlan.Fade -> tween(plan.durationMs, easing = MotionEasing.Standard.curve)
        MotionPlan.None -> snap()
    }

    /** Delay before item [index] of a staggered entrance starts. */
    fun staggerDelayMs(index: Int): Int =
        if (reduced) 0 else (index.coerceAtLeast(0).toLong() * MotionDurations.Stagger).coerceAtMost(MotionDurations.StaggerMax.toLong()).toInt()

    /** Default arrival of an element inside a screen: rises slightly and fades in. */
    fun enter(): EnterTransition =
        if (reduced) fadeIn(spec(MotionToken.Micro))
        else fadeIn(spec(MotionToken.Small)) + slideInVertically(spec<IntOffset>(MotionToken.Bounce)) { it / 8 }

    fun exit(): ExitTransition =
        if (reduced) fadeOut(spec(MotionToken.Micro))
        else fadeOut(spec(MotionToken.ScreenExit)) + slideOutVertically(spec<IntOffset>(MotionToken.ScreenExit)) { it / 8 }

    /** Pop-in for small elements such as a badge or a check. */
    fun popIn(): EnterTransition =
        if (reduced) fadeIn(spec(MotionToken.Micro)) else fadeIn(spec(MotionToken.Micro)) + scaleIn(spec(MotionToken.Bounce), initialScale = 0.8f)

    /** Sibling screens: incoming slides from the side the user is heading to. */
    fun sharedAxisX(forward: Boolean): ContentTransform {
        if (reduced) return fadeThrough()
        val direction = if (forward) 1 else -1
        return (fadeIn(spec(MotionToken.Screen)) + slideInHorizontally(spec<IntOffset>(MotionToken.Screen)) { width -> direction * width / 10 }) togetherWith
            (fadeOut(spec(MotionToken.ScreenExit)) + slideOutHorizontally(spec<IntOffset>(MotionToken.ScreenExit)) { width -> -direction * width / 10 })
    }

    /** Unrelated content swapping in the same place. */
    fun fadeThrough(): ContentTransform =
        ContentTransform(fadeIn(spec(MotionToken.Small)), fadeOut(spec(MotionToken.Micro)), sizeTransform = SizeTransform(clip = false))
}

/** Full motion unless the theme says otherwise, so a component previewed outside the theme still works. */
val LocalMotion = staticCompositionLocalOf { PayFlowMotion(MotionLevel.Full) }
