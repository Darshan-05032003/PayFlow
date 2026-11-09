package com.darshan.payflow.ui.designsystem.modifiers

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionToken
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import kotlinx.coroutines.delay

/**
 * Staggered arrival for lists and groups: fades in and rises 12dp, delayed by `motion.staggerDelayMs(index)`.
 * Set [play] false for items that should appear at once, such as rows coming back after a scroll. With reduced motion the
 * rise is dropped and only a short fade remains. The content is composed and tappable from the first frame.
 */
@Composable
fun StaggeredEntrance(index: Int, modifier: Modifier = Modifier, play: Boolean = true, content: @Composable () -> Unit) {
    val motion = LocalMotion.current
    val progress = remember { Animatable(if (play) 0f else 1f) }
    val rise = with(LocalDensity.current) { Spacing.sm.toPx() }
    LaunchedEffect(Unit) {
        if (!play) return@LaunchedEffect
        delay(motion.staggerDelayMs(index).toLong())
        progress.animateTo(1f, motion.spec(MotionToken.Small))
    }
    val reduced = motion.reduced
    Box(
        modifier.graphicsLayer {
            alpha = progress.value
            translationY = if (reduced) 0f else (1f - progress.value) * rise
        },
    ) { content() }
}
