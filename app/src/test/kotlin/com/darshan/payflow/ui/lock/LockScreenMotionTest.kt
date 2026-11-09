package com.darshan.payflow.ui.lock

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.security.PinRule
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.assertInside
import com.darshan.payflow.ui.designsystem.commitState
import com.darshan.payflow.ui.designsystem.motion.MotionDurations
import com.darshan.payflow.ui.designsystem.recompositionCycles
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class LockScreenMotionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val idle = LockUiState.Locked(0, 5, 0, biometricAvailable = false, biometricMessage = null, error = null)
    private var state by mutableStateOf<LockUiState>(idle)

    private fun show(initial: LockUiState = idle, reduceMotion: Boolean = false, fontScale: Float = 1f, callbacks: LockCallbacks = LockCallbacks()) {
        state = initial
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                PayFlowTheme(darkTheme = false, reduceMotion = reduceMotion) { LockScreen(state, callbacks) }
            }
        }
        rule.mainClock.advanceTimeByFrame()
    }

    private fun dots(): ImageBitmap = rule.onNodeWithTag(PayFlowTestTags.LOCK_DOTS).captureToImage()
    private fun ImageBitmap.same(other: ImageBitmap): Boolean {
        val a = toPixelMap()
        val b = other.toPixelMap()
        if (a.width != b.width || a.height != b.height) return false
        for (x in 0 until a.width) for (y in 0 until a.height) if (a[x, y] != b[x, y]) return false
        return true
    }
    private fun dotsBounds() = rule.onNodeWithTag(PayFlowTestTags.LOCK_DOTS).fetchSemanticsNode().boundsInRoot

    @Test
    fun aDotFillsOnASpringNotInOneJump() {
        show()
        val empty = dots()
        commitState { state = idle.copy(enteredDigits = 1) }
        repeat(4) { rule.mainClock.advanceTimeByFrame() }
        val filling = dots()
        rule.mainClock.advanceTimeBy(1_000)
        val full = dots()
        assertThat(filling.same(empty)).isFalse()
        assertThat(filling.same(full)).isFalse()
        assertThat(full.same(empty)).isFalse()
    }

    @Test
    fun aRefusalShakesTheRowAndKeepsItFullThenDrainsIt() {
        show(idle.copy(enteredDigits = 5))
        rule.mainClock.advanceTimeBy(1_000)
        val rest = dotsBounds().left
        commitState { state = idle.copy(error = LockError.WRONG_PIN, remainingAttempts = 4, rejections = 1) }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(MotionDurations.Shake / 6L)
        rule.onNodeWithText("Wrong PIN. 4 attempts left").assertExists()
        val shaking = dots()
        rule.mainClock.advanceTimeBy(MotionDurations.Shake * 3L)
        val drained = dots()
        assertThat(shaking.same(drained)).isFalse()
        assertThat(dotsBounds().left).isWithin(1f).of(rest)
        // Drained means back to the empty row: compare with a fresh look at zero digits after a refusal has fully played.
        commitState { state = idle.copy(rejections = 1) }
        rule.mainClock.advanceTimeBy(1_000)
        assertThat(dots().same(drained)).isTrue()
    }

    @Test
    fun theSameRefusalTwiceStillPlaysTwice() {
        show()
        rule.mainClock.advanceTimeBy(1_000)
        val rest = dots()
        commitState { state = idle.copy(error = LockError.WRONG_PIN, remainingAttempts = 4, rejections = 1) }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(MotionDurations.Shake / 6L)
        assertThat(dots().same(rest)).isFalse()
        rule.mainClock.advanceTimeBy(MotionDurations.Shake * 3L)
        commitState { state = idle.copy(error = LockError.WRONG_PIN, remainingAttempts = 3, rejections = 2) }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(MotionDurations.Shake / 6L)
        assertThat(dots().same(rest)).isFalse()
    }

    @Test
    fun nothingReplaysWithoutANewRefusal() {
        show(idle.copy(error = LockError.WRONG_PIN, remainingAttempts = 4, rejections = 1))
        rule.mainClock.advanceTimeByFrame()
        val first = dots()
        rule.mainClock.advanceTimeBy(MotionDurations.Shake / 6L)
        assertThat(dots().same(first)).isTrue()
    }

    @Test
    fun typingDuringTheShakeShowsTheNewDigitAtOnce() {
        show()
        commitState { state = idle.copy(error = LockError.WRONG_PIN, remainingAttempts = 4, rejections = 1) }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(MotionDurations.Shake / 6L)
        commitState { state = idle.copy(enteredDigits = 1, rejections = 1) }
        rule.mainClock.advanceTimeBy(MotionDurations.Shake * 3L)
        rule.onNodeWithContentDescription("1 of 6 digits entered").assertExists()
        commitState { state = idle.copy(enteredDigits = 0, rejections = 1) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithContentDescription("0 of 6 digits entered").assertExists()
    }

    @Test
    fun reducedMotionHasNoShakeButTheMessageAndPulseRemain() {
        show(reduceMotion = true)
        rule.mainClock.advanceTimeBy(1_000)
        val rest = dots()
        val left = dotsBounds().left
        commitState { state = idle.copy(error = LockError.WRONG_PIN, remainingAttempts = 4, rejections = 1) }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("Wrong PIN. 4 attempts left").assertExists()
        assertThat(dotsBounds().left).isWithin(0.5f).of(left)
        assertThat(dots().same(rest)).isFalse()
        rule.mainClock.advanceTimeBy(1_000)
        assertThat(dots().same(rest)).isTrue()
    }

    @Test
    fun aLockoutDisablesEveryKeyAndCountsDown() {
        val ticks = mutableListOf<Unit>()
        show(idle.copy(lockoutRemainingMillis = 30_000, error = LockError.LOCKED_OUT), callbacks = LockCallbacks(onTick = { ticks += Unit }))
        rule.onNodeWithText("Too many attempts. Try again in 30 s").assertExists()
        for (digit in 0..9) rule.onNodeWithTag(PayFlowTestTags.lockKey("$digit")).assertIsNotEnabled()
        rule.onNodeWithTag(PayFlowTestTags.LOCK_BACKSPACE).assertIsNotEnabled()
        rule.mainClock.advanceTimeBy(1_100)
        assertThat(ticks).hasSize(1)
        commitState { state = idle }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag(PayFlowTestTags.lockKey("0")).assertIsEnabled()
    }

    @Test
    fun everyKeyReportsItsDigit() {
        val typed = mutableListOf<String>()
        var backspaces = 0
        show(callbacks = LockCallbacks(onDigit = { typed += it }, onBackspace = { backspaces++ }))
        for (digit in 0..9) rule.onNodeWithTag(PayFlowTestTags.lockKey("$digit")).performClick()
        rule.onNodeWithTag(PayFlowTestTags.LOCK_BACKSPACE).performClick()
        rule.mainClock.advanceTimeByFrame()
        assertThat(typed).containsExactly("0", "1", "2", "3", "4", "5", "6", "7", "8", "9").inOrder()
        assertThat(backspaces).isEqualTo(1)
    }

    @Test
    fun everyKeyIsATouchTargetAndHasALabel() {
        show(idle.copy(biometricAvailable = true))
        val density = rule.density
        for (tag in (0..9).map { PayFlowTestTags.lockKey("$it") } + PayFlowTestTags.LOCK_BACKSPACE + PayFlowTestTags.LOCK_BIOMETRIC) {
            val bounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertThat(bounds.width).isAtLeast(with(density) { com.darshan.payflow.ui.designsystem.tokens.Sizes.touchTarget.toPx() })
            assertThat(bounds.height).isAtLeast(with(density) { com.darshan.payflow.ui.designsystem.tokens.Sizes.touchTarget.toPx() })
        }
        rule.onNodeWithContentDescription("Use fingerprint or face").assertExists()
        rule.onNodeWithContentDescription("Delete last digit").assertExists()
    }

    @Test
    fun largeTextFitsWithoutClipping() {
        show(idle.copy(error = LockError.WRONG_PIN, remainingAttempts = 4, rejections = 1, biometricAvailable = true), fontScale = 1.3f)
        rule.mainClock.advanceTimeBy(1_000)
        for (digit in 0..9) {
            rule.onNodeWithTag(PayFlowTestTags.lockKey("$digit")).assertInside(rule.onRoot())
        }
        rule.onNodeWithText("Wrong PIN. 4 attempts left").assertInside(rule.onRoot())
    }

    @Test
    fun setupShowsItsStepsAndItsRefusals() {
        show(LockUiState.Setup(SetupStep.ENTER, null))
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText("Choose a 6-digit PIN").assertExists()
        commitState { state = LockUiState.Setup(SetupStep.CONFIRM, null) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText("Confirm your PIN").assertExists()
        commitState { state = LockUiState.Setup(SetupStep.ENTER, PinRule.Mismatch) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText("The PINs did not match. Start again.").assertExists()
    }

    @Test
    fun backDuringSetupMeansNotNow() {
        var cancelled = 0
        show(LockUiState.Setup(SetupStep.ENTER, null), callbacks = LockCallbacks(onCancelSetup = { cancelled++ }))
        rule.mainClock.advanceTimeByFrame()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        assertThat(cancelled).isEqualTo(1)
    }

    @Test
    fun laterIsOfferedDuringSetup() {
        var cancelled = 0
        show(LockUiState.Setup(SetupStep.ENTER, null), callbacks = LockCallbacks(onCancelSetup = { cancelled++ }))
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("Not now").performClick()
        rule.mainClock.advanceTimeByFrame()
        assertThat(cancelled).isEqualTo(1)
    }

    @Test
    fun aDotAnimationDoesNotRecomposeEveryFrame() {
        show()
        commitState { state = idle.copy(enteredDigits = 1) }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        val before = recompositionCycles()
        repeat(8) { rule.mainClock.advanceTimeByFrame() }
        assertThat(recompositionCycles() - before).isAtMost(1L)
    }
}
