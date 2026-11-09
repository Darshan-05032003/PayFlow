package com.darshan.payflow.ui.lock

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionToken

/**
 * The only way to the app. [app] is not in the composition at all until [state] is [LockUiState.Unlocked], and it leaves in
 * the same recomposition that a lock arrives, so there is nothing behind the lock to read, tap or catch sight of.
 *
 * Unlocking opens the app through a circle growing from the control that unlocked it, over a frozen copy of the lock screen.
 * That copy takes no input and is hidden from accessibility: the app is already open underneath, and the reveal never delays
 * it. Reduced motion turns the circle into a short fade. Opening with no lock to leave (no PIN set) has no reveal.
 */
@Composable
fun AppGate(
    state: LockUiState,
    unlockMethod: UnlockMethod?,
    lockCallbacks: LockCallbacks,
    modifier: Modifier = Modifier,
    app: @Composable (pinConfigured: Boolean) -> Unit,
) {
    val motion = LocalMotion.current
    val anchors = remember { RevealAnchors() }
    val lastGate = remember { arrayOfNulls<LockUiState>(1) }
    if (state !is LockUiState.Unlocked) lastGate[0] = state
    val retained = lastGate[0]
    var finished by remember { mutableStateOf<LockUiState?>(null) }
    val open = state is LockUiState.Unlocked
    val revealing = open && (retained is LockUiState.Locked || retained is LockUiState.Setup) && finished !== retained
    val progress = remember { Animatable(0f) }

    LaunchedEffect(open) {
        if (!open) {
            progress.snapTo(0f)
            finished = null
        } else if (revealing) {
            progress.snapTo(0f)
            progress.animateTo(1f, motion.spec(MotionToken.Screen))
            finished = retained
        }
    }

    Box(modifier.fillMaxSize()) {
        if (!open || revealing) {
            val shown = if (open) retained ?: state else state
            LockScreen(
                state = shown,
                callbacks = if (open) INERT else lockCallbacks,
                modifier = if (open) Modifier.clearAndSetSemantics {} else Modifier,
                anchors = anchors,
                interactive = !open,
            )
        }
        if (state is LockUiState.Unlocked) {
            val isRevealing by rememberUpdatedState(revealing)
            val method by rememberUpdatedState(unlockMethod)
            Box(
                Modifier.fillMaxSize().revealClip(
                    reduced = motion.reduced,
                    fraction = { if (isRevealing) progress.value else 1f },
                    origin = { size -> LockMotionLogic.revealOrigin(method, anchors.pin, anchors.biometric, size) },
                ),
            ) {
                app(state.pinConfigured)
            }
        }
    }
}

private val INERT = LockCallbacks()

/** The circle (or, when reduced, the fade), read entirely in the draw and layer phases: nothing recomposes per frame. */
private fun Modifier.revealClip(reduced: Boolean, fraction: () -> Float, origin: (Size) -> Offset): Modifier =
    graphicsLayer { if (reduced) alpha = fraction() }
        .drawWithCache {
            val path = Path()
            onDrawWithContent {
                val f = fraction()
                if (reduced || f >= 1f) {
                    drawContent()
                } else {
                    val centre = origin(size)
                    path.rewind()
                    path.addOval(Rect(centre, LockMotionLogic.revealRadius(centre, size) * f))
                    clipPath(path) { this@onDrawWithContent.drawContent() }
                }
            }
        }
