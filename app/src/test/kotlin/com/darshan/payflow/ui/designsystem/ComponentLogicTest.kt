package com.darshan.payflow.ui.designsystem

import com.darshan.payflow.ui.designsystem.components.AvatarLogic
import com.darshan.payflow.ui.designsystem.components.BottomNavChange
import com.darshan.payflow.ui.designsystem.components.BottomNavLogic
import com.darshan.payflow.ui.designsystem.components.ClickDebouncer
import com.darshan.payflow.ui.designsystem.components.DisplayText
import com.darshan.payflow.ui.designsystem.components.SheetPhysics
import com.darshan.payflow.ui.designsystem.components.SheetTarget
import com.darshan.payflow.ui.designsystem.components.ShakeCurve
import com.darshan.payflow.ui.designsystem.components.ToastQueue
import com.darshan.payflow.ui.designsystem.effects.ToastTone
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClickDebouncerTest {
    @Test
    fun first_click_passes_and_rapid_repeats_are_dropped() {
        var now = 1_000L
        val d = ClickDebouncer(windowMs = 500) { now }
        assertThat(d.tryAcquire()).isTrue()
        now += 10
        assertThat(d.tryAcquire()).isFalse()
        now += 489
        assertThat(d.tryAcquire()).isFalse()
    }

    @Test
    fun a_click_after_the_window_passes_again() {
        var now = 0L
        val d = ClickDebouncer(windowMs = 500) { now }
        assertThat(d.tryAcquire()).isTrue()
        now += 500
        assertThat(d.tryAcquire()).isTrue()
    }

    @Test
    fun a_thousand_taps_inside_the_window_are_one_action() {
        var now = 5L
        val d = ClickDebouncer(windowMs = 500) { now }
        var actions = 0
        repeat(1_000) { if (d.tryAcquire()) actions++ }
        assertThat(actions).isEqualTo(1)
    }

    @Test
    fun a_clock_that_jumps_backwards_cannot_unlock_early_or_lock_forever() {
        var now = 10_000L
        val d = ClickDebouncer(windowMs = 500) { now }
        assertThat(d.tryAcquire()).isTrue()
        now = 100L
        assertThat(d.tryAcquire()).isTrue()
    }
}

class BottomNavLogicTest {
    @Test
    fun selecting_another_item_is_a_change() {
        val state = BottomNavLogic(count = 4, initial = 0)
        assertThat(state.select(2)).isEqualTo(BottomNavChange.Changed(from = 0, to = 2))
        assertThat(state.selected).isEqualTo(2)
    }

    @Test
    fun selecting_the_current_item_is_a_reselect_so_screens_can_scroll_to_top() {
        val state = BottomNavLogic(count = 4, initial = 1)
        assertThat(state.select(1)).isEqualTo(BottomNavChange.Reselected(1))
        assertThat(state.selected).isEqualTo(1)
    }

    @Test
    fun an_out_of_range_index_is_ignored() {
        val state = BottomNavLogic(count = 4, initial = 1)
        assertThat(state.select(4)).isEqualTo(BottomNavChange.Ignored)
        assertThat(state.select(-1)).isEqualTo(BottomNavChange.Ignored)
        assertThat(state.selected).isEqualTo(1)
    }

