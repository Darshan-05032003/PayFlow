package com.darshan.payflow.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.ui.designsystem.components.AmountDecimals
import com.darshan.payflow.ui.designsystem.components.AmountOdometer
import com.darshan.payflow.ui.designsystem.components.BottomNavState
import com.darshan.payflow.ui.designsystem.components.NavGlyphs
import com.darshan.payflow.ui.designsystem.components.NavItem
import com.darshan.payflow.ui.designsystem.components.PayFlowBottomNav
import com.darshan.payflow.ui.designsystem.components.PayFlowBottomSheet
import com.darshan.payflow.ui.designsystem.components.PrimaryButton
import com.darshan.payflow.ui.designsystem.components.StatusKind
import com.darshan.payflow.ui.designsystem.components.StatusPill
import com.darshan.payflow.ui.designsystem.components.TactileButton
import com.darshan.payflow.ui.designsystem.components.rememberBottomNavState
import com.darshan.payflow.ui.designsystem.tokens.AurumDark
import com.darshan.payflow.ui.designsystem.tokens.AurumLight
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.theme.PayFlowTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Frozen frames from the middle of animations: what no static baseline can show. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class MotionScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun frame(dark: Boolean, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setDs(dark = dark) {
            Box(Modifier.fillMaxWidth().background(if (dark) AurumDark.bg else AurumLight.bg).testTag(ROOT).padding(Spacing.screen)) {
                content()
            }
        }
    }

    private fun name(base: String, dark: Boolean) = "ds_motion_${base}_${if (dark) "dark" else "light"}"

    private fun odometerMidRoll(dark: Boolean) {
        var amount by mutableLongStateOf(1_111_11L)
        frame(dark) { AmountOdometer(amount, decimals = AmountDecimals.Always) }
        rule.mainClock.advanceTimeBy(1_000)
        commitState { amount = 8_245_78L }
        rule.mainClock.advanceTimeBy(240)
        rule.onNodeWithTag(ROOT).captureNode(name("odometer_mid_roll", dark))
    }

    @Test fun odometer_mid_roll_light() = odometerMidRoll(false)
    @Test fun odometer_mid_roll_dark() = odometerMidRoll(true)

    private fun pillsEntering(dark: Boolean) {
        frame(dark) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                StatusKind.entries.forEach { StatusPill(it, it.name) }
            }
        }
        rule.mainClock.advanceTimeBy(96)
        rule.onNodeWithTag(ROOT).captureNode(name("pills_entering", dark))
    }

    @Test fun pills_entering_light() = pillsEntering(false)
    @Test fun pills_entering_dark() = pillsEntering(true)

    private fun navSliding(dark: Boolean) {
        lateinit var state: BottomNavState
        frame(dark) {
            state = rememberBottomNavState(NAV.size)
            PayFlowBottomNav(NAV, state, onChange = {})
        }
        rule.mainClock.advanceTimeBy(500)
        commitState { state.select(2) }
        rule.mainClock.advanceTimeBy(112)
        rule.onNodeWithTag(ROOT).captureNode(name("nav_sliding", dark))
    }

    @Test fun nav_sliding_light() = navSliding(false)
    @Test fun nav_sliding_dark() = navSliding(true)

    private fun sheetArriving(dark: Boolean) {
        frame(dark) {
            Box(Modifier.fillMaxWidth().height(320.dp)) {
                PayFlowBottomSheet(visible = true, onDismissRequest = {}) {
                    Text("Pay with", style = PayFlowTheme.type.title, modifier = Modifier.padding(Spacing.screen).height(120.dp))
                }
            }
        }
        rule.mainClock.advanceTimeBy(96)
        rule.onNodeWithTag(ROOT).captureNode(name("sheet_arriving", dark))
    }

    @Test fun sheet_arriving_light() = sheetArriving(false)
    @Test fun sheet_arriving_dark() = sheetArriving(true)

    private fun fingerDown(dark: Boolean) {
        frame(dark) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PrimaryButton("Pay ₹1,250", {}, Modifier.fillMaxWidth().testTag("primary"))
                TactileButton("Send reminder", {}, Modifier.fillMaxWidth().testTag("tactile"))
            }
        }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("primary").performTouchInput { down(center) }
        rule.onNodeWithTag("tactile").performTouchInput { down(1, center) }
        rule.mainClock.advanceTimeBy(84)
        rule.onNodeWithTag(ROOT).captureNode(name("finger_down_84ms", dark))
    }

    @Test fun finger_down_light() = fingerDown(false)
    @Test fun finger_down_dark() = fingerDown(true)

    private companion object {
        const val ROOT = "frame"
        val NAV = listOf(
            NavItem("home", "Home", NavGlyphs.Home),
            NavItem("scan", "Scan", NavGlyphs.Scan),
            NavItem("history", "History", NavGlyphs.History),
            NavItem("me", "You", NavGlyphs.Profile),
        )
    }
}
