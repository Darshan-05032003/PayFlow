package com.darshan.payflow.ui.lock

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.security.PinRule
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.SecureWindow
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class LockScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val holder = mutableStateOf<LockUiState>(LockUiState.Checking)

    private fun show(state: LockUiState, callbacks: LockCallbacks = LockCallbacks()) {
        holder.value = state
        rule.setContent { val s by holder; PayFlowTheme { LockScreen(s, callbacks) } }
    }

    @Test
    fun lockedScreenKeypadAndBiometric() {
        val digits = mutableListOf<String>(); var bio = false
        show(LockUiState.Locked(enteredDigits = 2, remainingAttempts = 5, lockoutRemainingMillis = 0, biometricAvailable = true, biometricMessage = null, error = null),
            LockCallbacks(onDigit = { digits += it }, onBiometric = { bio = true }))
        rule.onNodeWithTag(PayFlowTestTags.lockKey("7")).performClick()
        rule.onNodeWithTag(PayFlowTestTags.LOCK_BIOMETRIC).performClick()
        assertThat(digits).containsExactly("7")
        assertThat(bio).isTrue()
        rule.onNodeWithContentDescription("2 of 6 digits entered").assertExists()
        holder.value = LockUiState.Locked(0, 2, 0, true, null, error = LockError.WRONG_PIN)
        rule.onNodeWithText("Wrong PIN. 2 attempts left").assertIsDisplayed()
        holder.value = LockUiState.Locked(0, 0, 30_000, true, null, error = null)
        rule.onNodeWithText("Too many attempts. Try again in 30 s").assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.lockKey("7")).assertIsNotEnabled()
    }

    @Test
    fun setupStepsAndErrors() {
        show(LockUiState.Setup(SetupStep.ENTER, null))
        rule.onNodeWithText("Choose a 6-digit PIN").assertIsDisplayed()
        holder.value = LockUiState.Setup(SetupStep.CONFIRM, null)
        rule.onNodeWithText("Confirm your PIN").assertIsDisplayed()
        holder.value = LockUiState.Setup(SetupStep.ENTER, PinRule.TooSimple)
        rule.onNodeWithText("That PIN is too easy to guess").assertIsDisplayed()
    }

    @Test
    fun lockScreenIsSecureAndFiltersObscuredTouches() {
        show(LockUiState.Locked(0, 5, 0, false, null, null))
        rule.waitForIdle()
        val flags = rule.activity.window.attributes.flags
        assertThat(flags and WindowManager.LayoutParams.FLAG_SECURE).isEqualTo(WindowManager.LayoutParams.FLAG_SECURE)
        assertThat(rule.activity.window.decorView.filterTouchesWhenObscured).isTrue()
    }

    @Test
    fun secureWindowClearsTheFlagWhenItLeavesComposition() {
        val secure = mutableStateOf(true)
        rule.setContent { if (secure.value) SecureWindow() }
        rule.waitForIdle()
        assertThat(rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE).isNotEqualTo(0)
        secure.value = false
        rule.waitForIdle()
        assertThat(rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE).isEqualTo(0)
    }
}
