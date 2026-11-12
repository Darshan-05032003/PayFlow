package com.darshan.payflow.ui.designsystem

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.ui.designsystem.components.PayFlowToast
import com.darshan.payflow.ui.designsystem.effects.ToastTone
import com.darshan.payflow.ui.me.MeCallbacks
import com.darshan.payflow.ui.me.MeScreen
import com.darshan.payflow.ui.me.MeUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Text that explains something stays whole at large font sizes: no line cap that cuts it, no ellipsis that hides the end. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text layout: the legacy mode measures every text as one line
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class LargeTextTest(private val fontScale: Float) {
    @get:Rule val rule = createComposeRule()

    @Test
    fun aLongToastMessageIsShownWhole() {
        // The catalog's sample: at 1.3× its last words were clipped at three lines with no ellipsis.
        val message = "A long failure message explains exactly what went wrong and what to try next, across several lines."
        rule.setDs(fontScale = fontScale) { PayFlowToast(message, ToastTone.Failed) }
        rule.onNodeWithText(message, useUnmergedTree = true).assertTextWhole()
    }

    @Test
    fun meRowsShowTheirWholeSubtitle() {
        rule.setDs(fontScale = fontScale) { MeScreen(MeUiState(loading = false, profile = null, pinConfigured = true), MeCallbacks(), version = "1.0") }
        rule.onNodeWithText("App lock on · checks and payment mode", useUnmergedTree = true).assertTextWhole()
        rule.onNodeWithText("Your QR and UPI ID", useUnmergedTree = true).assertTextWhole()
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}x")
        fun scales() = listOf(arrayOf<Any>(1f), arrayOf<Any>(1.3f), arrayOf<Any>(2f))
    }
}
