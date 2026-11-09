package com.darshan.payflow.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionToken
import com.darshan.payflow.ui.designsystem.motion.PayFlowMotion

/** Turns a [ScreenTransition] into the four animations Navigation asks for, using only the design system's motion helpers. */
class ShellTransitions(private val motion: PayFlowMotion) {
    fun enter(from: ShellRoute?, to: ShellRoute?): EnterTransition = when (ShellLogic.transition(from, to)) {
        ScreenTransition.SharedAxis -> motion.sharedAxisX(forward = true).targetContentEnter
        ScreenTransition.Rise -> if (to == ShellRoute.Scan) motion.enter() else motion.fadeThrough().targetContentEnter
        ScreenTransition.FadeThrough, ScreenTransition.ContainerTransform -> motion.fadeThrough().targetContentEnter
    }

    fun exit(from: ShellRoute?, to: ShellRoute?): ExitTransition = when (ShellLogic.transition(from, to)) {
        ScreenTransition.SharedAxis -> motion.sharedAxisX(forward = true).initialContentExit
        ScreenTransition.FadeThrough, ScreenTransition.Rise, ScreenTransition.ContainerTransform -> motion.fadeThrough().initialContentExit
    }

    fun popEnter(from: ShellRoute?, to: ShellRoute?): EnterTransition = when (ShellLogic.transition(from, to)) {
        ScreenTransition.SharedAxis -> motion.sharedAxisX(forward = false).targetContentEnter
        ScreenTransition.FadeThrough, ScreenTransition.Rise, ScreenTransition.ContainerTransform -> motion.fadeThrough().targetContentEnter
    }

    fun popExit(from: ShellRoute?, to: ShellRoute?): ExitTransition = when (ShellLogic.transition(from, to)) {
        ScreenTransition.SharedAxis -> motion.sharedAxisX(forward = false).initialContentExit
        ScreenTransition.Rise -> if (from == ShellRoute.Scan) motion.exit() else motion.fadeThrough().initialContentExit
        ScreenTransition.FadeThrough, ScreenTransition.ContainerTransform -> motion.fadeThrough().initialContentExit
    }
}

/** Set by the shell around the NavHost, null everywhere else (previews, screen tests), where the modifier below does nothing. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = compositionLocalOf<AnimatedContentScope?> { null }

/** The shared-element key of the scanner: Home's Scan & Pay action grows into it. */
const val SCAN_CONTAINER_KEY = "scan"

/** The shared-element key for one payment: its row and its detail screen carry the same one. */
fun paymentContainerKey(paymentId: String): String = "payment:$paymentId"

/**
 * Marks the element as one end of a container transform: the element with the same [key] on the other screen is what it
 * grows into. Reduced motion, or a screen outside the shell, leaves the element as it is and the screens simply fade.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.containerTransform(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current
    val scope = LocalNavAnimatedScope.current
    val motion = LocalMotion.current
    if (shared == null || scope == null || motion.reduced) return this
    val bounds = remember(motion) { BoundsTransform { _, _ -> motion.spec<Rect>(MotionToken.Screen) } }
    return with(shared) {
        this@containerTransform.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = scope,
            boundsTransform = bounds,
        )
    }
}
