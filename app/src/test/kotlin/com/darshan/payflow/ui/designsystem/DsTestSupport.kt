package com.darshan.payflow.ui.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Density
import com.darshan.payflow.ui.designsystem.effects.Haptics
import com.darshan.payflow.ui.theme.PayFlowTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage

/** Sets themed content with the options design-system tests vary: font scale, reduced motion, theme, fake haptics. */
fun ComposeContentTestRule.setDs(
    fontScale: Float = 1f,
    reduceMotion: Boolean = false,
    dark: Boolean = false,
    haptics: Haptics = FakeHaptics(),
    content: @Composable BoxScope.() -> Unit,
) {
    setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            PayFlowTheme(darkTheme = dark, reduceMotion = reduceMotion, haptics = haptics) {
                Box(content = content)
            }
        }
    }
}

/**
 * Applies a state change the way a real frame would see it. With a paused clock nothing else delivers the snapshot to the
 * recomposer before the next `advanceTimeBy`, so without this an animation test could pass without animating.
 */
fun commitState(change: () -> Unit) {
    change()
    Snapshot.sendApplyNotifications()
}

/** Successful recomposition cycles so far. A growing number during an animation means it recomposes every frame. */
fun recompositionCycles(): Long = Recomposer.runningRecomposers.value.sumOf { it.changeCount }

/** Fails if this node pokes out of [parent]: the check behind "no clipping at 1.3×". */
fun SemanticsNodeInteraction.assertInside(parent: SemanticsNodeInteraction) {
    val inner = fetchSemanticsNode().boundsInRoot
    val outer = parent.fetchSemanticsNode().boundsInRoot
    val slack = 1f
    assertThat(inner.left).isAtLeast(outer.left - slack)
    assertThat(inner.top).isAtLeast(outer.top - slack)
    assertThat(inner.right).isAtMost(outer.right + slack)
    assertThat(inner.bottom).isAtMost(outer.bottom + slack)
}


/**
 * Fails if this text node's layout is cut: lines past a cap, or an ellipsis. What "readable at
 * large font sizes" means for text that must stay whole.
 */
fun SemanticsNodeInteraction.assertTextWhole() {
    val node = fetchSemanticsNode()
    val layouts = mutableListOf<TextLayoutResult>()
    node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
    val layout = layouts.single()
    val label = layout.layoutInput.text.text
    // Not didOverflowWidth: wrap-content text is laid out at the full width and reads as "overflowing" when it is narrower.
    assertWithMessage("\"$label\" is cut at its line cap").that(layout.didOverflowHeight || layout.multiParagraph.didExceedMaxLines).isFalse()
    for (line in 0 until layout.lineCount) assertWithMessage("\"$label\" is ellipsized on line $line").that(layout.isLineEllipsized(line)).isFalse()
}
