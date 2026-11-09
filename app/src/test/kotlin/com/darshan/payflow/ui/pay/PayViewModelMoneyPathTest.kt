package com.darshan.payflow.ui.pay

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.darshan.payflow.data.psp.FakeOutcome
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.psp.FakePspConfig
import com.darshan.payflow.data.settings.InMemoryPaymentModeRepository
import com.darshan.payflow.data.upi.UpiLaunchResult
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.usecase.ConfirmPaymentUseCase
import com.darshan.payflow.domain.usecase.MarkPaymentManuallyUseCase
import com.darshan.payflow.domain.usecase.PayViaUpiIntentUseCase
import com.darshan.payflow.domain.usecase.RecordUpiAppResultUseCase
import com.darshan.payflow.testutil.FakePaymentRepository
import com.darshan.payflow.testutil.FakeUpiAppLauncher
import com.darshan.payflow.testutil.MainDispatcherRule
import com.darshan.payflow.testutil.TestDispatcherProvider
import com.darshan.payflow.testutil.fixedClock
import com.darshan.payflow.ui.designsystem.effects.HapticEvent
import com.darshan.payflow.ui.designsystem.effects.UiEffect
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The rules the new Pay screen leans on: one payment per intent, honest outcomes, and nothing lost on a keypad. */
@OptIn(ExperimentalCoroutinesApi::class)
class PayViewModelMoneyPathTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val mainRule = MainDispatcherRule(dispatcher)
    private val repo = FakePaymentRepository()
    private val launcher = FakeUpiAppLauncher()
    private val modes = InMemoryPaymentModeRepository(PaymentMode.SANDBOX)
    private var ids = 0

    private fun vm(psp: FakePsp = FakePsp(fixedClock), handle: SavedStateHandle = SavedStateHandle()) = PayViewModel(
        payViaUpiIntent = PayViaUpiIntentUseCase(repo, psp, fixedClock) { "pay-${++ids}" },
        confirmPayment = ConfirmPaymentUseCase(repo, psp, fixedClock),
        recordUpiAppResult = RecordUpiAppResultUseCase(repo),
        markPaymentManually = MarkPaymentManuallyUseCase(repo),
        verifyVpa = com.darshan.payflow.domain.usecase.VerifyVpaUseCase(psp),
        pollPaymentStatus = com.darshan.payflow.domain.usecase.PollPaymentStatusUseCase(
            ConfirmPaymentUseCase(repo, psp, fixedClock),
            com.darshan.payflow.core.BackoffPolicy(initialDelayMillis = 1_000, maxDelayMillis = 4_000, multiplier = 2.0, jitterFactor = 0.0, maxAttempts = 3),
            kotlin.random.Random(1),
        ),
        paymentGuard = com.darshan.payflow.data.security.AllowAllPaymentGuard(),
        paymentModes = modes,
        upiAppLauncher = launcher,
        dispatchers = TestDispatcherProvider(dispatcher),
        savedState = handle,
    )

    private fun PayViewModel.fill() {
        onVpaChange("alice@okbank"); onAmountChange("100")
    }

    @Test
    fun `a thousand swipes before the first answer create one payment and one launch`() = runTest(dispatcher) {
        val psp = FakePsp(fixedClock)
        val vm = vm(psp)
        vm.fill(); advanceUntilIdle()
        vm.events.test {
            repeat(1_000) { vm.submit() }
            advanceUntilIdle()
            assertThat(awaitItem()).isInstanceOf(PayEvent.LaunchUpiApp::class.java)
            repeat(1_000) { vm.submit() }
            advanceUntilIdle()
            expectNoEvents()
        }
        assertThat(ids).isEqualTo(1)
        assertThat(psp.initiateCalls).isEqualTo(1)
    }

    @Test
    fun `a thousand taps on one app in the sheet create one payment`() = runTest(dispatcher) {
        modes.set(PaymentMode.UPI_APPS)
        val vm = vm()
        advanceUntilIdle()
        vm.fill()
        vm.submit(); advanceUntilIdle()
        val phase = vm.uiState.value.phase as PayPhase.ChoosingApp
        val pkg = phase.apps.first().packageName
        vm.events.test {
            repeat(1_000) { vm.onAppChosen(pkg) }
            assertThat((vm.uiState.value.phase as PayPhase.ChoosingApp).picked).isEqualTo(pkg)
            advanceUntilIdle()
            assertThat(awaitItem()).isInstanceOf(PayEvent.LaunchUpiApp::class.java)
            expectNoEvents()
        }
        assertThat(ids).isEqualTo(1)
    }

    @Test
    fun `the sheet can't be dismissed once an app is picked`() = runTest(dispatcher) {
        modes.set(PaymentMode.UPI_APPS)
        val vm = vm()
        advanceUntilIdle()
        vm.fill()
        vm.submit(); advanceUntilIdle()
        val pkg = (vm.uiState.value.phase as PayPhase.ChoosingApp).apps.first().packageName
        vm.onAppChosen(pkg)
        vm.dismissChooser()
        assertThat(vm.uiState.value.phase).isInstanceOf(PayPhase.ChoosingApp::class.java)
        assertThat(vm.uiState.value.isBusy).isTrue()
        advanceUntilIdle()
        assertThat(vm.uiState.value.phase).isInstanceOf(PayPhase.AwaitingApp::class.java)
    }

    @Test
    fun `process death during a payment restores the attempt, and the slider's next try cannot pay twice`() = runTest(dispatcher) {
        val psp = FakePsp(fixedClock)
        val handle = SavedStateHandle()
        val first = vm(psp, handle)
        first.fill(); advanceUntilIdle()
        first.submit(); advanceUntilIdle()
        val paymentId = (first.uiState.value.phase as PayPhase.AwaitingApp).paymentId
        assertThat(handle.get<String>(PayViewModel.KEY_IN_FLIGHT)).isEqualTo(paymentId)

        val second = vm(psp, handle)
        advanceUntilIdle()
        assertThat(second.uiState.value.phase).isEqualTo(PayPhase.AwaitingApp(paymentId))
        repeat(100) { second.submit() }
        advanceUntilIdle()
        assertThat(ids).isEqualTo(1)
        assertThat(psp.initiateCalls).isEqualTo(1)
    }

    @Test
    fun `a typed payee restored after process death is checked again, so the slider can't wait forever`() = runTest(dispatcher) {
        val psp = FakePsp(fixedClock)
        val vm = vm(psp, SavedStateHandle(mapOf(PayViewModel.KEY_VPA to "alice@okbank", PayViewModel.KEY_AMOUNT to "10")))
        advanceUntilIdle()
        assertThat(vm.uiState.value.verification).isEqualTo(VpaVerificationState.Verified("Alice Kumar"))
        assertThat(PayReadiness.blockOf(vm.uiState.value)).isNull()
    }

    @Test
    fun `keypad presses inside one frame all land, and nothing passes the limit`() = runTest(dispatcher) {
        val vm = vm()
        // No suspension between presses: this is what a screen holding a stale copy would lose.
        for (key in listOf(AmountKey.D1, AmountKey.D0, AmountKey.D0)) vm.onAmountKey(key)
        assertThat(vm.uiState.value.amountText).isEqualTo("100")
        repeat(500) { vm.onAmountKey(AmountKey.D9) }
        assertThat(vm.uiState.value.amountText).isEqualTo("10099")
        assertThat(vm.uiState.value.errors.amount).isEqualTo(PayFieldError.AMOUNT_TOO_LARGE)
        assertThat(vm.uiState.value.amountNudges).isGreaterThan(0)
        repeat(500) { vm.onAmountKey(AmountKey.Backspace) }
        assertThat(vm.uiState.value.amountText).isEqualTo("")
    }

    @Test
    fun `a keypad press is saved at once, survives process death, and clears the old error`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val vm = vm(handle = handle)
        vm.onAmountChange("100000")
        vm.onAmountKey(AmountKey.D5)
        assertThat(vm.uiState.value.errors.amount).isEqualTo(PayFieldError.AMOUNT_TOO_LARGE)
        vm.onAmountKey(AmountKey.Backspace)
        assertThat(vm.uiState.value.errors.amount).isNull()
        assertThat(handle.get<String>(PayViewModel.KEY_AMOUNT)).isEqualTo("10000")
    }

    @Test
    fun `the keypad does nothing on a locked amount or during a payment`() = runTest(dispatcher) {
        val locked = vm(handle = SavedStateHandle(mapOf(PayViewModel.ARG_PREFILL_VPA to "shop@okbank", PayViewModel.ARG_PREFILL_AMOUNT to 4_500L, PayViewModel.ARG_SOURCE to "qr")))
        advanceUntilIdle()
        locked.onAmountKey(AmountKey.D9)
        assertThat(locked.uiState.value.amountPaise).isEqualTo(4_500)

        val vm = vm()
        vm.fill(); advanceUntilIdle()
        vm.submit(); advanceUntilIdle()
        vm.onAmountKey(AmountKey.D9)
        assertThat(vm.uiState.value.amountText).isEqualTo("100")
    }

    @Test
    fun `a trailing dot pays the whole rupees, exactly`() = runTest(dispatcher) {
        val vm = vm()
        vm.onVpaChange("alice@okbank"); advanceUntilIdle()
        vm.onAmountKey(AmountKey.D5); vm.onAmountKey(AmountKey.Dot)
        vm.submit(); advanceUntilIdle()
        assertThat(repo.getById("pay-1")!!.amountPaise).isEqualTo(500)
    }

    @Test
    fun `a refused attempt tells the slider to go home`() = runTest(dispatcher) {
        val vm = vm()
        vm.onAmountChange("100")
        vm.submit(); advanceUntilIdle()
        assertThat(vm.uiState.value.phase).isEqualTo(PayPhase.Idle)
        assertThat(vm.uiState.value.attemptsRefused).isEqualTo(1)

        modes.set(PaymentMode.UPI_APPS)
        val real = vm()
        advanceUntilIdle()
        real.fill()
        real.submit(); advanceUntilIdle()
        real.dismissChooser()
        assertThat(real.uiState.value.attemptsRefused).isEqualTo(1)
    }

    @Test
    fun `a provider success plays confirm once`() = runTest(dispatcher) {
        val vm = vm()
        vm.fill(); advanceUntilIdle()
        vm.submit(); advanceUntilIdle()
        vm.uiEffects.test {
            vm.onUpiAppResult(UpiLaunchResult.Responded("Status=SUCCESS"))
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(UiEffect.Haptic(HapticEvent.Confirm))
            // The chime is asked for once too; the sound setting decides whether it plays.
            assertThat(awaitItem()).isEqualTo(UiEffect.Sound(com.darshan.payflow.ui.designsystem.effects.UiSoundEvent.Success))
            expectNoEvents()
        }
    }

    @Test
    fun `a spoofed success with a failing provider rejects and never confirms`() = runTest(dispatcher) {
        val vm = vm(FakePsp(fixedClock, FakePspConfig(outcome = FakeOutcome.FAILED)))
        vm.fill(); advanceUntilIdle()
        vm.submit(); advanceUntilIdle()
        vm.uiEffects.test {
            vm.onUpiAppResult(UpiLaunchResult.Responded("Status=SUCCESS"))
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(UiEffect.Haptic(HapticEvent.Reject))
            expectNoEvents()
        }
    }

    @Test
    fun `pending has no haptic until the poll settles it, then exactly one`() = runTest(dispatcher) {
        val vm = vm(FakePsp(fixedClock, FakePspConfig(pendingPolls = 2)))
        vm.fill(); advanceUntilIdle()
        vm.submit(); advanceUntilIdle()
        vm.uiEffects.test {
            vm.onUpiAppResult(UpiLaunchResult.Cancelled)
            runCurrent()
            assertThat(vm.uiState.value.polling).isTrue()
            expectNoEvents()
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(UiEffect.Haptic(HapticEvent.Confirm))
            assertThat(awaitItem()).isEqualTo(UiEffect.Sound(com.darshan.payflow.ui.designsystem.effects.UiSoundEvent.Success))
            expectNoEvents()
        }
    }

    @Test
    fun `an order refunded at the provider is shown refunded, not successful, and doesn't celebrate`() = runTest(dispatcher) {
        val psp = FakePsp(fixedClock)
        val vm = vm(psp)
        vm.fill(); advanceUntilIdle()
        vm.submit(); advanceUntilIdle()
        val txnRef = repo.getById("pay-1")!!.txnRef
        psp.getPaymentStatus(txnRef)
        psp.refund(txnRef, 10_000, "refund-1")
        vm.onUpiAppResult(UpiLaunchResult.Cancelled); advanceUntilIdle()
        val done = vm.uiState.value.phase as PayPhase.Done
        assertThat(done.status).isEqualTo(PaymentStatus.REFUNDED)
        assertThat(done.payment!!.status).isEqualTo(PaymentStatus.REFUNDED)
        assertThat(PayOutcomes.of(done).celebration).isEqualTo(Celebration.NONE)
    }

    @Test
    fun `pay again stops the old poller, so its answer can't land on the new attempt`() = runTest(dispatcher) {
        val vm = vm(FakePsp(fixedClock, FakePspConfig(outcome = FakeOutcome.PENDING_UNTIL_SETTLED)))
        vm.fill(); advanceUntilIdle()
        vm.submit(); advanceUntilIdle()
        vm.onUpiAppResult(UpiLaunchResult.Cancelled); runCurrent()
        assertThat(vm.uiState.value.polling).isTrue()
        vm.payAgain()
        assertThat(vm.uiState.value.phase).isEqualTo(PayPhase.Idle)
        advanceTimeBy(60_000); runCurrent()
        assertThat(vm.uiState.value.phase).isEqualTo(PayPhase.Idle)
        assertThat(vm.uiState.value.polling).isFalse()
    }
}
