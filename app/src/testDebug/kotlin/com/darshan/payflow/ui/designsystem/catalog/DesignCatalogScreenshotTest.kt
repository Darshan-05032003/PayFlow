package com.darshan.payflow.ui.designsystem.catalog

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.ui.designsystem.captureNode
import com.darshan.payflow.ui.theme.PayFlowTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** One baseline per catalog section per theme. A new component in the catalog is covered by adding its section. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h2600dp-xhdpi")
class DesignCatalogScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private fun capture(key: String, dark: Boolean, fontScale: Float = 1f) {
        val section = CatalogSections.first { it.key == key }
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                PayFlowTheme(darkTheme = dark) { CatalogSectionView(section) }
            }
        }
        composeRule.mainClock.advanceTimeBy(SETTLE_MS)
        val suffix = if (fontScale == 1f) "" else "_large_font"
        composeRule.onNodeWithTag("catalog:$key").captureNode("ds_${key}_${if (dark) "dark" else "light"}$suffix")
    }

    @Test fun buttons_primary_large_font() = capture("buttons_primary", false, LARGE)
    @Test fun inputs_large_font() = capture("inputs", true, LARGE)
    @Test fun nav_large_font() = capture("nav", false, LARGE)
    @Test fun odometer_large_font() = capture("odometer", true, LARGE)
    @Test fun status_large_font() = capture("status", false, LARGE)
    @Test fun structure_large_font() = capture("structure", true, LARGE)
    @Test fun toasts_large_font() = capture("toasts", false, LARGE)

    @Test fun colors_light() = capture("colors", false)
    @Test fun colors_dark() = capture("colors", true)
    @Test fun type_light() = capture("type", false)
    @Test fun type_dark() = capture("type", true)
    @Test fun surfaces_light() = capture("surfaces", false)
    @Test fun surfaces_dark() = capture("surfaces", true)
    @Test fun buttons_primary_light() = capture("buttons_primary", false)
    @Test fun buttons_primary_dark() = capture("buttons_primary", true)
    @Test fun buttons_tactile_light() = capture("buttons_tactile", false)
    @Test fun buttons_tactile_dark() = capture("buttons_tactile", true)
    @Test fun buttons_ghost_light() = capture("buttons_ghost", false)
    @Test fun buttons_ghost_dark() = capture("buttons_ghost", true)
    @Test fun buttons_icon_light() = capture("buttons_icon", false)
    @Test fun buttons_icon_dark() = capture("buttons_icon", true)
    @Test fun inputs_light() = capture("inputs", false)
    @Test fun inputs_dark() = capture("inputs", true)
    @Test fun odometer_light() = capture("odometer", false)
    @Test fun odometer_dark() = capture("odometer", true)
    @Test fun avatars_light() = capture("avatars", false)
    @Test fun avatars_dark() = capture("avatars", true)
    @Test fun status_light() = capture("status", false)
    @Test fun status_dark() = capture("status", true)
    @Test fun sheet_light() = capture("sheet", false)
    @Test fun sheet_dark() = capture("sheet", true)
    @Test fun skeleton_light() = capture("skeleton", false)
    @Test fun skeleton_dark() = capture("skeleton", true)
    @Test fun toasts_light() = capture("toasts", false)
    @Test fun toasts_dark() = capture("toasts", true)
    @Test fun states_light() = capture("states", false)
    @Test fun states_dark() = capture("states", true)
    @Test fun illustrations_light() = capture("illustrations", false)
    @Test fun illustrations_dark() = capture("illustrations", true)
    @Test fun nav_light() = capture("nav", false)
    @Test fun nav_dark() = capture("nav", true)
    @Test fun structure_light() = capture("structure", false)
    @Test fun structure_dark() = capture("structure", true)
    @Test fun selection_light() = capture("selection", false)
    @Test fun selection_dark() = capture("selection", true)
    @Test fun loaders_light() = capture("loaders", false)
    @Test fun loaders_dark() = capture("loaders", true)
    @Test fun effects_light() = capture("effects", false)
    @Test fun effects_dark() = capture("effects", true)

    private companion object {
        const val SETTLE_MS = 3_000L
        const val LARGE = 1.3f
    }
}
