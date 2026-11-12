package com.darshan.payflow.ui.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.ui.designsystem.components.PayFlowListRow
import com.darshan.payflow.ui.designsystem.modifiers.PressFeedback
import com.darshan.payflow.ui.designsystem.modifiers.rememberPressFeedback
import com.darshan.payflow.ui.designsystem.modifiers.trackPress
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A finger that starts a scroll on a row is scrolling, not pressing: the row lets go of its pressed look as soon as the
 * movement passes touch slop, instead of riding the whole fling scaled down and tinted. A still finger keeps the press.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class PressInScrollTest {
    @get:Rule val rule = createComposeRule()

    private fun showRows(reduceMotion: Boolean = false, fontScale: Float = 1f): () -> PressFeedback {
        lateinit var press: PressFeedback
        rule.mainClock.autoAdvance = false
        rule.setDs(fontScale = fontScale, reduceMotion = reduceMotion) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                press = rememberPressFeedback()
                Column(Modifier.trackPress(press).testTag(ROW)) { PayFlowListRow("Asha Rao", subtitle = "asha@okicici", onClick = {}) }
                repeat(30) { PayFlowListRow("Row $it", onClick = {}) }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        return { press }
    }

    private fun assertScrollReleases(reduceMotion: Boolean, fontScale: Float) {
        val press = showRows(reduceMotion, fontScale)
        val row = rule.onNodeWithTag(ROW)
        row.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(PRESS_BUDGET_MS)
        assertThat(press().progress).isGreaterThan(0.1f)
        // The finger moves well past touch slop: this is a scroll now, and the finger is still down.
        row.performTouchInput { moveBy(Offset(0f, -viewConfiguration.touchSlop * 4)) }
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        assertThat(press().progress).isWithin(0.01f).of(0f)
        row.performTouchInput { up() }
    }

    @Test fun aScrollReleasesThePress() = assertScrollReleases(reduceMotion = false, fontScale = 1f)
    @Test fun aScrollReleasesThePressAtLargeText() = assertScrollReleases(reduceMotion = false, fontScale = 1.3f)
    @Test fun aScrollReleasesThePressUnderReducedMotion() = assertScrollReleases(reduceMotion = true, fontScale = 1f)

    @Test
    fun aStillFingerKeepsThePressUntilItLifts() {
        val press = showRows()
        val row = rule.onNodeWithTag(ROW)
        row.performTouchInput { down(center) }
        // A little wobble under the slop is still a press.
        row.performTouchInput { moveBy(Offset(0f, viewConfiguration.touchSlop / 3)) }
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        assertThat(press().progress).isWithin(0.01f).of(1f)
        row.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        assertThat(press().progress).isWithin(0.01f).of(0f)
    }

    private companion object {
        const val ROW = "row"
        const val PRESS_BUDGET_MS = 100L
        const val SETTLE_MS = 1_500L
    }
}
