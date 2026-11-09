package com.darshan.payflow.ui.lock

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.darshan.payflow.data.security.PinRule
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.sqrt

class LockMotionLogicTest {
    @Test
    fun `dots show the digits entered`() {
        assertThat(LockMotionLogic.visibleDots(entered = 3, rejecting = false)).isEqualTo(3)
        assertThat(LockMotionLogic.visibleDots(entered = 0, rejecting = false)).isEqualTo(0)
    }

    @Test
    fun `a refused pin stays full while it shakes`() {
        assertThat(LockMotionLogic.visibleDots(entered = 0, rejecting = true)).isEqualTo(6)
    }

    @Test
    fun `typing during the shake wins at once`() {
        assertThat(LockMotionLogic.visibleDots(entered = 1, rejecting = true)).isEqualTo(1)
    }

    @Test
    fun `dot count stays inside the pin length`() {
        assertThat(LockMotionLogic.visibleDots(entered = 99, rejecting = false)).isEqualTo(6)
        assertThat(LockMotionLogic.visibleDots(entered = -1, rejecting = false)).isEqualTo(0)
    }

    @Test
    fun `a rejection plays once per refusal`() {
        assertThat(LockMotionLogic.shouldPlayRejection(seen = 0, current = 0)).isFalse()
        assertThat(LockMotionLogic.shouldPlayRejection(seen = 0, current = 1)).isTrue()
        assertThat(LockMotionLogic.shouldPlayRejection(seen = 1, current = 1)).isFalse()
    }

    @Test
    fun `the countdown rounds up and never reads zero early`() {
        assertThat(LockMotionLogic.lockoutSeconds(30_000)).isEqualTo(30)
        assertThat(LockMotionLogic.lockoutSeconds(29_001)).isEqualTo(30)
        assertThat(LockMotionLogic.lockoutSeconds(1)).isEqualTo(1)
        assertThat(LockMotionLogic.lockoutSeconds(0)).isEqualTo(0)
        assertThat(LockMotionLogic.lockoutSeconds(-5)).isEqualTo(0)
    }

    @Test
    fun `the keypad is off during a lockout and whenever nothing can be typed`() {
        val locked = LockUiState.Locked(0, 5, 0, false, null, null)
        assertThat(LockMotionLogic.keypadEnabled(locked)).isTrue()
        assertThat(LockMotionLogic.keypadEnabled(locked.copy(lockoutRemainingMillis = 1))).isFalse()
        assertThat(LockMotionLogic.keypadEnabled(LockUiState.Setup(SetupStep.ENTER, PinRule.TooSimple))).isTrue()
        assertThat(LockMotionLogic.keypadEnabled(LockUiState.Checking)).isFalse()
        assertThat(LockMotionLogic.keypadEnabled(LockUiState.Unlocked(true))).isFalse()
    }

    @Test
    fun `the reveal radius reaches the furthest corner`() {
        val size = Size(300f, 400f)
        assertThat(LockMotionLogic.revealRadius(Offset(150f, 200f), size)).isWithin(0.01f).of(250f)
        assertThat(LockMotionLogic.revealRadius(Offset.Zero, size)).isWithin(0.01f).of(500f)
        assertThat(LockMotionLogic.revealRadius(Offset(300f, 400f), size)).isWithin(0.01f).of(500f)
        assertThat(LockMotionLogic.revealRadius(Offset(0f, 200f), size)).isWithin(0.01f).of(sqrt(300f * 300f + 200f * 200f))
    }

    @Test
    fun `the reveal opens from the control that unlocked`() {
        val size = Size(300f, 400f)
        val dots = Offset(150f, 100f)
        val finger = Offset(50f, 380f)
        assertThat(LockMotionLogic.revealOrigin(UnlockMethod.Biometric, dots, finger, size)).isEqualTo(finger)
        assertThat(LockMotionLogic.revealOrigin(UnlockMethod.Pin, dots, finger, size)).isEqualTo(dots)
        assertThat(LockMotionLogic.revealOrigin(null, dots, finger, size)).isEqualTo(dots)
    }

    @Test
    fun `the reveal falls back when a control was never measured`() {
        val size = Size(300f, 400f)
        val dots = Offset(150f, 100f)
        assertThat(LockMotionLogic.revealOrigin(UnlockMethod.Biometric, dots, null, size)).isEqualTo(dots)
        assertThat(LockMotionLogic.revealOrigin(UnlockMethod.Biometric, null, null, size)).isEqualTo(Offset(150f, 200f))
        assertThat(LockMotionLogic.revealOrigin(UnlockMethod.Pin, null, Offset(1f, 1f), size)).isEqualTo(Offset(150f, 200f))
    }
}
