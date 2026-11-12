package com.darshan.payflow.ui.designsystem

import com.darshan.payflow.core.Money
import com.darshan.payflow.ui.designsystem.components.AmountDecimals
import com.darshan.payflow.ui.designsystem.components.AmountSign
import com.darshan.payflow.ui.designsystem.components.OdometerLogic
import com.darshan.payflow.ui.designsystem.components.OdometerSlot
import com.darshan.payflow.ui.designsystem.components.RollDirection
import com.google.common.truth.Truth.assertThat
import kotlin.math.ceil
import org.junit.Test

class OdometerLogicTest {
    private fun text(paise: Long, decimals: AmountDecimals = AmountDecimals.Always, sign: AmountSign = AmountSign.NegativeOnly) =
        OdometerLogic.slots(paise, decimals, sign).joinToString("") { it.char.toString() }

    @Test
    fun formats_with_indian_grouping() {
        assertThat(text(0)).isEqualTo("₹0.00")
        assertThat(text(5)).isEqualTo("₹0.05")
        assertThat(text(99_999)).isEqualTo("₹999.99")
        assertThat(text(1_00_000)).isEqualTo("₹1,000.00")
        assertThat(text(1_23_45_678)).isEqualTo("₹1,23,456.78")
        assertThat(text(12_34_56_789)).isEqualTo("₹12,34,567.89")
    }

    @Test
    fun handles_the_largest_supported_amount() {
        assertThat(OdometerLogic.MAX_PAISE).isEqualTo(99_99_99_999_99L)
        assertThat(text(OdometerLogic.MAX_PAISE)).isEqualTo("₹99,99,99,999.99")
    }

    @Test
    fun amounts_beyond_the_maximum_clamp_rather_than_breaking_the_layout() {
        assertThat(text(Long.MAX_VALUE)).isEqualTo("₹99,99,99,999.99")
        assertThat(text(Long.MIN_VALUE)).isEqualTo("−₹99,99,99,999.99")
    }

    @Test
    fun agrees_with_money_formatter_for_every_grouping_length() {
        var paise = 1L
        while (paise <= OdometerLogic.MAX_PAISE) {
            assertThat(text(paise)).isEqualTo(Money.formatInr(paise))
            paise = paise * 10 + 7
        }
    }

    @Test
    fun sign_is_a_true_minus_marked_as_a_sign_slot() {
        val slots = OdometerLogic.slots(-1_250_00, AmountDecimals.Always, AmountSign.NegativeOnly)
        assertThat(slots.first()).isInstanceOf(OdometerSlot.Sign::class.java)
        assertThat(slots.first().char).isEqualTo('−')
        assertThat(slots.count { it is OdometerSlot.Sign }).isEqualTo(1)
    }

    @Test
    fun always_sign_marks_a_credit_with_plus_and_zero_has_none() {
        assertThat(text(500_00, sign = AmountSign.Always)).isEqualTo("+₹500.00")
        assertThat(text(-500_00, sign = AmountSign.Always)).isEqualTo("−₹500.00")
        assertThat(text(0, sign = AmountSign.Always)).isEqualTo("₹0.00")
    }

    @Test
    fun no_sign_hides_it_for_callers_that_show_direction_elsewhere() {
        assertThat(text(-500_00, sign = AmountSign.None)).isEqualTo("₹500.00")
    }

    @Test
    fun decimals_modes() {
        assertThat(text(1_250_00, AmountDecimals.Never)).isEqualTo("₹1,250")
        assertThat(text(1_250_50, AmountDecimals.WhenNonZero)).isEqualTo("₹1,250.50")
        assertThat(text(1_250_00, AmountDecimals.WhenNonZero)).isEqualTo("₹1,250")
    }

    @Test
    fun digit_keys_follow_place_value_so_columns_keep_identity_when_the_amount_grows() {
        val small = OdometerLogic.slots(9_00, AmountDecimals.Never, AmountSign.None).filterIsInstance<OdometerSlot.Digit>()
        val big = OdometerLogic.slots(1_009_00, AmountDecimals.Never, AmountSign.None).filterIsInstance<OdometerSlot.Digit>()
        assertThat(small.single().key).isEqualTo(big.last().key)
        assertThat(big.last().value).isEqualTo(9)
    }

    @Test
    fun all_keys_are_unique_at_the_maximum() {
        val keys = OdometerLogic.slots(-OdometerLogic.MAX_PAISE, AmountDecimals.Always, AmountSign.Always).map { it.key }
        assertThat(keys).containsNoDuplicates()
    }

    @Test
    fun digit_sequence_rolls_forward_through_every_digit_when_rising() {
        assertThat(OdometerLogic.digitSequence(3, 7, RollDirection.Up)).containsExactly(3, 4, 5, 6, 7).inOrder()
    }

