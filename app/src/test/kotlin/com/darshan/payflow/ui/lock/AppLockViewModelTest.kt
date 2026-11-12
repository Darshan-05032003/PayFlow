package com.darshan.payflow.ui.lock

import app.cash.turbine.test
import com.darshan.payflow.core.DispatcherProvider
import com.darshan.payflow.data.security.AppLock
import com.darshan.payflow.data.security.BiometricAvailability
import com.darshan.payflow.data.security.BiometricResult
import com.darshan.payflow.data.security.CountingCryptoBox
import com.darshan.payflow.data.security.CountingStore
import com.darshan.payflow.data.security.InMemorySecureStore
import com.darshan.payflow.data.security.PinManager
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.FakeBiometricAuthenticator
import com.darshan.payflow.testutil.MainDispatcherRule
import com.darshan.payflow.testutil.TestDispatcherProvider
import com.darshan.payflow.testutil.fixedClock
import com.darshan.payflow.ui.designsystem.effects.HapticEvent
import com.darshan.payflow.ui.designsystem.effects.UiEffect
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.ZoneOffset
import kotlin.coroutines.CoroutineContext

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

    /** Holds work until the test lets it run, to look at the state in between. */
    private class ParkedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue += block }
        fun hasWork() = queue.isNotEmpty()
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

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
    fun `relock during pin setup ends the setup and cancelling cannot open the app`() = runTest(dispatcher) {
        pins.set("493027")
        val vm = vm()
        vm.uiState.test { awaitItem(); awaitItem() }
        vm.onPinEntered("493027"); advanceUntilIdle()
        vm.startSetup()
        appLock.lock(); advanceUntilIdle()
        assertThat(vm.uiState.value).isInstanceOf(LockUiState.Locked::class.java)
        vm.cancelSetup(); advanceUntilIdle()
        assertThat(vm.uiState.value).isInstanceOf(LockUiState.Locked::class.java)
        vm.onSetupPin("284913"); advanceUntilIdle()
        assertThat(vm.uiState.value).isInstanceOf(LockUiState.Locked::class.java)
        assertThat(pins.verify("493027")).isEqualTo(com.darshan.payflow.data.security.PinVerification.Ok)
    }

    @Test
    fun `setup cannot start over the lock`() = runTest(dispatcher) {
        pins.set("493027")
        val vm = vm()
        vm.uiState.test { awaitItem(); awaitItem() }
        vm.startSetup()
        assertThat(vm.uiState.value).isInstanceOf(LockUiState.Locked::class.java)
    }

    @Test
    fun `the unlocked state goes the moment the lock lands, before its details have loaded`() = runTest(dispatcher) {
        pins.set("493027")
        val parkedIo = ParkedDispatcher()
        val provider = object : DispatcherProvider {
            override val io: CoroutineDispatcher get() = parkedIo
            override val default: CoroutineDispatcher get() = dispatcher
            override val main: CoroutineDispatcher get() = dispatcher
        }
        val vm = AppLockViewModel(appLock, pins, biometric, provider, clock)
        fun settle() { do { parkedIo.drain(); advanceUntilIdle() } while (parkedIo.hasWork()) }
        settle()
        vm.onPinEntered("493027"); settle()
        assertThat(vm.uiState.value).isEqualTo(LockUiState.Unlocked(pinConfigured = true))
        appLock.lock()
        runCurrent()
        // The lock's details are already in memory, so the lock lands with no IO at all: nothing was drained.
        assertThat(parkedIo.hasWork()).isFalse()
        assertThat(vm.uiState.value).isInstanceOf(LockUiState.Locked::class.java)
        settle()
        assertThat(vm.uiState.value).isInstanceOf(LockUiState.Locked::class.java)
    }

    @Test
    fun `when the lock's details are not known yet, the app still goes before they load`() = runTest(dispatcher) {
        pins.set("493027")
        val parkedIo = ParkedDispatcher()
        val provider = object : DispatcherProvider {
            override val io: CoroutineDispatcher get() = parkedIo
            override val default: CoroutineDispatcher get() = dispatcher
            override val main: CoroutineDispatcher get() = dispatcher
        }
        // A manager with nothing in memory: its details need a read.
        val coldPins = PinManager(store, clock, iterations = 1_000)
        val coldLock = AppLock(coldPins, clock)
        val vm = AppLockViewModel(coldLock, coldPins, biometric, provider, clock)
        runCurrent()
        assertThat(vm.uiState.value).isEqualTo(LockUiState.Checking)
        parkedIo.drain(); runCurrent()
        assertThat(vm.uiState.value).isEqualTo(LockUiState.Checking)
        do { parkedIo.drain(); advanceUntilIdle() } while (parkedIo.hasWork())
        assertThat(vm.uiState.value).isInstanceOf(LockUiState.Locked::class.java)
    }

    @Test
    fun `a lock answered before the screen asks shows the lock from the first state, never the app`() = runTest(dispatcher) {
        PinManager(store, clock, iterations = 1_000).set("493027") // set in an earlier process
        // What StartupPrefetch does in Application.onCreate.
        appLock.refresh()
        pins.lockStatus()
        val vm = vm()
        assertThat(vm.uiState.value).isInstanceOf(LockUiState.Locked::class.java)
        vm.uiState.test {
            assertThat(awaitItem()).isInstanceOf(LockUiState.Locked::class.java)
            advanceUntilIdle()
            expectNoEvents()
        }
    }

    @Test
    fun `a pin answered early but details unknown holds on checking, never the app`() = runTest(dispatcher) {
        PinManager(store, clock, iterations = 1_000).set("493027")
        appLock.refresh()
        val vm = vm()
        assertThat(vm.uiState.value).isEqualTo(LockUiState.Checking)
        advanceUntilIdle()
        assertThat(vm.uiState.value).isInstanceOf(LockUiState.Locked::class.java)
    }

    @Test
    fun `no pin answered early opens the app from the first state`() = runTest(dispatcher) {
        appLock.refresh()
        val vm = vm()
        assertThat(vm.uiState.value).isEqualTo(LockUiState.Unlocked(pinConfigured = false))
        advanceUntilIdle()
        assertThat(vm.uiState.value).isEqualTo(LockUiState.Unlocked(pinConfigured = false))
    }

    @Test
    fun `an early unlocked answer still gives way to a relock before anything suspends`() = runTest(dispatcher) {
        // A new activity in a process that was unlocked earlier, coming back after the grace period: onStart relocks.
        pins.set("493027")
        appLock.refresh()
        appLock.unlock()
        val parkedIo = ParkedDispatcher()
        val provider = object : DispatcherProvider {
            override val io: CoroutineDispatcher get() = parkedIo
            override val default: CoroutineDispatcher get() = dispatcher
            override val main: CoroutineDispatcher get() = dispatcher
        }
        val vm = AppLockViewModel(appLock, pins, biometric, provider, clock)
        assertThat(vm.uiState.value).isEqualTo(LockUiState.Unlocked(pinConfigured = true))
        runCurrent()
        appLock.onBackground()
        now = now.plusSeconds(AppLock.GRACE_SECONDS + 1)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { appLock.onForeground() }
        runCurrent()
        // No IO has run: the collector was listening from the start, and the details came from memory.
        assertThat(vm.uiState.value).isInstanceOf(LockUiState.Locked::class.java)
    }

    @Test
    fun `a locked cold start decrypts once, a start with no pin decrypts nothing`() = runTest(dispatcher) {
        val box = CountingCryptoBox()
        val counted = CountingStore(InMemorySecureStore(box))
        PinManager(counted, clock, iterations = 1_000).set("493027")
        box.reset(); counted.reset()
        val lockedPins = PinManager(counted, clock, iterations = 1_000)
        val locked = AppLockViewModel(AppLock(lockedPins, clock), lockedPins, biometric, TestDispatcherProvider(dispatcher), clock)
        advanceUntilIdle()
        assertThat(locked.uiState.value).isInstanceOf(LockUiState.Locked::class.java)
        // Was four: presence twice, attempts and lockout each decrypted the record.
        assertThat(box.decrypts.get()).isEqualTo(1)
        assertThat(counted.reads.get()).isEqualTo(2)

        val emptyBox = CountingCryptoBox()
        val empty = CountingStore(InMemorySecureStore(emptyBox))
        val openPins = PinManager(empty, clock, iterations = 1_000)
        val open = AppLockViewModel(AppLock(openPins, clock), openPins, biometric, TestDispatcherProvider(dispatcher), clock)
        advanceUntilIdle()
        assertThat(open.uiState.value).isEqualTo(LockUiState.Unlocked(pinConfigured = false))
        assertThat(emptyBox.decrypts.get()).isEqualTo(0)
        assertThat(empty.reads.get()).isEqualTo(1)
    }

    @Test
    fun `a pin removed while unlocked does not strand the user on the lock`() = runTest(dispatcher) {
        pins.set("493027")
        val vm = vm()
        vm.uiState.test { awaitItem(); awaitItem() }
        vm.onPinEntered("493027"); advanceUntilIdle()
        pins.clear()
        appLock.onBackground()
        now = now.plusSeconds(AppLock.GRACE_SECONDS + 1)
        appLock.onForeground(); advanceUntilIdle()
        assertThat(vm.uiState.value).isInstanceOf(LockUiState.Unlocked::class.java)

        // The same thing arriving late: the lock screen is already up when the store says there is no PIN.
        appLock.lock(); advanceUntilIdle()
        assertThat(vm.uiState.value).isInstanceOf(LockUiState.Locked::class.java)
        appLock.refresh(); advanceUntilIdle()
        assertThat(vm.uiState.value).isEqualTo(LockUiState.Unlocked(pinConfigured = false))
    }

    @Test
    fun `typing during a verification cannot burn a second attempt`() = runTest(dispatcher) {
        pins.set("493027")
        val vm = vm()
        vm.uiState.test { awaitItem(); awaitItem() }
        "000000".forEach { vm.onPinDigit(it.toString()) }
        vm.onPinBackspace()
        vm.onPinDigit("1")
        vm.onPinEntered("000000")
        "111111".forEach { vm.onPinDigit(it.toString()) }
        advanceUntilIdle()
        assertThat((vm.uiState.value as LockUiState.Locked).remainingAttempts).isEqualTo(4)
        assertThat((vm.uiState.value as LockUiState.Locked).enteredDigits).isEqualTo(0)
    }

    @Test
    fun `every refused attempt moves the rejection serial, so the same message still shakes`() = runTest(dispatcher) {
        pins.set("493027")
        val vm = vm()
        vm.uiState.test { awaitItem(); awaitItem() }
        val start = (vm.uiState.value as LockUiState.Locked).rejections
        repeat(5) { vm.onPinEntered("000000"); advanceUntilIdle() }
        assertThat((vm.uiState.value as LockUiState.Locked).rejections).isEqualTo(start + 5)
        assertThat((vm.uiState.value as LockUiState.Locked).error).isEqualTo(LockError.LOCKED_OUT)
    }

    @Test
    fun `setup digits live in the view model and never reach the screen as text`() = runTest(dispatcher) {
        val vm = vm()
        vm.uiState.test { awaitItem(); awaitItem() }
        vm.startSetup()
        "4930".forEach { vm.onSetupDigit(it.toString()) }
        vm.onSetupBackspace()
        assertThat(vm.uiState.value).isEqualTo(LockUiState.Setup(SetupStep.ENTER, error = null, enteredDigits = 3))
        vm.onSetupDigit("x")
        assertThat((vm.uiState.value as LockUiState.Setup).enteredDigits).isEqualTo(3)
        "027".forEach { vm.onSetupDigit(it.toString()) }
        assertThat(vm.uiState.value).isEqualTo(LockUiState.Setup(SetupStep.CONFIRM, error = null, enteredDigits = 0))
        "493027".forEach { vm.onSetupDigit(it.toString()) }
        advanceUntilIdle()
        assertThat(vm.uiState.value).isEqualTo(LockUiState.Unlocked(pinConfigured = true))
    }

    @Test
    fun `cancelling setup with no pin leaves the app open`() = runTest(dispatcher) {
        val vm = vm()
        vm.uiState.test { awaitItem(); awaitItem() }
        vm.startSetup()
        vm.cancelSetup(); advanceUntilIdle()
        assertThat(vm.uiState.value).isEqualTo(LockUiState.Unlocked(pinConfigured = false))
    }

    @Test
    fun `a slow double tap on biometrics opens one prompt`() = runTest(dispatcher) {
        pins.set("493027")
        biometric.result = BiometricResult.Failed("no match")
        val vm = vm()
        vm.uiState.test { awaitItem(); awaitItem() }
        vm.onBiometricRequested()
        vm.onBiometricRequested()
        advanceUntilIdle()
        assertThat(biometric.prompts).isEqualTo(1)
    }

    @Test
    fun `the right haptic for each event`() = runTest(dispatcher) {
        pins.set("493027")
        val vm = vm()
        vm.uiState.test { awaitItem(); awaitItem() }
        vm.uiEffects.test {
            vm.onPinDigit("x")
            vm.onPinDigit("0")
            assertThat(awaitItem()).isEqualTo(UiEffect.Haptic(HapticEvent.Tick))
            vm.onPinBackspace()
            assertThat(awaitItem()).isEqualTo(UiEffect.Haptic(HapticEvent.Tick))
            vm.onPinEntered("000000"); advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(UiEffect.Haptic(HapticEvent.Reject))
            biometric.result = BiometricResult.Failed("no match")
            vm.onBiometricRequested(); advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(UiEffect.Haptic(HapticEvent.Reject))
            biometric.result = BiometricResult.Cancelled
            vm.onBiometricRequested(); advanceUntilIdle()
            expectNoEvents()
            vm.onPinEntered("493027"); advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(UiEffect.Haptic(HapticEvent.Confirm))
        }
    }

    @Test
    fun `setup refusals reject and a finished setup confirms`() = runTest(dispatcher) {
        val vm = vm()
        vm.uiState.test { awaitItem(); awaitItem() }
        vm.startSetup()
        vm.uiEffects.test {
            vm.onSetupPin("123456"); advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(UiEffect.Haptic(HapticEvent.Reject))
            vm.onSetupPin("493027"); advanceUntilIdle()
            vm.onSetupPin("000000"); advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(UiEffect.Haptic(HapticEvent.Reject))
            vm.onSetupPin("493027"); advanceUntilIdle()
            vm.onSetupPin("493027"); advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(UiEffect.Haptic(HapticEvent.Confirm))
        }
    }

    @Test
    fun `the view model remembers how the app was unlocked for the reveal`() = runTest(dispatcher) {
        pins.set("493027")
        val vm = vm()
        vm.uiState.test { awaitItem(); awaitItem() }
        assertThat(vm.lastUnlockMethod).isNull()
        vm.onPinEntered("493027"); advanceUntilIdle()
        assertThat(vm.lastUnlockMethod).isEqualTo(UnlockMethod.Pin)
        appLock.lock(); advanceUntilIdle()
        biometric.result = BiometricResult.Success
        vm.onBiometricRequested(); advanceUntilIdle()
        assertThat(vm.lastUnlockMethod).isEqualTo(UnlockMethod.Biometric)
    }

    /** Home's "set up app lock" banner reads this state: a PIN removed or added elsewhere must reach it at once. */
    @Test
    fun `a pin removed or added from security updates the unlocked state at once, and never opens a lock`() = runTest(dispatcher) {
        pins.set("493027")
        val vm = vm()
        vm.uiState.test { awaitItem(); awaitItem() }
        vm.onPinEntered("493027"); advanceUntilIdle()
        assertThat(vm.uiState.value).isEqualTo(LockUiState.Unlocked(pinConfigured = true))
        pins.clear(); advanceUntilIdle()
        assertThat(vm.uiState.value).isEqualTo(LockUiState.Unlocked(pinConfigured = false))
        pins.set("583921"); advanceUntilIdle()
        assertThat(vm.uiState.value).isEqualTo(LockUiState.Unlocked(pinConfigured = true))

        // A change while locked leaves the lock alone: only the lock's own flow may open it.
        appLock.lock(); advanceUntilIdle()
        pins.set("493027"); advanceUntilIdle()
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
