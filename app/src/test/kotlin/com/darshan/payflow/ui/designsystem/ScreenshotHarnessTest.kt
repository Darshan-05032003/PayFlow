package com.darshan.payflow.ui.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.ui.theme.PayFlowTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Proves the screenshot pipeline (Robolectric native graphics + Roborazzi) renders text and colour on the JVM. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreenshotHarnessTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun harness_renders_text_and_colour() {
        composeRule.setContent {
            PayFlowTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.padding(24.dp)) { Text("₹1,23,456.00", style = MaterialTheme.typography.headlineMedium) }
                }
            }
        }
        composeRule.captureScreenshot("harness")
    }
}
