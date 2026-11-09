package com.darshan.payflow.ui.designsystem

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage

/** Folder of the committed baselines, relative to the app module (Gradle runs tests from there). */
const val SCREENSHOT_DIR = "src/test/screenshots"

/** A tiny tolerance absorbs anti-aliasing noise between runs without hiding a real visual change. */
@OptIn(ExperimentalRoborazziApi::class)
private val DsOptions = RoborazziOptions(
    compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f),
)

/** Captures the composition root as `<name>.png` in the baseline folder. A no-op outside the Roborazzi tasks. */
fun ComposeTestRule.captureScreenshot(name: String) {
    onRoot().captureRoboImage("$SCREENSHOT_DIR/$name.png", roborazziOptions = DsOptions)
}
