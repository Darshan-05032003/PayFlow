package com.darshan.payflow.ui.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.ui.designsystem.components.GhostButton
import com.darshan.payflow.ui.designsystem.components.PayFlowIconButton
import com.darshan.payflow.ui.designsystem.components.PrimaryButton
import com.darshan.payflow.ui.designsystem.components.TactileButton
import com.darshan.payflow.ui.designsystem.effects.HapticEvent
import com.darshan.payflow.ui.designsystem.modifiers.PressFeedback
import com.darshan.payflow.ui.designsystem.modifiers.rememberPressFeedback
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ButtonsTest {
    @get:Rule val rule = createComposeRule()

    private enum class Kind { Primary, Tactile, Ghost }

    @Composable
    private fun ButtonUnderTest(kind: Kind, enabled: Boolean = true, loading: Boolean = false, press: PressFeedback = rememberPressFeedback(), onClick: () -> Unit) {
        val modifier = Modifier.testTag(TAG)
        when (kind) {
            Kind.Primary -> PrimaryButton("Pay", onClick, modifier, enabled, loading, press = press)
            Kind.Tactile -> TactileButton("Pay", onClick, modifier, enabled, loading, press = press)
            Kind.Ghost -> GhostButton("Pay", onClick, modifier, enabled, loading, press = press)
        }
    }

    @Test
    fun a_click_calls_back_once_and_plays_one_light_haptic() {
        var clicks = 0
        val haptics = FakeHaptics()
        rule.setDs(haptics = haptics) { ButtonUnderTest(Kind.Primary) { clicks++ } }
        rule.onNodeWithText("Pay").performClick()
        assertThat(clicks).isEqualTo(1)
        assertThat(haptics.events).containsExactly(HapticEvent.Light)
    }

    @Test
    fun tactile_and_ghost_click_once_too() {
        var tactile = 0
        var ghost = 0
        rule.setDs {
            Column {
                TactileButton("One", { tactile++ })
                GhostButton("Two", { ghost++ })
            }
        }
        rule.onNodeWithText("One").performClick()
        rule.onNodeWithText("Two").performClick()
        assertThat(tactile).isEqualTo(1)
        assertThat(ghost).isEqualTo(1)
    }

    @Test
    fun rapid_repeat_clicks_inside_the_window_are_dropped() {
        var clicks = 0
        val haptics = FakeHaptics()
        rule.setDs(haptics = haptics) { ButtonUnderTest(Kind.Primary) { clicks++ } }
        repeat(5) { rule.onNodeWithText("Pay").performClick() }
        assertThat(clicks).isEqualTo(1)
        assertThat(haptics.events).hasSize(1)
    }

    @Test
    fun a_later_click_after_the_window_goes_through() {
        var clicks = 0
        rule.setDs { ButtonUnderTest(Kind.Primary) { clicks++ } }
        rule.onNodeWithText("Pay").performClick()
        ShadowSystemClock.advanceBy(Duration.ofMillis(600))
        rule.onNodeWithText("Pay").performClick()
        assertThat(clicks).isEqualTo(2)
    }

    @Test
    fun debounce_off_passes_every_click() {
        var clicks = 0
        rule.setDs { PrimaryButton("Pay", { clicks++ }, debounceMs = 0L) }
        repeat(3) { rule.onNodeWithText("Pay").performClick() }
        assertThat(clicks).isEqualTo(3)
    }

    @Test
    fun disabled_never_calls_back_and_says_so_to_talkback() {
        var clicks = 0
        rule.setDs { ButtonUnderTest(Kind.Primary, enabled = false) { clicks++ } }
        rule.onNodeWithTag(TAG).assertIsNotEnabled()
        rule.onNodeWithTag(TAG).performClick()
        assertThat(clicks).isEqualTo(0)
    }

    @Test
    fun loading_blocks_clicks_keeps_the_label_for_talkback_and_announces_loading() {
        var clicks = 0
        rule.mainClock.autoAdvance = false
        rule.setDs { ButtonUnderTest(Kind.Primary, loading = true) { clicks++ } }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag(TAG, useUnmergedTree = false).assertIsNotEnabled()
        rule.onNodeWithTag(TAG).performClick()
        assertThat(clicks).isEqualTo(0)
        rule.onNodeWithTag(TAG).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Loading"))
        rule.onNodeWithText("Pay").assertExists()
    }

    @Test
    fun a_two_finger_tap_is_one_action() {
        var clicks = 0
        rule.setDs { ButtonUnderTest(Kind.Primary) { clicks++ } }
        rule.onNodeWithText("Pay").performMultiTouchTap()
        assertThat(clicks).isAtMost(1)
    }

    private fun SemanticsNodeInteraction.performMultiTouchTap() = performTouchInput {
        down(0, center)
        down(1, center + Offset(10f, 0f))
        up(1)
        up(0)
    }

    @Test
    fun the_accessibility_click_goes_through_the_same_debounce() {
        var clicks = 0
        rule.setDs { ButtonUnderTest(Kind.Primary) { clicks++ } }
        repeat(3) { rule.onNodeWithTag(TAG).performSemanticsAction(SemanticsActions.OnClick) }
        assertThat(clicks).isEqualTo(1)
    }

    private fun assertPressFeedback(kind: Kind, reduceMotion: Boolean) {
        lateinit var press: PressFeedback
        rule.mainClock.autoAdvance = false
        rule.setDs(reduceMotion = reduceMotion) {
            press = rememberPressFeedback()
            ButtonUnderTest(kind, press = press) {}
        }
        rule.mainClock.advanceTimeByFrame()
        assertThat(press.progress).isEqualTo(0f)
        rule.onNodeWithTag(TAG).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(PRESS_BUDGET_MS)
        assertThat(press.progress).isGreaterThan(0.1f)
        rule.onNodeWithTag(TAG).performTouchInput { up() }
        rule.mainClock.advanceTimeBy(1_500)
        assertThat(press.progress).isWithin(0.01f).of(0f)
    }

    @Test fun primary_press_shows_within_100ms_and_returns() = assertPressFeedback(Kind.Primary, reduceMotion = false)
    @Test fun tactile_press_shows_within_100ms_and_returns() = assertPressFeedback(Kind.Tactile, reduceMotion = false)
    @Test fun ghost_press_shows_within_100ms_and_returns() = assertPressFeedback(Kind.Ghost, reduceMotion = false)
    @Test fun press_feedback_stays_under_reduced_motion() = assertPressFeedback(Kind.Primary, reduceMotion = true)

    @Test
    fun a_disabled_button_shows_no_press() {
        lateinit var press: PressFeedback
        rule.mainClock.autoAdvance = false
        rule.setDs {
            press = rememberPressFeedback()
            ButtonUnderTest(Kind.Primary, enabled = false, press = press) {}
        }
        rule.onNodeWithTag(TAG).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(PRESS_BUDGET_MS)
        assertThat(press.progress).isEqualTo(0f)
    }

    @Test
    fun label_is_not_clipped_at_large_font_scale() {
        rule.setDs(fontScale = 1.3f) {
            PrimaryButton("A long label that needs a second line at a large font size, yes", {}, Modifier.fillMaxWidth().testTag(TAG))
        }
        rule.onNodeWithTag(TAG).assertHeightIsAtLeast(56.dp)
        rule.onNodeWithText("A long label", substring = true).assertInside(rule.onNodeWithTag(TAG))
    }

    @Test
    fun all_kinds_meet_the_touch_target_at_large_font_scale() {
        rule.setDs(fontScale = 1.3f) {
            Column {
                PrimaryButton("P", {}, Modifier.testTag("p"))
                TactileButton("T", {}, Modifier.testTag("t"))
                GhostButton("G", {}, Modifier.testTag("g"))
                PayFlowIconButton(Icons.Outlined.Share, "Share", {}, Modifier.testTag("i"))
            }
        }
        for (tag in listOf("p", "t", "g", "i")) {
            rule.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).assertIsDisplayed()
        }
    }

    @Test
    fun the_icon_button_names_itself_and_is_a_button() {
        var clicks = 0
        val haptics = FakeHaptics()
        rule.setDs(haptics = haptics) { PayFlowIconButton(Icons.Outlined.Share, "Share receipt", { clicks++ }) }
        rule.onNodeWithContentDescription("Share receipt", useUnmergedTree = true).assertExists()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertIsEnabled().performClick()
        assertThat(clicks).isEqualTo(1)
        assertThat(haptics.events).containsExactly(HapticEvent.Light)
    }

    private companion object {
        const val TAG = "button"

        /** Budget from the brief: press feedback within 100ms. One frame less leaves a margin. */
        const val PRESS_BUDGET_MS = 84L
    }
}