    @Test
    fun bad_construction_is_rejected() {
        val failure = runCatching { BottomNavLogic(count = 0, initial = 0) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun icon_morph_follows_the_pill_position() {
        assertThat(BottomNavLogic.itemProgress(pillPosition = 1f, index = 1)).isEqualTo(1f)
        assertThat(BottomNavLogic.itemProgress(pillPosition = 1f, index = 0)).isEqualTo(0f)
        assertThat(BottomNavLogic.itemProgress(pillPosition = 1.25f, index = 1)).isEqualTo(0.75f)
        assertThat(BottomNavLogic.itemProgress(pillPosition = 1.25f, index = 2)).isEqualTo(0.25f)
        assertThat(BottomNavLogic.itemProgress(pillPosition = 3f, index = 0)).isEqualTo(0f)
    }

    @Test
    fun overshooting_pill_never_produces_progress_outside_zero_one() {
        for (p in listOf(-0.3f, 3.3f)) for (i in 0..3) assertThat(BottomNavLogic.itemProgress(p, i)).isIn(com.google.common.collect.Range.closed(0f, 1f))
    }

    @Test
    fun pill_left_edge_is_centred_in_the_selected_cell() {
        assertThat(BottomNavLogic.pillLeft(position = 2f, itemWidth = 100f, pillWidth = 60f)).isEqualTo(220f)
        assertThat(BottomNavLogic.pillLeft(position = 0f, itemWidth = 100f, pillWidth = 100f)).isEqualTo(0f)
    }

    @Test
    fun a_thousand_rapid_selects_end_on_the_last_item() {
        val state = BottomNavLogic(count = 4, initial = 0)
        repeat(1_000) { state.select(it % 4) }
        assertThat(state.selected).isEqualTo(3)
    }
}

class SheetPhysicsTest {
    private val height = 1000f

    @Test
    fun a_small_slow_drag_springs_back() {
        assertThat(SheetPhysics.settle(100f, height, 0f, dismissible = true)).isEqualTo(SheetTarget.Expanded)
    }

    @Test
    fun past_the_threshold_it_dismisses() {
        assertThat(SheetPhysics.settle(450f, height, 0f, dismissible = true)).isEqualTo(SheetTarget.Dismissed)
    }

    @Test
    fun a_fast_downward_fling_dismisses_from_anywhere() {
        assertThat(SheetPhysics.settle(10f, height, SheetPhysics.FLING_VELOCITY + 1f, dismissible = true)).isEqualTo(SheetTarget.Dismissed)
    }

    @Test
    fun an_upward_fling_keeps_it_open_even_past_the_threshold() {
        assertThat(SheetPhysics.settle(600f, height, -SheetPhysics.FLING_VELOCITY - 1f, dismissible = true)).isEqualTo(SheetTarget.Expanded)
    }

    @Test
    fun a_locked_sheet_never_dismisses() {
        assertThat(SheetPhysics.settle(999f, height, 99_999f, dismissible = false)).isEqualTo(SheetTarget.Expanded)
    }

    @Test
    fun dragging_up_past_the_top_meets_resistance_and_down_is_free() {
        assertThat(SheetPhysics.applyDrag(0f, 100f)).isEqualTo(100f)
        val up = SheetPhysics.applyDrag(0f, -100f)
        assertThat(up).isLessThan(0f)
        assertThat(up).isGreaterThan(-100f * SheetPhysics.OVERDRAG_RESISTANCE - 0.001f)
    }

    @Test
    fun scrim_fades_with_the_drag() {
        assertThat(SheetPhysics.scrimAlpha(0f, height)).isEqualTo(1f)
        assertThat(SheetPhysics.scrimAlpha(height, height)).isEqualTo(0f)
        assertThat(SheetPhysics.scrimAlpha(-50f, height)).isEqualTo(1f)
        assertThat(SheetPhysics.scrimAlpha(500f, 0f)).isEqualTo(0f)
    }
}

class ToastQueueTest {
    @Test
    fun shows_one_at_a_time_in_order() {
        val q = ToastQueue(maxPending = 3)
        q.enqueue("a", ToastTone.Info)
        q.enqueue("b", ToastTone.Info)
        assertThat(q.current?.message).isEqualTo("a")
        q.dismissCurrent()
        assertThat(q.current?.message).isEqualTo("b")
        q.dismissCurrent()
        assertThat(q.current).isNull()
    }

    @Test
    fun a_repeat_of_the_visible_toast_does_not_stack() {
        val q = ToastQueue(maxPending = 3)
        repeat(50) { q.enqueue("Copied", ToastTone.Success) }
        q.dismissCurrent()
        assertThat(q.current).isNull()
    }

    @Test
    fun a_flood_keeps_only_the_newest_pending_ones() {
        val q = ToastQueue(maxPending = 3)
        repeat(1_000) { q.enqueue("m$it", ToastTone.Info) }
        val seen = mutableListOf<String>()
        while (q.current != null) { seen += q.current!!.message; q.dismissCurrent() }
        assertThat(seen).containsExactly("m0", "m997", "m998", "m999").inOrder()
    }

    @Test
    fun each_toast_gets_a_fresh_id_so_the_timer_restarts() {
        val q = ToastQueue(maxPending = 3)
        q.enqueue("a", ToastTone.Info)
        val first = q.current!!.id
        q.dismissCurrent()
        q.enqueue("a", ToastTone.Info)
        assertThat(q.current!!.id).isNotEqualTo(first)
    }

    @Test
    fun capping_never_cuts_an_emoji_in_half() {
        val q = ToastQueue(maxPending = 3)
        q.enqueue("x".repeat(ToastQueue.MAX_MESSAGE_LENGTH - 1) + "😀😀", ToastTone.Info)
        assertThat(q.current!!.message.last().isHighSurrogate()).isFalse()
    }

    @Test
    fun an_absurdly_long_message_is_capped() {
        val q = ToastQueue(maxPending = 3)
        q.enqueue("x".repeat(100_000), ToastTone.Failed)
        assertThat(q.current!!.message.length).isAtMost(ToastQueue.MAX_MESSAGE_LENGTH)
    }
}

class DisplayTextTest {
    @Test
    fun removes_direction_overrides_and_zero_width_characters() {
        assertThat(DisplayText.safe("Rahul\u202Eevil")).isEqualTo("Rahulevil")
        assertThat(DisplayText.safe("a\u2066b\u2069c")).isEqualTo("abc")
        assertThat(DisplayText.safe("pay\u200Bme\uFEFF")).isEqualTo("payme")
    }

    @Test
    fun leaves_real_text_in_any_language_alone() {
        for (name in listOf("Asha", "आशा कुलकर्णी", "عائشة", "😀 Café", "O'Brien & Sons")) {
            assertThat(DisplayText.safe(name)).isEqualTo(name)
        }
    }
}

class AvatarLogicTest {
    @Test
    fun initial_is_the_first_letter_uppercased() {
        assertThat(AvatarLogic.initial("asha")).isEqualTo("A")
        assertThat(AvatarLogic.initial("  rahul k")).isEqualTo("R")
        assertThat(AvatarLogic.initial("9 Bakers")).isEqualTo("9")
    }

    @Test
    fun initial_skips_symbols_and_keeps_surrogate_pairs_whole() {
        assertThat(AvatarLogic.initial("!!@@zed")).isEqualTo("Z")
        assertThat(AvatarLogic.initial("𝐀bc")).isEqualTo("𝐀")
    }

    @Test
    fun a_name_with_no_letters_gets_a_placeholder() {
        assertThat(AvatarLogic.initial("")).isEqualTo("?")
        assertThat(AvatarLogic.initial("   ")).isEqualTo("?")
        assertThat(AvatarLogic.initial("😀")).isEqualTo("?")
    }

    @Test
    fun tone_is_stable_for_a_name_ignoring_case_and_padding() {
        assertThat(AvatarLogic.toneIndex("Asha", 6)).isEqualTo(AvatarLogic.toneIndex("  asha ", 6))
    }

    @Test
    fun tone_is_always_in_range_even_for_hash_extremes() {
        for (name in listOf("polygenelubricants", "", "a", "\u0000", "x".repeat(10_000))) {
            assertThat(AvatarLogic.toneIndex(name, 6)).isIn(0..5)
        }
    }

    @Test
    fun badge_count_is_capped() {
        assertThat(AvatarLogic.badgeText(3)).isEqualTo("3")
        assertThat(AvatarLogic.badgeText(120)).isEqualTo("99+")
        assertThat(AvatarLogic.badgeText(0)).isNull()
    }
}

class ShakeCurveTest {
    @Test
    fun rests_at_zero_at_both_ends() {
        assertThat(ShakeCurve.offset(0f)).isWithin(0.001f).of(0f)
        assertThat(ShakeCurve.offset(1f)).isWithin(0.001f).of(0f)
    }

    @Test
    fun peaks_early_and_decays() {
        val early = (1..30).maxOf { kotlin.math.abs(ShakeCurve.offset(it / 100f)) }
        val late = (70..99).maxOf { kotlin.math.abs(ShakeCurve.offset(it / 100f)) }
        assertThat(early).isGreaterThan(late)
        assertThat(early).isAtMost(1f)
    }

    @Test
    fun it_goes_both_ways() {
        val values = (1..99).map { ShakeCurve.offset(it / 100f) }
        assertThat(values.any { it > 0.2f }).isTrue()
        assertThat(values.any { it < -0.2f }).isTrue()
    }
}
