package com.darshan.payflow.stress

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.ui.designsystem.FakeHaptics
import com.darshan.payflow.ui.designsystem.commitState
import com.darshan.payflow.ui.designsystem.components.AmountDecimals
import com.darshan.payflow.ui.designsystem.components.AmountOdometer
import com.darshan.payflow.ui.designsystem.components.AmountSign
import com.darshan.payflow.ui.designsystem.components.AmountText
import com.darshan.payflow.ui.designsystem.components.AvatarLogic
import com.darshan.payflow.ui.designsystem.components.BottomNavLogic
import com.darshan.payflow.ui.designsystem.components.BottomNavState
import com.darshan.payflow.ui.designsystem.components.ClickDebouncer
import com.darshan.payflow.ui.designsystem.components.NavGlyphs
import com.darshan.payflow.ui.designsystem.components.NavItem
import com.darshan.payflow.ui.designsystem.components.OdometerLogic
import com.darshan.payflow.ui.designsystem.components.PayFlowAvatar
import com.darshan.payflow.ui.designsystem.components.PayFlowBottomNav
import com.darshan.payflow.ui.designsystem.components.PayFlowListRow
import com.darshan.payflow.ui.designsystem.components.PrimaryButton
import com.darshan.payflow.ui.designsystem.components.RollDirection
import com.darshan.payflow.ui.designsystem.components.StatusKind
import com.darshan.payflow.ui.designsystem.components.StatusPill
import com.darshan.payflow.ui.designsystem.components.ToastQueue
import com.darshan.payflow.ui.designsystem.components.rememberBottomNavState
import com.darshan.payflow.ui.designsystem.effects.HapticEvent
import com.darshan.payflow.ui.designsystem.effects.ToastTone
import com.darshan.payflow.ui.designsystem.effects.UiEffect
import com.darshan.payflow.ui.designsystem.effects.UiEffectChannel
import com.darshan.payflow.ui.designsystem.recompositionCycles
import com.darshan.payflow.ui.designsystem.setDs
import com.google.common.truth.Truth.assertThat
import kotlin.system.measureTimeMillis
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Load simulations for the design system. Only run by `stressTest`. Numbers are JVM timings: comparisons, not phone frame times. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class DesignSystemStressTest {
    @get:Rule val rule = createComposeRule()

    private fun report(line: String) = println("STRESS [design system] $line")

    @Test
    fun a_thousand_clicks_inside_the_window_are_one_action() {
        var actions = 0
        val haptics = FakeHaptics()
        rule.setDs(haptics = haptics) { PrimaryButton("Pay", { actions++ }, Modifier.testTag("pay")) }
        // All 1,000 in one main-thread tick, so the clock can't move past the window: the worst burst a button can see.
        val click = rule.onNodeWithTag("pay").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val ms = measureTimeMillis { rule.runOnUiThread { repeat(TAPS) { click() } } }
        assertThat(actions).isEqualTo(1)
        assertThat(haptics.events).containsExactly(HapticEvent.Light)
        report("$TAPS clicks inside the window → $actions action, ${haptics.events.size} haptic, $ms ms")
    }

    @Test
    fun a_thousand_real_taps_are_one_action_when_the_screen_goes_to_loading() {
        var actions = 0
        var loading by mutableStateOf(false)
        rule.setDs {
            PrimaryButton(
                "Pay",
                {
                    actions++
                    loading = true
                },
                Modifier.testTag("pay"),
                loading = loading,
            )
        }
        // Injected taps do move the clock (about 30ms each), past the debounce window: the loading state is what holds here.
        val ms = measureTimeMillis { repeat(TAPS) { rule.onNodeWithTag("pay").performClick() } }
        assertThat(actions).isEqualTo(1)
        report("$TAPS spaced taps with loading on accept → $actions action, $ms ms")
    }

    @Test
    fun taps_spread_over_time_stay_limited_to_one_action_per_window() {
        var actions = 0
        rule.setDs { PrimaryButton("Pay", { actions++ }, Modifier.testTag("pay")) }
        val start = SystemClock.uptimeMillis()
        repeat(TAPS) { rule.onNodeWithTag("pay").performClick() }
        val elapsed = SystemClock.uptimeMillis() - start
        val limit = elapsed / ClickDebouncer.DEFAULT_WINDOW_MS + 1
        assertThat(actions.toLong()).isAtMost(limit)
        report("$TAPS spaced taps over $elapsed simulated ms, no loading state → $actions actions (limit $limit)")
    }

    @Test
    fun ten_thousand_rows_compose_only_what_is_visible() {
        var composedRows = 0
        rule.setDs {
            LazyColumn(Modifier.testTag("list")) {
                items(ROWS, key = { it }, contentType = { "row" }) { index ->
                    SideEffect { composedRows++ }
                    PayFlowListRow(
                        "Payee number $index",
                        subtitle = "payee$index@okbank",
                        leading = { PayFlowAvatar("Payee number $index") },
                        trailing = { AmountText(index * 137L, decimals = AmountDecimals.Always, sign = AmountSign.Always) },
                        onClick = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        val initial = composedRows
        val jumpMs = measureTimeMillis {
            for (target in listOf(100, 2_500, 5_000, 7_500, ROWS - 1, 0)) {
                rule.onNode(hasScrollAction()).performScrollToIndex(target)
            }
        }
        rule.onNodeWithText("Payee number 0").assertExists()
        assertThat(initial).isAtMost(MAX_VISIBLE_ROWS)
        assertThat(composedRows).isAtMost(MAX_VISIBLE_ROWS * 7)
        report("$ROWS rows: $initial composed first, $composedRows after 6 long jumps, $jumpMs ms for the jumps")
    }

    @Test
    fun ten_thousand_avatar_names_resolve_cheaply() {
        val names = List(ROWS) { "Name $it ${"é".repeat(it % 5)}" }
        val ms = measureTimeMillis {
            for (name in names) {
                AvatarLogic.initial(name)
                AvatarLogic.toneIndex(name, 6)
            }
        }
        assertThat(ms).isLessThan(1_000)
        report("$ROWS avatar initials and tones: $ms ms")
    }

    @Test
    fun the_maximum_amount_survives_a_thousand_updates_and_lands_on_the_last() {
        var amount by mutableLongStateOf(0L)
        rule.mainClock.autoAdvance = false
        rule.setDs { AmountOdometer(amount, Modifier.testTag("amount"), decimals = AmountDecimals.Always) }
        rule.mainClock.advanceTimeBy(100)
        val ms = measureTimeMillis {
            repeat(UPDATES) { i ->
                commitState { amount = if (i % 2 == 0) OdometerLogic.MAX_PAISE - i else i * 1_000_003L }
                rule.mainClock.advanceTimeByFrame()
            }
            commitState { amount = OdometerLogic.MAX_PAISE }
            rule.mainClock.advanceTimeBy(3_000)
        }
        rule.onNodeWithText("₹99,99,99,999.99").assertExists()
        report("$UPDATES interrupting updates at the maximum amount: $ms ms, ends on ₹99,99,99,999.99")
    }

    @Test
    fun reduced_motion_takes_the_same_load() {
        var amount by mutableLongStateOf(0L)
        rule.mainClock.autoAdvance = false
        rule.setDs(reduceMotion = true) { AmountOdometer(amount, decimals = AmountDecimals.Always) }
        rule.mainClock.advanceTimeBy(100)
        val ms = measureTimeMillis {
            repeat(UPDATES) { i ->
                commitState { amount = i * 99_991L }
                rule.mainClock.advanceTimeByFrame()
            }
        }
        rule.onNodeWithText(OdometerLogic.text((UPDATES - 1) * 99_991L, AmountDecimals.Always, AmountSign.NegativeOnly)).assertExists()
        report("$UPDATES updates with reduced motion: $ms ms")
    }

    @Test
    fun a_busy_screen_of_animations_does_not_recompose_per_frame() {
        var amount by mutableLongStateOf(1_111_11L)
        lateinit var nav: BottomNavState
        val items = listOf(
            NavItem("a", "Home", NavGlyphs.Home),
            NavItem("b", "Scan", NavGlyphs.Scan),
            NavItem("c", "History", NavGlyphs.History),
            NavItem("d", "You", NavGlyphs.Profile),
        )
        rule.mainClock.autoAdvance = false
        rule.setDs {
            Column {
                AmountOdometer(amount, decimals = AmountDecimals.Always)
                StatusKind.entries.forEach { StatusPill(it, it.name) }
                nav = rememberBottomNavState(items.size)
                PayFlowBottomNav(items, nav, onChange = {})
                Text("Static content")
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        commitState {
            amount = 9_999_99L
            nav.select(3)
        }
        rule.mainClock.advanceTimeBy(48)
        val before = recompositionCycles()
        repeat(FRAMES) { rule.mainClock.advanceTimeByFrame() }
        val cycles = recompositionCycles() - before
        assertThat(cycles).isAtMost(3L)
        report("$FRAMES frames of odometer + 5 pills + nav pill: $cycles recomposition cycles")
    }

    @Test
    fun pure_rules_hold_at_volume() {
        val debouncer = ClickDebouncer(windowMs = 500) { 5L }
        var passed = 0
        val nav = BottomNavLogic(count = 4, initial = 0)
        var sum = 0L
        val ms = measureTimeMillis {
            repeat(1_000_000) { i ->
                if (debouncer.tryAcquire()) passed++
                nav.select(i % 4)
                sum += OdometerLogic.nextPosition(i.toFloat(), i % 10, if (i % 2 == 0) RollDirection.Up else RollDirection.Down).toLong()
            }
        }
        assertThat(passed).isEqualTo(1)
        assertThat(nav.selected).isEqualTo(3)
        report("1,000,000 debounce + nav + roll position calls: $ms ms")
        assertThat(sum).isNotEqualTo(Long.MIN_VALUE)
    }

    @Test
    fun floods_of_toasts_and_effects_stay_bounded() {
        val queue = ToastQueue()
        val toastMs = measureTimeMillis { repeat(100_000) { queue.enqueue("message $it", ToastTone.Info) } }
        var shown = 0
        while (queue.current != null) {
            shown++
            queue.dismissCurrent()
        }
        assertThat(shown).isEqualTo(4)
        val channel = UiEffectChannel(capacity = 16)
        val effectMs = measureTimeMillis { repeat(100_000) { channel.send(UiEffect.Toast("m$it")) } }
        report("100,000 toasts: $toastMs ms, $shown kept; 100,000 effects into a 16-slot channel: $effectMs ms")
    }

    private companion object {
        const val TAPS = 1_000
        const val ROWS = 10_000
        const val UPDATES = 1_000
        const val FRAMES = 120
        const val MAX_VISIBLE_ROWS = 20
    }
}
