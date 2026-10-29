package com.darshan.payflow.ui.lock

import app.cash.turbine.test
import com.darshan.payflow.data.security.AppLock
import com.darshan.payflow.data.security.BiometricAvailability
import com.darshan.payflow.data.security.BiometricResult
import com.darshan.payflow.data.security.InMemorySecureStore
import com.darshan.payflow.data.security.PinManager
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.FakeBiometricAuthenticator
import com.darshan.payflow.testutil.MainDispatcherRule
import com.darshan.payflow.testutil.TestDispatcherProvider
import com.darshan.payflow.testutil.fixedClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class AppLockViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val mainRule = MainDispatcherRule(dispatcher)
    private val store = InMemorySecureStore()
    private val biometric = FakeBiometricAuthenticator()
    private var now = FIXED_INSTANT
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
        override fun instant() = now
    }
    private val pins = PinManager(store, clock, iterations = 1_000)
    private val appLock = AppLock(pins, clock)

    private fun vm() = AppLockViewModel(appLock, pins, biometric, TestDispatcherProvider(dispatcher), clock)

    @Test
    fun `without a pin the app is open but setup is offered`() = runTest(dispatcher) {
        vm().uiState.test {
            assertThat(awaitItem()).isEqualTo(LockUiState.Checking)
            assertThat(awaitItem()).isEqualTo(LockUiState.Unlocked(pinConfigured = false))
        }
    }

    @Test
    fun `setup requires a valid pin typed twice and then locks on next launch`() = runTest(dispatcher) {
        val vm = vm()
        vm.uiState.test { awaitItem(); awaitItem() }
        vm.startSetup()
        assertThat(vm.uiState.value).isEqualTo(LockUiState.Setup(step = SetupStep.ENTER, error = null))
        vm.onSetupPin("123456"); advanceUntilIdle()
        assertThat((vm.uiState.value as LockUiState.Setup).error).isEqualTo(com.darshan.payflow.data.security.PinRule.TooSimple)
        vm.onSetupPin("493027"); advanceUntilIdle()
        assertThat(vm.uiState.value).isEqualTo(LockUiState.Setup(step = SetupStep.CONFIRM, error = null))
        vm.onSetupPin("493028"); advanceUntilIdle()
        assertThat(vm.uiState.value).isEqualTo(LockUiState.Setup(step = SetupStep.ENTER, error = com.darshan.payflow.data.security.PinRule.Mismatch))
        vm.onSetupPin("493027"); advanceUntilIdle()
        vm.onSetupPin("493027"); advanceUntilIdle()
        assertThat(vm.uiState.value).isEqualTo(LockUiState.Unlocked(pinConfigured = true))
        assertThat(pins.isSet()).isTrue()

        // Next launch: locked.
        val fresh = AppLockViewModel(AppLock(pins, clock), pins, biometric, TestDispatcherProvider(dispatcher), clock)
        fresh.uiState.test {
            awaitItem()
            val locked = awaitItem() as LockUiState.Locked
            assertThat(locked.biometricAvailable).isTrue()
            assertThat(locked.lockoutRemainingMillis).isEqualTo(0)
        }
    }

    @Test
    fun `pin unlock, wrong attempts and lockout countdown`() = runTest(dispatcher) {
        pins.set("493027")
        val vm = vm()
        vm.uiState.test { awaitItem(); awaitItem() }
        vm.onPinEntered("000000"); advanceUntilIdle()
        assertThat((vm.uiState.value as LockUiState.Locked).remainingAttempts).isEqualTo(4)
        repeat(4) { vm.onPinEntered("000000"); advanceUntilIdle() }
        val locked = vm.uiState.value as LockUiState.Locked
        assertThat(locked.lockoutRemainingMillis).isEqualTo(30_000)
        vm.onPinEntered("493027"); advanceUntilIdle()
        assertThat((vm.uiState.value as LockUiState.Locked).lockoutRemainingMillis).isEqualTo(30_000)
        now = now.plusSeconds(31)
        vm.onPinEntered("493027"); advanceUntilIdle()
        assertThat(vm.uiState.value).isEqualTo(LockUiState.Unlocked(pinConfigured = true))
    }

    @Test
    fun `biometric success unlocks, failure or unavailability falls back to the pin`() = runTest(dispatcher) {
        pins.set("493027")
        biometric.result = BiometricResult.Success
        val vm = vm()
        vm.uiState.test { awaitItem(); awaitItem() }
        vm.onBiometricRequested(); advanceUntilIdle()
        assertThat(vm.uiState.value).isEqualTo(LockUiState.Unlocked(pinConfigured = true))

        appLock.lock(); advanceUntilIdle() // the lock-state collector moves the UI to Locked
        biometric.result = BiometricResult.Failed("too many attempts")
        vm.onBiometricRequested(); advanceUntilIdle()
        val locked = vm.uiState.value as LockUiState.Locked
        assertThat(locked.biometricMessage).isEqualTo("too many attempts")

        biometric.availability = BiometricAvailability.NONE_ENROLLED
        val vm2 = AppLockViewModel(AppLock(pins, clock), pins, biometric, TestDispatcherProvider(dispatcher), clock) // fresh launch
        vm2.uiState.test { awaitItem(); assertThat((awaitItem() as LockUiState.Locked).biometricAvailable).isFalse() }
    }

    @Test
    fun `app relocks after being in the background longer than the grace period`() = runTest(dispatcher) {
        pins.set("493027")
        val vm = vm()
        vm.uiState.test { awaitItem(); awaitItem() }
        vm.onPinEntered("493027"); advanceUntilIdle()
        appLock.onBackground()
        now = now.plusSeconds(10)
        appLock.onForeground()
        assertThat(appLock.isLocked.value).isFalse() // within grace
        appLock.onBackground()
        now = now.plusSeconds(AppLock.GRACE_SECONDS + 1)
        appLock.onForeground()
        assertThat(appLock.isLocked.value).isTrue()
        advanceUntilIdle()
        assertThat(vm.uiState.value).isInstanceOf(LockUiState.Locked::class.java)
    }

    @Test
    fun `pin entry only accepts digits and clears after each attempt`() = runTest(dispatcher) {
        pins.set("493027")
        val vm = vm()
        vm.uiState.test { awaitItem(); awaitItem() }
        vm.onPinDigit("4"); vm.onPinDigit("x"); vm.onPinDigit("9")
        assertThat((vm.uiState.value as LockUiState.Locked).enteredDigits).isEqualTo(2)
        vm.onPinBackspace()
        assertThat((vm.uiState.value as LockUiState.Locked).enteredDigits).isEqualTo(1)
        "93027".forEach { vm.onPinDigit(it.toString()) } // auto-submits at 6 digits
        advanceUntilIdle()
        assertThat(vm.uiState.value).isEqualTo(LockUiState.Unlocked(pinConfigured = true))
    }
}
