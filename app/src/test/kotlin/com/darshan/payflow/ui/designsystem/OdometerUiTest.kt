package com.darshan.payflow.ui.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.ui.designsystem.components.AmountDecimals
import com.darshan.payflow.ui.designsystem.components.AmountOdometer
import com.darshan.payflow.ui.designsystem.components.AmountSign
import com.darshan.payflow.ui.designsystem.components.AmountText
import com.darshan.payflow.ui.designsystem.components.OdometerLogic
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class OdometerUiTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun exposes_the_formatted_amount_and_speaks_it() {
        rule.setDs { AmountOdometer(1_23_456_78L, Modifier.testTag(TAG)) }
        rule.onNodeWithText("₹1,23,456.78").assertExists()
        rule.onNodeWithTag(TAG).assertContentDescriptionEquals("₹1,23,456.78")
    }

    @Test
    fun a_debit_is_spoken_as_minus_while_it_shows_a_true_minus() {
        rule.setDs { AmountOdometer(-1_250_00L, Modifier.testTag(TAG)) }
        rule.onNodeWithText("−₹1,250").assertExists()
        rule.onNodeWithTag(TAG).assertContentDescriptionEquals("minus ₹1,250")
    }

    @Test
    fun hidden_reveals_nothing_to_talkback_or_tests() {
        rule.setDs { AmountOdometer(1_23_456_78L, Modifier.testTag(TAG), hidden = true) }
        rule.onNodeWithTag(TAG).assertContentDescriptionEquals("Amount hidden")
        rule.onNodeWithText("₹1,23,456.78").assertDoesNotExist()
    }

    @Test
    fun rolls_to_the_new_value_passing_through_a_different_picture() {
        var amount by mutableLongStateOf(1_000_00L)
        rule.mainClock.autoAdvance = false
        rule.setDs { Box(Modifier.testTag(TAG)) { AmountOdometer(amount) } }
        rule.mainClock.advanceTimeBy(1_000)
        val start = rule.onNodeWithTag(TAG).captureToImage().asAndroidBitmap()

        commitState { amount = 9_876_00L }
        rule.mainClock.advanceTimeBy(FRAME_16 * 12)
        val middle = rule.onNodeWithTag(TAG).captureToImage().asAndroidBitmap()
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        val end = rule.onNodeWithTag(TAG).captureToImage().asAndroidBitmap()

        assertThat(middle.sameAs(start)).isFalse()
        assertThat(middle.sameAs(end)).isFalse()
        rule.onNodeWithText("₹9,876").assertExists()
    }

    @Test
    fun reduced_motion_lands_on_the_new_value_on_the_next_frame() {
        var amount by mutableLongStateOf(1_000_00L)
        rule.mainClock.autoAdvance = false
        rule.setDs(reduceMotion = true) { Box(Modifier.testTag(TAG)) { AmountOdometer(amount) } }
        rule.mainClock.advanceTimeBy(100)
        commitState { amount = 2_000_00L }
        rule.mainClock.advanceTimeBy(FRAME_16 * 2)
        val first = rule.onNodeWithTag(TAG).captureToImage().asAndroidBitmap()
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        val settled = rule.onNodeWithTag(TAG).captureToImage().asAndroidBitmap()
        assertThat(first.sameAs(settled)).isTrue()
    }

    @Test
    fun a_roll_does_not_recompose_per_frame() {
        var amount by mutableLongStateOf(1_111_11L)
        rule.mainClock.autoAdvance = false
        rule.setDs { AmountOdometer(amount, decimals = AmountDecimals.Always) }
        rule.mainClock.advanceTimeBy(1_000)
        commitState { amount = 8_888_88L }
        rule.mainClock.advanceTimeBy(FRAME_16 * 3)
        val moving = rule.onRoot().captureToImage().asAndroidBitmap()
        val before = recompositionCycles()
        repeat(FRAMES) { rule.mainClock.advanceTimeByFrame() }
        assertThat(recompositionCycles() - before).isAtMost(ALLOWED_CYCLES)
        // Proves the frames above really animated, so the flat recomposition count means something.
        assertThat(rule.onRoot().captureToImage().asAndroidBitmap().sameAs(moving)).isFalse()
    }

    /** Control: shows the counter behind the "no recomposition" checks really moves when something recomposes per frame. */
    @Test
    fun the_recomposition_counter_catches_a_value_read_in_composition() {
        var target by mutableStateOf(0f)
        rule.mainClock.autoAdvance = false
        rule.setDs {
            val value by animateFloatAsState(target, tween(1_000), label = "control")
            Text(value.toString())
        }
        rule.mainClock.advanceTimeBy(100)
        commitState { target = 1f }
        rule.mainClock.advanceTimeBy(48)
        val before = recompositionCycles()
        repeat(FRAMES) { rule.mainClock.advanceTimeByFrame() }
        assertThat(recompositionCycles() - before).isAtLeast(FRAMES.toLong() / 2)
    }

    @Test
    fun new_value_interrupting_a_roll_ends_on_the_newest_value() {
        var amount by mutableLongStateOf(1_000_00L)
        rule.mainClock.autoAdvance = false
        rule.setDs { Box(Modifier.testTag(TAG)) { AmountOdometer(amount) } }
        rule.mainClock.advanceTimeBy(500)
        commitState { amount = 5_000_00L }
        rule.mainClock.advanceTimeBy(FRAME_16 * 6)
        commitState { amount = 3_000_00L }
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        val interrupted = rule.onNodeWithTag(TAG).captureToImage().asAndroidBitmap()
        rule.onNodeWithText("₹3,000").assertExists()

        rule.mainClock.advanceTimeBy(SETTLE_MS)
        assertThat(rule.onNodeWithTag(TAG).captureToImage().asAndroidBitmap().sameAs(interrupted)).isTrue()
    }

    @Test
    fun the_largest_amount_fits_a_narrow_slot_at_large_font_scale() {
        rule.setDs(fontScale = 1.3f) {
            Box(Modifier.width(200.dp).testTag("slot")) { AmountOdometer(OdometerLogic.MAX_PAISE, Modifier.testTag(TAG), decimals = AmountDecimals.Always) }
        }
        rule.onNodeWithTag(TAG).assertInside(rule.onNodeWithTag("slot"))
    }

    @Test
    fun the_largest_amount_fits_a_full_width_phone_at_normal_scale_without_shrinking_much() {
        rule.setDs { Box(Modifier.width(371.dp)) { AmountOdometer(OdometerLogic.MAX_PAISE, Modifier.testTag(TAG), decimals = AmountDecimals.Always) } }
        rule.onNodeWithTag(TAG).assertWidthIsAtLeast(200.dp)
    }

    @Test
    fun static_amount_shares_format_and_speech() {
        rule.setDs { AmountText(-300_50L, Modifier.testTag(TAG), sign = AmountSign.Always) }
        rule.onNodeWithTag(TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("minus ₹300.50")))
        rule.onNodeWithText("−₹300.50").assertExists()
    }

    private companion object {
        const val TAG = "amount"
        const val FRAME_16 = 16L
        const val SETTLE_MS = 3_000L
        const val FRAMES = 30

        /** A burst at most: never one per frame. */
        const val ALLOWED_CYCLES = 3L
    }
}
