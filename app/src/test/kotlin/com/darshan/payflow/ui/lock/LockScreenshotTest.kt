package com.darshan.payflow.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.security.PinRule
import com.darshan.payflow.ui.designsystem.captureScreenshot
import com.darshan.payflow.ui.designsystem.commitState
import com.darshan.payflow.ui.designsystem.motion.MotionDurations
import com.darshan.payflow.ui.designsystem.setDs
import com.darshan.payflow.ui.theme.PayFlowTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every lock state in both themes, plus frames frozen in the middle of the shake and the reveal. No real PIN appears: only dots. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class LockScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val idle = LockUiState.Locked(0, 5, 0, biometricAvailable = true, biometricMessage = null, error = null)

    private fun shot(name: String, state: LockUiState, dark: Boolean, fontScale: Float = 1f) {
        rule.setDs(dark = dark, fontScale = fontScale) { LockScreen(state, LockCallbacks()) }
        rule.waitForIdle()
        rule.captureScreenshot("lock/$name-${if (dark) "dark" else "light"}")
    }

    @Test fun idleLight() = shot("idle", idle, dark = false)
    @Test fun idleDark() = shot("idle", idle, dark = true)
    @Test fun threeDigitsLight() = shot("digits", idle.copy(enteredDigits = 3), dark = false)
    @Test fun threeDigitsDark() = shot("digits", idle.copy(enteredDigits = 3), dark = true)
    @Test fun noBiometricLight() = shot("no-biometric", idle.copy(biometricAvailable = false), dark = false)
    @Test fun wrongPinDark() = shot("wrong", idle.copy(error = LockError.WRONG_PIN, remainingAttempts = 2), dark = true)
    @Test fun wrongPinLight() = shot("wrong", idle.copy(error = LockError.WRONG_PIN, remainingAttempts = 2), dark = false)
    @Test fun lockoutLight() = shot("lockout", idle.copy(lockoutRemainingMillis = 30_000, error = LockError.LOCKED_OUT, remainingAttempts = 0), dark = false)
    @Test fun lockoutDark() = shot("lockout", idle.copy(lockoutRemainingMillis = 30_000, error = LockError.LOCKED_OUT, remainingAttempts = 0), dark = true)
    @Test fun setupLight() = shot("setup", LockUiState.Setup(SetupStep.ENTER, null, enteredDigits = 2), dark = false)
    @Test fun setupDark() = shot("setup", LockUiState.Setup(SetupStep.ENTER, null, enteredDigits = 2), dark = true)
    @Test fun setupRefusedLight() = shot("setup-refused", LockUiState.Setup(SetupStep.ENTER, PinRule.Mismatch), dark = false)
    @Test fun confirmDark() = shot("setup-confirm", LockUiState.Setup(SetupStep.CONFIRM, null), dark = true)
    @Test fun largeTextLight() = shot("large-text", idle.copy(error = LockError.WRONG_PIN, remainingAttempts = 2), dark = false, fontScale = 1.3f)
    @Test fun largeTextDark() = shot("large-text", LockUiState.Setup(SetupStep.CONFIRM, PinRule.TooSimple), dark = true, fontScale = 1.3f)

    @Test
    fun refusalMidShake() {
        var state by mutableStateOf<LockUiState>(idle)
        rule.mainClock.autoAdvance = false
        rule.setDs(dark = true) { LockScreen(state, LockCallbacks()) }
        rule.mainClock.advanceTimeByFrame()
        commitState { state = idle.copy(error = LockError.WRONG_PIN, remainingAttempts = 4, rejections = 1) }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(MotionDurations.Shake / 5L)
        rule.captureScreenshot("lock/mid-shake-dark")
    }

    private fun revealFrame(name: String, dark: Boolean, method: UnlockMethod) {
        var state by mutableStateOf<LockUiState>(idle.copy(enteredDigits = 6))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            PayFlowTheme(darkTheme = dark) {
                AppGate(state, method, LockCallbacks()) {
                    val colors = PayFlowTheme.colors
                    Box(Modifier.fillMaxSize().background(colors.raised))
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        commitState { state = LockUiState.Unlocked(pinConfigured = true) }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(MotionDurations.Screen / 8L)
        rule.captureScreenshot("lock/$name")
    }

    @Test fun revealFromPinDark() = revealFrame("reveal-pin-dark", dark = true, method = UnlockMethod.Pin)
    @Test fun revealFromPinLight() = revealFrame("reveal-pin-light", dark = false, method = UnlockMethod.Pin)
    @Test fun revealFromFingerprintDark() = revealFrame("reveal-biometric-dark", dark = true, method = UnlockMethod.Biometric)
}
