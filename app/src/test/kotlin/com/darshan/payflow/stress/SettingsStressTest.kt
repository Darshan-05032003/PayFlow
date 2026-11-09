package com.darshan.payflow.stress

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.profile.SecureUpiProfileRepository
import com.darshan.payflow.data.security.BindingStatus
import com.darshan.payflow.data.security.BiometricAvailability
import com.darshan.payflow.data.security.DeviceBindingManager
import com.darshan.payflow.data.security.DeviceIdentity
import com.darshan.payflow.data.security.FakeIntegrityVerifier
import com.darshan.payflow.data.security.FakeOtpSender
import com.darshan.payflow.data.security.InMemorySecureStore
import com.darshan.payflow.data.security.IntegrityChecker
import com.darshan.payflow.data.security.IntegrityResult
import com.darshan.payflow.data.security.IntegrityVerdict
import com.darshan.payflow.data.security.PinManager
import com.darshan.payflow.data.security.TamperDetector
import com.darshan.payflow.data.security.TamperReport
import com.darshan.payflow.data.settings.InMemoryPaymentModeRepository
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.model.UpiProfile
import com.darshan.payflow.domain.usecase.RecordCheckoutResultUseCase
import com.darshan.payflow.testutil.FakeBiometricAuthenticator
import com.darshan.payflow.testutil.FakePaymentRepository
import com.darshan.payflow.testutil.FakeUpiAppLauncher
import com.darshan.payflow.testutil.MainDispatcherRule
import com.darshan.payflow.testutil.TestDispatcherProvider
import com.darshan.payflow.testutil.fixedClock
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.checkout.CheckoutViewModel
import com.darshan.payflow.ui.designsystem.effects.InMemoryUiPreferences
import com.darshan.payflow.ui.designsystem.effects.ThemeMode
import com.darshan.payflow.ui.designsystem.recompositionCycles
import com.darshan.payflow.ui.designsystem.setDs
import com.darshan.payflow.ui.me.MeViewModel
import com.darshan.payflow.ui.receive.ReceiveCallbacks
import com.darshan.payflow.ui.receive.ReceiveScreen
import com.darshan.payflow.ui.receive.ReceiveUiState
import com.darshan.payflow.ui.security.CheckReveal
import com.darshan.payflow.ui.security.IntegrityStatus
import com.darshan.payflow.ui.security.PinRemovalError
import com.darshan.payflow.ui.security.SecurityCallbacks
import com.darshan.payflow.ui.security.SecurityChecks
import com.darshan.payflow.ui.security.SecurityScreen
import com.darshan.payflow.ui.security.SecurityUiState
import com.darshan.payflow.ui.security.SecurityViewModel
import com.google.common.truth.Truth.assertThat
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Security centre, Me, Receive and Checkout at scale: brute force, 1,000-tap races, 1,000 toggles, 1M reveal steps. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SettingsStressTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val mainRule = MainDispatcherRule(dispatcher)

    private val store = InMemorySecureStore()
    private val pins = PinManager(store, fixedClock, iterations = 1_000)
    private val biometric = FakeBiometricAuthenticator()

    private fun securityVm() = SecurityViewModel(
        pins,
        DeviceBindingManager(store, object : DeviceIdentity { override fun fingerprint() = "fp" }, FakeOtpSender(), fixedClock, kotlin.random.Random(3)),
        FakeOtpSender(),
        object : TamperDetector { override suspend fun scan() = TamperReport.CLEAN },
        object : IntegrityChecker { override val isConfigured = false; override suspend fun requestToken(requestHash: String) = IntegrityResult.NotConfigured },
        FakeIntegrityVerifier(),
        InMemoryPaymentModeRepository(PaymentMode.SANDBOX),
        FakeUpiAppLauncher(),
        TestDispatcherProvider(dispatcher),
        biometric,
    )

    @Test
    fun aThousandRacingTurnOffTapsVerifyOnce() = runTest(dispatcher) {
        pins.set("493027")
        val vm = securityVm(); advanceUntilIdle()
        vm.startPinRemoval(); advanceUntilIdle()
        val ms = measureTimeMillis { repeat(1_000) { vm.confirmPinRemoval("000000") }; advanceUntilIdle() }
        println("STRESS [remove-pin-1000-taps] ${ms}ms remaining=${vm.uiState.value.removal!!.remainingAttempts}")
        assertThat(vm.uiState.value.removal!!.remainingAttempts).isEqualTo(PinManager.MAX_ATTEMPTS - 1)
        assertThat(pins.isSet()).isTrue()
    }

    @Test
    fun aThousandGuessesNeverRemoveTheLock() = runTest(dispatcher) {
        pins.set("493027")
        val vm = securityVm(); advanceUntilIdle()
        vm.startPinRemoval(); advanceUntilIdle()
        val ms = measureTimeMillis {
            repeat(1_000) { i ->
                vm.confirmPinRemoval(i.toString().padStart(6, '0'))
                advanceUntilIdle()
            }
        }
        val removal = vm.uiState.value.removal!!
        println("STRESS [remove-pin-1000-guesses] ${ms}ms error=${removal.error} attemptsCounted=${removal.attempts}")
        assertThat(pins.isSet()).isTrue()
        assertThat(removal.error).isEqualTo(PinRemovalError.LOCKED_OUT)
        // Only 5 guesses ever reach PBKDF2; the rest are refused by the lockout before any hashing.
        assertThat(pins.debugRecord()!!.lockoutRound).isEqualTo(1)
    }

    @Test
    fun aThousandBiometricRetriesWithFailuresKeepTheLock() = runTest(dispatcher) {
        pins.set("493027")
        biometric.result = com.darshan.payflow.data.security.BiometricResult.Failed("no match")
        val vm = securityVm(); advanceUntilIdle()
        vm.startPinRemoval(); advanceUntilIdle()
        repeat(1_000) { vm.confirmPinRemovalWithBiometric("Turn off"); advanceUntilIdle() }
        println("STRESS [remove-pin-1000-biometric-failures] prompts=${biometric.prompts}")
        assertThat(biometric.prompts).isEqualTo(1_000)
        assertThat(pins.isSet()).isTrue()
    }

    @Test
    fun aThousandThemeAndMotionTogglesSettleOnTheLastOne() = runTest(dispatcher) {
        val prefs = InMemoryUiPreferences()
        val vm = MeViewModel(SecureUpiProfileRepository(store), pins, prefs, TestDispatcherProvider(dispatcher)); advanceUntilIdle()
        val ms = measureTimeMillis {
            repeat(1_000) { i ->
                vm.setTheme(ThemeMode.entries[i % 3]); vm.setReduceMotion(i % 2 == 0); vm.setHaptics(i % 2 == 1); vm.setSound(i % 2 == 0)
            }
            advanceUntilIdle()
        }
        println("STRESS [me-4000-toggles] ${ms}ms final=${prefs.settings.value}")
        assertThat(prefs.settings.value.theme).isEqualTo(ThemeMode.entries[999 % 3])
        assertThat(prefs.settings.value.reduceMotion).isFalse()
        assertThat(vm.uiState.value.settings).isEqualTo(prefs.settings.value)
    }

    @Test
    fun aThousandBuyTapsOpenOneOrder() {
        val vm = CheckoutViewModel(RecordCheckoutResultUseCase(FakePaymentRepository(), fixedClock, { "ck-1" }) { "CKREF" }, TestDispatcherProvider(dispatcher), androidx.lifecycle.SavedStateHandle())
        val products = vm.uiState.value.products
        val orders = (0 until 1_000).map { vm.startOrder(products[it % products.size]).orderId }.toSet()
        println("STRESS [checkout-1000-buy-taps] distinctOrders=${orders.size}")
        assertThat(orders).hasSize(1)
    }

    @Test
    fun aMillionRevealStepsAndChecksAreCheap() {
        val states = listOf(
            SecurityUiState(),
            SecurityUiState(loading = false, pinConfigured = true, binding = BindingStatus.Bound("fp", "+91 ****3210"), tamper = TamperReport.CLEAN,
                integrity = IntegrityStatus.Result(IntegrityVerdict.MEETS_DEVICE_INTEGRITY, true), biometric = BiometricAvailability.AVAILABLE),
        )
        var sink = 0
        val checksMs = measureTimeMillis {
            repeat(1_000_000) { i -> sink += SecurityChecks.summary(SecurityChecks.of(states[i % 2])).hashCode() and 1 }
        }
        val reveal = CheckReveal()
        val resolved = List(5) { true }
        val revealMs = measureTimeMillis { repeat(1_000_000) { i -> reveal.advance(i.toLong(), resolved) } }
        println("STRESS [security-checks-1M] ${checksMs}ms reveal-1M=${revealMs}ms sink=$sink")
        assertThat(reveal.revealed).isEqualTo(5)
        assertThat(checksMs).isLessThan(10_000)
    }

}