    @Test
    fun digit_sequence_wraps_past_nine_when_rising() {
        assertThat(OdometerLogic.digitSequence(8, 2, RollDirection.Up)).containsExactly(8, 9, 0, 1, 2).inOrder()
    }

    @Test
    fun digit_sequence_rolls_backward_when_falling_and_wraps_past_zero() {
        assertThat(OdometerLogic.digitSequence(7, 3, RollDirection.Down)).containsExactly(7, 6, 5, 4, 3).inOrder()
        assertThat(OdometerLogic.digitSequence(1, 9, RollDirection.Down)).containsExactly(1, 0, 9).inOrder()
    }

    @Test
    fun an_unchanged_digit_does_not_roll() {
        assertThat(OdometerLogic.digitSequence(4, 4, RollDirection.Up)).containsExactly(4)
        assertThat(OdometerLogic.nextPosition(14f, 4, RollDirection.Up)).isEqualTo(14f)
    }

    @Test
    fun direction_follows_the_amount() {
        assertThat(OdometerLogic.direction(100, 200)).isEqualTo(RollDirection.Up)
        assertThat(OdometerLogic.direction(200, 100)).isEqualTo(RollDirection.Down)
        assertThat(OdometerLogic.direction(100, 100)).isEqualTo(RollDirection.Up)
    }

    @Test
    fun next_position_is_the_shortest_travel_in_the_given_direction() {
        assertThat(OdometerLogic.nextPosition(8f, 2, RollDirection.Up)).isEqualTo(12f)
        assertThat(OdometerLogic.nextPosition(2f, 8, RollDirection.Down)).isEqualTo(-2f)
        assertThat(OdometerLogic.nextPosition(-2f, 3, RollDirection.Up)).isEqualTo(3f)
    }

    @Test
    fun next_position_from_a_mid_roll_position_still_lands_on_the_target() {
        val p = OdometerLogic.nextPosition(5.4f, 2, RollDirection.Up)
        assertThat(p).isEqualTo(12f)
        assertThat(OdometerLogic.digitAt(p)).isEqualTo(2)
    }

    @Test
    fun position_decodes_to_the_two_visible_digits() {
        assertThat(OdometerLogic.digitAt(3f)).isEqualTo(3)
        assertThat(OdometerLogic.digitAt(13f)).isEqualTo(3)
        assertThat(OdometerLogic.digitAt(-2f)).isEqualTo(8)
        assertThat(OdometerLogic.digitAt(9.5f)).isEqualTo(9)
        assertThat(OdometerLogic.digitAt(9.5f + 1f)).isEqualTo(0)
    }

    @Test
    fun scale_is_one_when_the_text_fits_and_shrinks_in_stable_steps_otherwise() {
        assertThat(OdometerLogic.fitScale(100f, 200f, 0.4f)).isEqualTo(1f)
        val scale = OdometerLogic.fitScale(400f, 300f, 0.4f)
        assertThat(scale).isAtMost(0.75f)
        assertThat(400f * scale).isAtMost(300f)
        assertThat(((scale / OdometerLogic.SCALE_STEP) % 1f)).isWithin(0.001f).of(0f)
    }

    @Test
    fun the_chosen_scale_fits_when_cells_round_up_to_whole_pixels() {
        // Eleven digit cells and four separators, each rounded up to whole pixels at the scaled size, as the odometer lays
        // them out: width is not proportional to the scale.
        val widthAt = { s: Float -> 11 * ceil(30.4f * s) + 4 * ceil(9.3f * s) }
        for (available in 150..400) {
            val scale = OdometerLogic.fitScale(available.toFloat(), 0.4f, widthAt)
            if (scale > 0.4f) assertThat(widthAt(scale)).isAtMost(available.toFloat())
            assertThat(((scale / OdometerLogic.SCALE_STEP) % 1f).let { minOf(it, 1f - it) }).isWithin(0.001f).of(0f)
        }
        assertThat(OdometerLogic.fitScale(1_000f, 0.4f, widthAt)).isEqualTo(1f)
    }

    @Test
    fun scale_never_goes_below_the_floor_even_on_absurdly_narrow_slots() {
        assertThat(OdometerLogic.fitScale(10_000f, 10f, 0.4f)).isEqualTo(0.4f)
        assertThat(OdometerLogic.fitScale(100f, 0f, 0.4f)).isEqualTo(0.4f)
    }

    @Test
    fun spoken_text_never_contains_a_lookalike_character() {
        assertThat(OdometerLogic.spoken(-1_250_00, AmountDecimals.WhenNonZero)).isEqualTo("minus ₹1,250")
        assertThat(OdometerLogic.spoken(1_250_50, AmountDecimals.Always)).isEqualTo("₹1,250.50")
    }

    @Test
    fun hidden_amount_reveals_no_digits_and_no_length() {
        val small = OdometerLogic.hiddenSlots().joinToString("") { it.char.toString() }
        assertThat(small).doesNotContainMatch("[0-9]")
        assertThat(small).startsWith("₹")
    }
}
