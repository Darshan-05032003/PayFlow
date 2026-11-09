package com.darshan.payflow.ui.lock

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.security.PinRule
import com.darshan.payflow.ui.PayFlowTestTags
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

/** The gate as a user and as an attacker see it: frames from the reveal, and what exists in the tree at each step. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class AppGateTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val locked = LockUiState.Locked(0, 5, 0, biometricAvailable = true, biometricMessage = null, error = null)
    private var state by mutableStateOf<LockUiState>(locked)
    private var method by mutableStateOf<UnlockMethod?>(null)
    private var appCompositions = 0
    private val digits = mutableListOf<String>()

    private fun show(initial: LockUiState = locked, reduceMotion: Boolean = false) {
        state = initial
        rule.mainClock.autoAdvance = false
        rule.setContent {
            PayFlowTheme(darkTheme = true, reduceMotion = reduceMotion) {
                AppGate(state, method, LockCallbacks(onDigit = { digits += it })) {
                    appCompositions++
                    Box(Modifier.fillMaxSize().background(APP_COLOUR).testTag(APP))
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
    }

    private fun appInTree() = rule.onAllNodes(hasTestTag(APP)).fetchSemanticsNodes().isNotEmpty()
    private fun keysInTree() = rule.onAllNodes(hasTestTag(PayFlowTestTags.lockKey("1"))).fetchSemanticsNodes().isNotEmpty()
    private fun frame(): ImageBitmap = rule.onRoot().captureToImage()
    private fun ImageBitmap.isApp(x: Int, y: Int) = toPixelMap()[x, y] == APP_COLOUR

    private fun settle() = rule.mainClock.advanceTimeBy(MotionDurations.Screen + FRAME_SLACK_MS)

    @Test
    fun theAppIsNeverComposedWhileGated() {
        show()
        repeat(5) { rule.mainClock.advanceTimeByFrame() }
        commitState { state = locked.copy(enteredDigits = 3) }
        rule.mainClock.advanceTimeByFrame()
        commitState { state = locked.copy(error = LockError.WRONG_PIN, rejections = 1) }
        settle()
        commitState { state = locked.copy(lockoutRemainingMillis = 30_000, error = LockError.LOCKED_OUT, rejections = 2) }
        rule.mainClock.advanceTimeByFrame()
        commitState { state = LockUiState.Setup(SetupStep.ENTER, PinRule.Mismatch) }
        rule.mainClock.advanceTimeByFrame()
        commitState { state = LockUiState.Checking }
        rule.mainClock.advanceTimeByFrame()
        assertThat(appCompositions).isEqualTo(0)
        assertThat(appInTree()).isFalse()
    }

    @Test
    fun unlockOpensThroughACircleFromThePinDots() {
        show()
        val dots = rule.onNodeWithTag(PayFlowTestTags.LOCK_DOTS).fetchSemanticsNode().boundsInRoot.center
        method = UnlockMethod.Pin
        commitState { state = LockUiState.Unlocked(pinConfigured = true) }
        rule.mainClock.advanceTimeByFrame()
        val first = frame()
        assertThat(first.isApp(0, 0)).isFalse()
        assertThat(first.isApp(first.width - 1, first.height - 1)).isFalse()

        rule.mainClock.advanceTimeBy(MotionDurations.Screen / 6L)
        val early = frame()
        assertThat(early.isApp(dots.x.toInt(), dots.y.toInt())).isTrue()
        assertThat(early.isApp(early.width - 1, early.height - 1)).isFalse()

        settle()
        val last = frame()
        assertThat(last.isApp(0, 0)).isTrue()
        assertThat(last.isApp(last.width - 1, last.height - 1)).isTrue()
    }

    @Test
    fun biometricUnlockOpensFromTheFingerprintKey() {
        show()
        val key = rule.onNodeWithTag(PayFlowTestTags.LOCK_BIOMETRIC).fetchSemanticsNode().boundsInRoot.center
        val dots = rule.onNodeWithTag(PayFlowTestTags.LOCK_DOTS).fetchSemanticsNode().boundsInRoot.center
        method = UnlockMethod.Biometric
        commitState { state = LockUiState.Unlocked(pinConfigured = true) }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(20)
        val early = frame()
        assertThat(early.isApp(key.x.toInt(), key.y.toInt())).isTrue()
        assertThat(early.isApp(dots.x.toInt(), dots.y.toInt())).isFalse()
    }

    @Test
    fun theFrozenLockTakesNoInputAndIsHiddenFromAccessibilityDuringTheReveal() {
        show()
        val key = rule.onNodeWithTag(PayFlowTestTags.lockKey("5")).fetchSemanticsNode().boundsInRoot.center
        commitState { state = LockUiState.Unlocked(pinConfigured = true) }
        rule.mainClock.advanceTimeByFrame()
        assertThat(keysInTree()).isFalse()
        rule.onRoot().performTouchInput { click(Offset(key.x, key.y)) }
        rule.mainClock.advanceTimeByFrame()
        assertThat(digits).isEmpty()
        assertThat(appInTree()).isTrue()
    }

    @Test
    fun aRelockMidRevealRemovesTheAppInTheNextFrame() {
        show()
        commitState { state = LockUiState.Unlocked(pinConfigured = true) }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(MotionDurations.Screen / 3L)
        assertThat(appInTree()).isTrue()

        commitState { state = locked }
        rule.mainClock.advanceTimeByFrame()
        assertThat(appInTree()).isFalse()
        assertThat(keysInTree()).isTrue()
        val frame = frame()
        assertThat(frame.isApp(frame.width / 2, frame.height / 2)).isFalse()

        rule.onNodeWithTag(PayFlowTestTags.lockKey("5")).performClick()
        rule.mainClock.advanceTimeByFrame()
        assertThat(digits).containsExactly("5")
    }

    @Test
    fun aSecondUnlockRevealsAgainFromTheStart() {
        show()
        commitState { state = LockUiState.Unlocked(pinConfigured = true) }
        rule.mainClock.advanceTimeByFrame()
        settle()
        assertThat(frame().isApp(0, 0)).isTrue()

        commitState { state = locked.copy(enteredDigits = 1) }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        commitState { state = LockUiState.Unlocked(pinConfigured = true) }
        rule.mainClock.advanceTimeByFrame()
        assertThat(frame().isApp(0, 0)).isFalse()
        settle()
        assertThat(frame().isApp(0, 0)).isTrue()
    }

    @Test
    fun noPinMeansNoReveal() {
        show(LockUiState.Checking)
        commitState { state = LockUiState.Unlocked(pinConfigured = false) }
        rule.mainClock.advanceTimeByFrame()
        val frame = frame()
        assertThat(frame.isApp(0, 0)).isTrue()
        assertThat(frame.isApp(frame.width - 1, frame.height - 1)).isTrue()
    }

    @Test
    fun reducedMotionOpensWithAShortFadeAndNoCircle() {
        show(reduceMotion = true)
        commitState { state = LockUiState.Unlocked(pinConfigured = true) }
        rule.mainClock.advanceTimeByFrame()
        assertThat(appInTree()).isTrue()
        rule.mainClock.advanceTimeBy(MotionDurations.ReducedFade + FRAME_SLACK_MS)
        val frame = frame()
        assertThat(frame.isApp(0, 0)).isTrue()
        assertThat(frame.isApp(frame.width - 1, frame.height - 1)).isTrue()
    }

    @Test
    fun theWindowStaysSecureFromLockThroughTheRevealIntoTheApp() {
        show()
        // AppRoot holds its own SecureWindow for the app; here the frozen lock has to hold it through the reveal by itself.
        fun secure() = rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        assertThat(secure()).isTrue()
        commitState { state = LockUiState.Unlocked(pinConfigured = true) }
        rule.mainClock.advanceTimeByFrame()
        repeat(4) {
            rule.mainClock.advanceTimeBy(MotionDurations.Screen / 8L)
            assertThat(secure()).isTrue()
        }
    }

    @Test
    fun theStatusBarStaysWithTheLockUntilTheRevealCoversIt() {
        // Found on the emulator: Home switched the status-bar icons to light while the ivory lock still filled the bar.
        var owns: State<Boolean>? = null
        state = locked
        method = UnlockMethod.Pin
        rule.mainClock.autoAdvance = false
        rule.setContent {
            PayFlowTheme(darkTheme = false) {
                AppGate(state, method, LockCallbacks()) {
                    owns = LocalAppOwnsStatusBar.current
                    Box(Modifier.fillMaxSize().background(APP_COLOUR).testTag(APP))
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        commitState { state = LockUiState.Unlocked(pinConfigured = true) }
        rule.mainClock.advanceTimeByFrame()
        assertThat(owns!!.value).isFalse()
        // Frame by frame: the app may set the icons only while it is what covers the whole top edge.
        var handedOver = false
        repeat(((MotionDurations.Screen + FRAME_SLACK_MS) / FRAME_MS).toInt()) {
            rule.mainClock.advanceTimeByFrame()
            val image = frame()
            if (owns!!.value) {
                handedOver = true
                assertThat(image.isApp(0, 0) && image.isApp(image.width - 1, 0)).isTrue()
            }
        }
        assertThat(handedOver).isTrue()
        assertThat(owns!!.value).isTrue()
    }

    @Test
    fun withNoRevealTheAppOwnsTheStatusBarAtOnce() {
        var owns: State<Boolean>? = null
        state = LockUiState.Unlocked(pinConfigured = false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            PayFlowTheme(darkTheme = false) {
                AppGate(state, method, LockCallbacks()) { owns = LocalAppOwnsStatusBar.current }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        assertThat(owns!!.value).isTrue()
    }

    @Test
    fun theRevealDoesNotRecomposeEveryFrame() {
        show()
        commitState { state = LockUiState.Unlocked(pinConfigured = true) }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        val before = recompositionCycles()
        val compositions = appCompositions
        repeat(10) { rule.mainClock.advanceTimeByFrame() }
        assertThat(recompositionCycles() - before).isAtMost(2L)
        assertThat(appCompositions).isEqualTo(compositions)
    }

    private companion object {
        const val APP = "gate:app"
        const val FRAME_SLACK_MS = 100L
        const val FRAME_MS = 16L
        val APP_COLOUR = Color(0xFF00C853)
    }
}
