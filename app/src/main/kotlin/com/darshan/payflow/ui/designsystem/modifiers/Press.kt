package com.darshan.payflow.ui.designsystem.modifiers

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionToken
import com.darshan.payflow.ui.designsystem.motion.PayFlowMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Press state of a tappable element: [progress] runs 0 (rest) → 1 (fully pressed). Read it inside `graphicsLayer` or
 * `drawBehind`, never in composition. Hoist it to drive something else from the same press.
 */
@Stable
class PressFeedback internal constructor(initial: Float, private val scope: CoroutineScope, private val motion: PayFlowMotion) {
    private val animatable = Animatable(initial)
    val progress: Float get() = animatable.value

    /** Finger down. Starts on the next frame: feedback is never delayed by the tap timeout `clickable` applies in lists. */
    fun press() {
        scope.launch { animatable.animateTo(1f, motion.spec(MotionToken.Press)) }
    }

    fun release() {
        scope.launch { animatable.animateTo(0f, motion.spec(MotionToken.Release)) }
    }
}

/** [initial] 1f renders the pressed look without a finger, for the catalog and screenshot tests. */
@Composable
fun rememberPressFeedback(initial: Float = 0f): PressFeedback {
    val scope = rememberCoroutineScope()
    val motion = LocalMotion.current
    return remember(motion) { PressFeedback(initial, scope, motion) }
}

/**
 * Feeds [state] from raw pointer events on the Initial pass, so it fires before children and scrollers and does not consume
 * anything: `clickable`, scrolling and drags keep working.
 */
fun Modifier.trackPress(state: PressFeedback, enabled: Boolean = true): Modifier =
    if (!enabled) {
        this
    } else {
        pointerInput(state) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                state.press()
                waitForUpOrCancellation(PointerEventPass.Initial)
                state.release()
            }
        }
    }

/** Scales the element down by [state], in the layer so no recomposition happens. */
fun Modifier.pressScale(state: PressFeedback, pressedScale: Float = PRESSED_SCALE): Modifier = graphicsLayer {
    val s = 1f - (1f - pressedScale) * state.progress
    scaleX = s
    scaleY = s
}

const val PRESSED_SCALE = 0.97f
