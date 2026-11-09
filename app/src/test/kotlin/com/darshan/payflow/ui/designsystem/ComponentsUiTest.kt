package com.darshan.payflow.ui.designsystem

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.ui.designsystem.components.AvatarBadge
import com.darshan.payflow.ui.designsystem.components.BottomNavChange
import com.darshan.payflow.ui.designsystem.components.BottomNavState
import com.darshan.payflow.ui.designsystem.components.BrandLoader
import com.darshan.payflow.ui.designsystem.components.EmptyState
import com.darshan.payflow.ui.designsystem.components.ErrorState
import com.darshan.payflow.ui.designsystem.components.NavGlyphs
import com.darshan.payflow.ui.designsystem.components.NavItem
import com.darshan.payflow.ui.designsystem.components.PayFlowAvatar
import com.darshan.payflow.ui.designsystem.components.PayFlowBottomNav
import com.darshan.payflow.ui.designsystem.components.PayFlowBottomSheet
import com.darshan.payflow.ui.designsystem.components.PayFlowChoiceChip
import com.darshan.payflow.ui.designsystem.components.PayFlowListRow
import com.darshan.payflow.ui.designsystem.components.PayFlowSwitch
import com.darshan.payflow.ui.designsystem.components.PayFlowTextField
import com.darshan.payflow.ui.designsystem.components.PayFlowToastHost
import com.darshan.payflow.ui.designsystem.components.PrimaryButton
import com.darshan.payflow.ui.designsystem.components.SectionHeader
import com.darshan.payflow.ui.designsystem.components.SkeletonList
import com.darshan.payflow.ui.designsystem.components.StatusKind
import com.darshan.payflow.ui.designsystem.components.StatusPill
import com.darshan.payflow.ui.designsystem.components.ToastHostState
import com.darshan.payflow.ui.designsystem.components.rememberBottomNavState
import com.darshan.payflow.ui.designsystem.components.rememberToastHostState
import com.darshan.payflow.ui.designsystem.effects.CollectUiEffects
import com.darshan.payflow.ui.designsystem.effects.HapticEvent
import com.darshan.payflow.ui.designsystem.effects.ToastTone
import com.darshan.payflow.ui.designsystem.effects.UiEffect
import com.darshan.payflow.ui.designsystem.effects.UiEffectChannel
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class BottomNavUiTest {
    @get:Rule val rule = createComposeRule()

    private val items = listOf(
        NavItem("home", "Home", NavGlyphs.Home, "nav:home"),
        NavItem("scan", "Scan", NavGlyphs.Scan, "nav:scan"),
        NavItem("history", "History", NavGlyphs.History, "nav:history"),
        NavItem("me", "You", NavGlyphs.Profile, "nav:me"),
    )

    @Test
    fun selecting_reports_the_change_marks_the_tab_and_ticks_once() {
        val changes = mutableListOf<BottomNavChange>()
        val haptics = FakeHaptics()
        rule.setDs(haptics = haptics) {
            PayFlowBottomNav(items, rememberBottomNavState(items.size), onChange = { changes += it })
        }
        rule.onNodeWithTag("nav:home").assertIsSelected()
        rule.onNodeWithTag("nav:history").performClick()
        rule.onNodeWithTag("nav:history").assertIsSelected()
        rule.onNodeWithTag("nav:home").assertIsNotSelected()
        assertThat(changes).containsExactly(BottomNavChange.Changed(0, 2))
        assertThat(haptics.events).containsExactly(HapticEvent.Tick)
    }

    @Test
    fun reselecting_reports_it_with_no_haptic() {
        val changes = mutableListOf<BottomNavChange>()
        val haptics = FakeHaptics()
        rule.setDs(haptics = haptics) { PayFlowBottomNav(items, rememberBottomNavState(items.size), onChange = { changes += it }) }
        rule.onNodeWithTag("nav:home").performClick()
        assertThat(changes).containsExactly(BottomNavChange.Reselected(0))
        assertThat(haptics.events).isEmpty()
    }

    @Test
    fun the_sliding_pill_does_not_recompose_per_frame() {
        lateinit var state: BottomNavState
        rule.mainClock.autoAdvance = false
        rule.setDs {
            state = rememberBottomNavState(items.size)
            PayFlowBottomNav(items, state, onChange = {})
        }
        rule.mainClock.advanceTimeBy(500)
        commitState { state.select(3) }
        rule.mainClock.advanceTimeBy(48)
        val moving = rule.onRoot().captureToImage().asAndroidBitmap()
        val before = recompositionCycles()
        repeat(30) { rule.mainClock.advanceTimeByFrame() }
        assertThat(recompositionCycles() - before).isAtMost(2L)
        assertThat(rule.onRoot().captureToImage().asAndroidBitmap().sameAs(moving)).isFalse()
    }

    @Test
    fun labels_stay_inside_the_bar_at_large_font_scale() {
        rule.setDs(fontScale = 1.3f) { PayFlowBottomNav(items, rememberBottomNavState(items.size), onChange = {}, modifier = Modifier.testTag("bar")) }
        rule.onNodeWithText("History", useUnmergedTree = true).assertIsDisplayed().assertInside(rule.onNodeWithTag("nav:history"))
    }

    @Test
    fun every_item_reaches_the_touch_target() {
        rule.setDs { PayFlowBottomNav(items, rememberBottomNavState(items.size), onChange = {}) }
        for (item in items) rule.onNodeWithTag(item.testTag!!).assertHeightIsAtLeast(48.dp)
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class BottomSheetUiTest {
    @get:Rule val rule = createComposeRule()

    private fun sheet(locked: Boolean = false, refuses: Boolean = false, reduce: Boolean = false, onDismiss: () -> Unit = {}) {
        var visible by mutableStateOf(true)
        rule.setDs(reduceMotion = reduce) {
            Box(Modifier.fillMaxSize()) {
                PayFlowBottomSheet(
                    visible = visible,
                    onDismissRequest = {
                        onDismiss()
                        if (!refuses) visible = false
                    },
                    dismissible = !locked,
                    modifier = Modifier.testTag("host"),
                ) {
                    Box(Modifier.fillMaxWidth().testTag("sheet")) { Text("Sheet content") }
                }
            }
        }
    }

    @Test
    fun opens_and_shows_its_content() {
        sheet()
        rule.onNodeWithText("Sheet content").assertIsDisplayed()
    }

    @Test
    fun a_scrim_tap_dismisses_and_it_leaves_composition() {
        var dismissed = 0
        sheet(onDismiss = { dismissed++ })
        rule.onNodeWithTag("host").performTouchInput { click(topCenter + Offset(0f, 50f)) }
        rule.waitForIdle()
        assertThat(dismissed).isEqualTo(1)
        rule.onNodeWithText("Sheet content").assertDoesNotExist()
    }

    @Test
    fun a_tap_on_the_sheet_itself_does_not_dismiss() {
        var dismissed = 0
        sheet(onDismiss = { dismissed++ })
        rule.onNodeWithText("Sheet content").performClick()
        assertThat(dismissed).isEqualTo(0)
    }

    @Test
    fun a_swipe_down_dismisses() {
        var dismissed = 0
        sheet(onDismiss = { dismissed++ })
        rule.onNodeWithTag("sheet").performTouchInput { swipeDown(startY = top, endY = top + 2_000f, durationMillis = 100) }
        rule.waitForIdle()
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun a_locked_sheet_ignores_scrim_and_swipe_and_offers_no_dismiss_action() {
        var dismissed = 0
        sheet(locked = true, onDismiss = { dismissed++ })
        rule.onNodeWithTag("host").performTouchInput { click(topCenter + Offset(0f, 50f)) }
        rule.onNodeWithTag("sheet").performTouchInput { swipeDown(startY = top, endY = top + 2_000f, durationMillis = 100) }
        rule.waitForIdle()
        assertThat(dismissed).isEqualTo(0)
        rule.onNodeWithText("Sheet content").assertIsDisplayed()
    }

    @Test
    fun if_the_owner_refuses_it_springs_back_open() {
        var dismissed = 0
        sheet(refuses = true, onDismiss = { dismissed++ })
        rule.onNodeWithTag("sheet").performTouchInput { swipeDown(startY = top, endY = top + 2_000f, durationMillis = 100) }
        rule.waitForIdle()
        assertThat(dismissed).isEqualTo(1)
        rule.onNodeWithText("Sheet content").assertIsDisplayed()
    }

    @Test
    fun reduced_motion_still_opens_and_dismisses() {
        var dismissed = 0
        sheet(reduce = true, onDismiss = { dismissed++ })
        rule.onNodeWithText("Sheet content").assertIsDisplayed()
        rule.onNodeWithTag("host").performTouchInput { click(topCenter + Offset(0f, 50f)) }
        rule.waitForIdle()
        assertThat(dismissed).isEqualTo(1)
        rule.onNodeWithText("Sheet content").assertDoesNotExist()
    }

    @Test
    fun it_opens_no_new_window_so_flag_secure_still_covers_it() {
        sheet()
        rule.onAllNodes(isDialog()).assertCountEquals(0)
        rule.onAllNodes(isPopup()).assertCountEquals(0)
    }

    @Test
    fun touches_cannot_reach_a_button_behind_a_sheet_even_a_locked_one() {
        var behind = 0
        rule.setDs {
            Box(Modifier.fillMaxSize()) {
                PrimaryButton("Pay", { behind++ }, Modifier.fillMaxWidth())
                PayFlowBottomSheet(visible = true, onDismissRequest = {}, dismissible = false, modifier = Modifier.testTag("host")) {
                    Text("Confirming")
                }
            }
        }
        rule.onNodeWithTag("host").performTouchInput { click(topCenter + Offset(0f, 20f)) }
        rule.waitForIdle()
        assertThat(behind).isEqualTo(0)
    }

    @Test
    fun a_swipe_down_from_scrollable_content_at_its_top_dismisses() {
        var dismissed = 0
        var visible by mutableStateOf(true)
        rule.setDs {
            Box(Modifier.fillMaxSize()) {
                PayFlowBottomSheet(visible, { dismissed++; visible = false }) {
                    LazyColumn(Modifier.fillMaxWidth().height(300.dp).testTag("list")) {
                        items(50) { Text("Row $it", Modifier.height(48.dp)) }
                    }
                }
            }
        }
        rule.onNodeWithTag("list").performTouchInput { swipeDown(startY = top + 10f, endY = bottom + 1_500f, durationMillis = 150) }
        rule.waitForIdle()
        assertThat(dismissed).isEqualTo(1)
    }

    private fun backCase(locked: Boolean): Int {
        var dismissed = 0
        lateinit var back: OnBackPressedDispatcher
        rule.setDs {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            PayFlowBottomSheet(visible = true, onDismissRequest = { dismissed++ }, dismissible = !locked) { Text("Sheet content") }
        }
        rule.runOnIdle { back.onBackPressed() }
        rule.waitForIdle()
        return dismissed
    }

    @Test
    fun back_dismisses_an_ordinary_sheet() {
        assertThat(backCase(locked = false)).isEqualTo(1)
    }

    @Test
    fun back_cannot_leave_a_locked_sheet_and_is_swallowed() {
        assertThat(backCase(locked = true)).isEqualTo(0)
        rule.onNodeWithText("Sheet content").assertIsDisplayed()
    }

    @Test
    fun entrance_starts_below_the_screen_and_finishes_at_rest() {
        rule.mainClock.autoAdvance = false
        sheet()
        rule.mainClock.advanceTimeBy(32)
        val startTop = rule.onNodeWithText("Sheet content").fetchSemanticsNode().boundsInRoot.top
        rule.mainClock.advanceTimeBy(1_500)
        val endTop = rule.onNodeWithText("Sheet content").fetchSemanticsNode().boundsInRoot.top
        assertThat(endTop).isLessThan(startTop)
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ToastUiTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var host: ToastHostState

    private fun setHost() {
        rule.mainClock.autoAdvance = false
        rule.setDs {
            host = rememberToastHostState()
            PayFlowToastHost(host, Modifier.align(Alignment.BottomCenter))
        }
    }

    @Test
    fun shows_then_leaves_on_its_own() {
        setHost()
        commitState { host.show("Copied", ToastTone.Success) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Copied").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(4_000)
        rule.onNodeWithText("Copied").assertDoesNotExist()
    }

    @Test
    fun a_failure_stays_longer_than_a_success() {
        setHost()
        commitState { host.show("Payment failed", ToastTone.Failed) }
        rule.mainClock.advanceTimeBy(4_000)
        rule.onNodeWithText("Payment failed").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(2_000)
        rule.onNodeWithText("Payment failed").assertDoesNotExist()
    }

    @Test
    fun a_tap_dismisses_and_the_next_one_shows() {
        setHost()
        commitState {
            host.show("First")
            host.show("Second")
        }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("First").performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Second").assertIsDisplayed()
        rule.onNodeWithText("First").assertDoesNotExist()
    }

    @Test
    fun it_announces_itself_politely() {
        setHost()
        commitState { host.show("Copied") }
        rule.mainClock.advanceTimeBy(500)
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertExists()
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class TextFieldUiTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun typing_reports_the_new_value() {
        var value by mutableStateOf("")
        rule.setDs { PayFlowTextField(value, { value = it }, "UPI ID", fieldTag = "field") }
        rule.onNodeWithTag("field").performTextInput("asha@okicici")
        assertThat(value).isEqualTo("asha@okicici")
    }

    @Test
    fun a_new_error_plays_one_reject_and_appears_in_semantics() {
        var error by mutableStateOf<String?>(null)
        val haptics = FakeHaptics()
        rule.setDs(haptics = haptics) { PayFlowTextField("x", {}, "UPI ID", error = error, fieldTag = "field") }
        rule.runOnIdle { error = "Invalid" }
        rule.waitForIdle()
        assertThat(haptics.events).containsExactly(HapticEvent.Reject)
        rule.onNodeWithTag("field").assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "Invalid"))
        rule.onNodeWithText("Invalid").assertIsDisplayed()
    }

    @Test
    fun an_error_already_there_on_open_makes_no_fuss() {
        val haptics = FakeHaptics()
        rule.setDs(haptics = haptics) { PayFlowTextField("x", {}, "UPI ID", error = "Invalid") }
        rule.waitForIdle()
        assertThat(haptics.events).isEmpty()
    }

    @Test
    fun the_same_error_again_does_not_buzz_twice() {
        var error by mutableStateOf<String?>("One")
        val haptics = FakeHaptics()
        rule.setDs(haptics = haptics) { PayFlowTextField("x", {}, "UPI ID", error = error) }
        rule.runOnIdle { error = "One" }
        rule.waitForIdle()
        assertThat(haptics.events).isEmpty()
        rule.runOnIdle { error = "Two" }
        rule.waitForIdle()
        assertThat(haptics.events).containsExactly(HapticEvent.Reject)
    }

    @Test
    fun under_reduced_motion_the_error_still_shows_and_buzzes() {
        var error by mutableStateOf<String?>(null)
        val haptics = FakeHaptics()
        rule.setDs(reduceMotion = true, haptics = haptics) { PayFlowTextField("x", {}, "UPI ID", error = error) }
        rule.runOnIdle { error = "Invalid" }
        rule.waitForIdle()
        rule.onNodeWithText("Invalid").assertIsDisplayed()
        assertThat(haptics.events).containsExactly(HapticEvent.Reject)
    }

    @Test
    fun label_and_error_are_not_clipped_at_large_font_scale() {
        rule.setDs(fontScale = 1.3f) {
            Box(Modifier.fillMaxWidth().testTag("box")) {
                PayFlowTextField("asha@okicici", {}, "UPI ID", error = "Enter a UPI ID like name@bank")
            }
        }
        rule.onNodeWithText("UPI ID").assertIsDisplayed().assertInside(rule.onNodeWithTag("box"))
        rule.onNodeWithText("Enter a UPI ID like name@bank").assertIsDisplayed().assertInside(rule.onNodeWithTag("box"))
    }

    @Test
    fun disabled_refuses_input() {
        rule.setDs { PayFlowTextField("locked", {}, "UPI ID", enabled = false) }
        rule.onNodeWithContentDescription("UPI ID")
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class SmallComponentsUiTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun status_pill_shows_each_label_animated_or_not() {
        rule.mainClock.autoAdvance = false
        rule.setDs {
            Column {
                StatusKind.entries.forEach { StatusPill(it, it.name, Modifier.testTag(it.name)) }
                StatusKind.entries.forEach { StatusPill(it, "${it.name} static", animate = false) }
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        StatusKind.entries.forEach {
            rule.onNodeWithText(it.name).assertIsDisplayed()
            rule.onNodeWithText("${it.name} static").assertIsDisplayed()
        }
    }

    @Test
    fun status_pill_label_is_inside_the_pill_at_large_font_scale() {
        rule.mainClock.autoAdvance = false
        rule.setDs(fontScale = 1.3f) { StatusPill(StatusKind.Refund, "Refund pending", Modifier.testTag("pill")) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText("Refund pending").assertInside(rule.onNodeWithTag("pill"))
    }

    @Test
    fun brand_loader_is_announced_as_indeterminate_progress() {
        rule.mainClock.autoAdvance = false
        rule.setDs { BrandLoader() }
        rule.mainClock.advanceTimeBy(16)
        rule.onNodeWithContentDescription("Loading")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
    }

    @Test
    fun skeleton_is_one_loading_announcement_not_many_blocks() {
        rule.mainClock.autoAdvance = false
        rule.setDs { SkeletonList(count = 3) }
        rule.mainClock.advanceTimeBy(16)
        rule.onNodeWithContentDescription("Loading").assertExists()
    }

    @Test
    fun avatar_is_silent_by_default_and_speaks_when_named() {
        rule.setDs {
            Column {
                PayFlowAvatar("Asha", Modifier.testTag("silent"))
                PayFlowAvatar("Rahul", Modifier.testTag("named"), contentDescription = "Rahul, verified", badge = AvatarBadge.Verified)
            }
        }
        rule.onNodeWithTag("silent").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        rule.onNodeWithContentDescription("Rahul, verified").assertExists()
    }

    @Test
    fun switch_toggles_with_a_tick_and_reports_state() {
        var on by mutableStateOf(false)
        val haptics = FakeHaptics()
        rule.setDs(haptics = haptics) { PayFlowSwitch(on, { on = it }, "Haptics", Modifier.testTag("s")) }
        rule.onNodeWithTag("s").assertIsOff().performClick()
        rule.onNodeWithTag("s").assertIsOn()
        assertThat(haptics.events).containsExactly(HapticEvent.Tick)
    }

    @Test
    fun choice_chip_selects_and_keeps_a_48dp_target() {
        var selected by mutableStateOf(false)
        rule.setDs { PayFlowChoiceChip("₹500", selected, { selected = true }, Modifier.testTag("c")) }
        rule.onNodeWithTag("c").assertHeightIsAtLeast(48.dp).assertIsNotSelected().performClick()
        rule.onNodeWithTag("c").assertIsSelected()
    }

    @Test
    fun a_list_row_never_shows_direction_override_characters() {
        rule.setDs { PayFlowListRow("Rahul\u202Elive", subtitle = "a\u2066b@bank") }
        rule.onNodeWithText("Rahullive").assertExists()
        rule.onNodeWithText("ab@bank").assertExists()
    }

    @Test
    fun section_header_is_a_heading_and_its_action_works() {
        var clicks = 0
        rule.setDs { SectionHeader("Recent", actionLabel = "View all", onAction = { clicks++ }) }
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
        rule.onNodeWithText("View all").performClick()
        assertThat(clicks).isEqualTo(1)
    }

    @Test
    fun list_row_clicks_with_a_haptic_and_long_names_stay_inside() {
        var clicks = 0
        val haptics = FakeHaptics()
        rule.setDs(fontScale = 1.3f, haptics = haptics) {
            PayFlowListRow(
                "A very long payee name that cannot possibly fit on the line".repeat(3),
                Modifier.testTag("row"),
                subtitle = "\u202Eright-to-left override name",
                onClick = { clicks++ },
            )
        }
        rule.onNodeWithTag("row").performClick()
        assertThat(clicks).isEqualTo(1)
        assertThat(haptics.events).containsExactly(HapticEvent.Light)
        rule.onNodeWithTag("row").assertHeightIsAtLeast(72.dp)
    }

    @Test
    fun empty_state_action_and_error_retry_work() {
        var action = 0
        var retry = 0
        rule.setDs {
            Column {
                EmptyState("Nothing here", message = "Yet", actionLabel = "Pay", onAction = { action++ })
                ErrorState("Failed", "Retry", { retry++ })
            }
        }
        rule.onNodeWithText("Pay").performClick()
        rule.onNodeWithText("Retry").performClick()
        assertThat(action).isEqualTo(1)
        assertThat(retry).isEqualTo(1)
    }

    @Test
    fun effect_channel_reaches_the_theme_haptics_and_toast_callback() {
        val haptics = FakeHaptics()
        val channel = UiEffectChannel()
        val toasts = mutableListOf<String>()
        rule.setDs(haptics = haptics) { CollectUiEffects(channel.effects, onToast = { m, _ -> toasts += m }) }
        rule.runOnIdle {
            channel.send(UiEffect.Haptic(HapticEvent.Confirm))
            channel.send(UiEffect.Toast("Saved"))
        }
        rule.waitForIdle()
        assertThat(haptics.events).containsExactly(HapticEvent.Confirm)
        assertThat(toasts).containsExactly("Saved")
    }

    @Test
    fun scrim_dismiss_is_available_to_accessibility_services() {
        var dismissed = 0
        rule.setDs {
            PayFlowBottomSheet(visible = true, onDismissRequest = { dismissed++ }) { Text("Body") }
        }
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick) and SemanticsMatcher("dismiss") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Dismiss" })
            .performSemanticsAction(SemanticsActions.OnClick)
        assertThat(dismissed).isEqualTo(1)
    }
}
