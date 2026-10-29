package com.darshan.payflow.ui.pay

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.darshan.payflow.data.psp.FakeOutcome
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.psp.FakePspConfig
import com.darshan.payflow.data.upi.UpiLaunchResult
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.usecase.ConfirmPaymentUseCase
import com.darshan.payflow.domain.usecase.PayViaUpiIntentUseCase
import com.darshan.payflow.testutil.FakePaymentRepository
import com.darshan.payflow.testutil.FakeUpiAppLauncher
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

@OptIn(ExperimentalCoroutinesApi::class)
class PayViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val mainRule = MainDispatcherRule(dispatcher)
    private val repo = FakePaymentRepository()
    private val launcher = FakeUpiAppLauncher()
    private var ids = 0

    private fun vm(psp: FakePsp = FakePsp(fixedClock), handle: SavedStateHandle = SavedStateHandle()) = PayViewModel(
        payViaUpiIntent = PayViaUpiIntentUseCase(repo, psp, fixedClock) { "pay-${++ids}" },
        confirmPayment = ConfirmPaymentUseCase(repo, psp, fixedClock),
        upiAppLauncher = launcher,
        dispatchers = TestDispatcherProvider(dispatcher),
        savedState = handle,
    )

    private fun PayViewModel.fillValidForm() {
        onVpaChange("alice@okbank"); onNameChange("Alice"); onAmountChange("123.45"); onNoteChange("Lunch")
    }

    @Test
    fun `form validation blocks submit and surfaces field errors`() = runTest(dispatcher) {
        val vm = vm()
        vm.submit()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertThat(s.errors.vpa).isNotNull()
        assertThat(s.errors.amount).isNotNull()
        assertThat(s.phase).isEqualTo(PayPhase.Idle)
        vm.onVpaChange("alice"); vm.onAmountChange("0")
        vm.submit(); advanceUntilIdle()
        assertThat(vm.uiState.value.errors.vpa).isEqualTo(PayFieldError.INVALID_VPA)
        assertThat(vm.uiState.value.errors.amount).isEqualTo(PayFieldError.INVALID_AMOUNT)
        vm.onAmountChange("100001")
        vm.submit(); advanceUntilIdle()
        assertThat(vm.uiState.value.errors.amount).isEqualTo(PayFieldError.AMOUNT_TOO_LARGE)
        assertThat(repo.getById("pay-1")).isNull()
    }

    @Test
    fun `valid submit creates a pending payment and emits a launch event`() = runTest(dispatcher) {
        val vm = vm()
        vm.fillValidForm()
        vm.events.test {
            vm.submit()
            val event = awaitItem() as PayEvent.LaunchUpiApp
            assertThat(event.upiUri).contains("am=123.45")
            assertThat(event.upiUri).contains("pa=alice%40okbank")
        }
        assertThat(vm.uiState.value.phase).isEqualTo(PayPhase.AwaitingApp("pay-1"))
        assertThat(repo.getById("pay-1")!!.status).isEqualTo(PaymentStatus.PENDING)
    }

    @Test
    fun `rapid double tap creates exactly one payment`() = runTest(dispatcher) {
        val vm = vm()
        vm.fillValidForm()
        vm.submit(); vm.submit(); vm.submit()
        advanceUntilIdle()
        assertThat(repo.getById("pay-1")).isNotNull()
        assertThat(repo.getById("pay-2")).isNull()
    }

    @Test
    fun `no upi app installed is reported and nothing is created`() = runTest(dispatcher) {
        launcher.installed = false
        val vm = vm()
        vm.fillValidForm()
        vm.events.test {
            vm.submit()
            assertThat(awaitItem()).isEqualTo(PayEvent.NoUpiApp)
        }
        assertThat(repo.getById("pay-1")).isNull()
        assertThat(vm.uiState.value.phase).isEqualTo(PayPhase.Idle)
    }

    @Test
    fun `app result is confirmed through the psp and shown as done`() = runTest(dispatcher) {
        val vm = vm()
        vm.fillValidForm(); vm.submit(); advanceUntilIdle()
        vm.onUpiAppResult(UpiLaunchResult.Responded("txnId=A1&Status=SUCCESS&txnRef=${repo.getById("pay-1")!!.txnRef}"))
        advanceUntilIdle()
        val phase = vm.uiState.value.phase as PayPhase.Done
        assertThat(phase.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(phase.paymentId).isEqualTo("pay-1")
    }

    @Test
    fun `spoofed app success with a failing psp shows failed`() = runTest(dispatcher) {
        val vm = vm(FakePsp(fixedClock, FakePspConfig(outcome = FakeOutcome.FAILED)))
        vm.fillValidForm(); vm.submit(); advanceUntilIdle()
        vm.onUpiAppResult(UpiLaunchResult.Responded("Status=SUCCESS&txnRef=${repo.getById("pay-1")!!.txnRef}&ApprovalRefNo=1"))
        advanceUntilIdle()
        assertThat((vm.uiState.value.phase as PayPhase.Done).status).isEqualTo(PaymentStatus.FAILED)
    }

    @Test
    fun `cancelled app result still asks the psp, pending stays pending and check status can resolve it`() = runTest(dispatcher) {
        val psp = FakePsp(fixedClock, FakePspConfig(pendingPolls = 1))
        val vm = vm(psp)
        vm.fillValidForm(); vm.submit(); advanceUntilIdle()
        vm.onUpiAppResult(UpiLaunchResult.Cancelled); advanceUntilIdle()
        assertThat((vm.uiState.value.phase as PayPhase.Done).status).isEqualTo(PaymentStatus.PENDING)
        vm.checkStatus(); advanceUntilIdle()
        assertThat((vm.uiState.value.phase as PayPhase.Done).status).isEqualTo(PaymentStatus.SUCCESS)
    }

    @Test
    fun `in flight payment survives process death and the late app result is still confirmed`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val psp = FakePsp(fixedClock)
        val vm1 = vm(psp, handle)
        vm1.fillValidForm(); vm1.submit(); advanceUntilIdle()
        assertThat(handle.get<String>(PayViewModel.KEY_IN_FLIGHT)).isEqualTo("pay-1")

        val vm2 = vm(psp, handle)
        assertThat(vm2.uiState.value.phase).isEqualTo(PayPhase.AwaitingApp("pay-1"))
        assertThat(vm2.uiState.value.vpa).isEqualTo("alice@okbank")
        vm2.onUpiAppResult(UpiLaunchResult.Responded("Status=SUCCESS&txnRef=${repo.getById("pay-1")!!.txnRef}")); advanceUntilIdle()
        assertThat((vm2.uiState.value.phase as PayPhase.Done).status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(handle.get<String>(PayViewModel.KEY_IN_FLIGHT)).isNull()
    }

    @Test
    fun `unexpected app result while idle is ignored`() = runTest(dispatcher) {
        val vm = vm()
        vm.onUpiAppResult(UpiLaunchResult.Responded("Status=SUCCESS&txnRef=X")); advanceUntilIdle()
        assertThat(vm.uiState.value.phase).isEqualTo(PayPhase.Idle)
    }

    @Test
    fun `reset returns to an empty form`() = runTest(dispatcher) {
        val vm = vm()
        vm.fillValidForm(); vm.submit(); advanceUntilIdle()
        vm.onUpiAppResult(UpiLaunchResult.Cancelled); advanceUntilIdle()
        vm.reset()
        assertThat(vm.uiState.value).isEqualTo(PayUiState())
    }

    @Test
    fun `amount text accepts only sane input`() = runTest(dispatcher) {
        val vm = vm()
        vm.onAmountChange("12.345")
        assertThat(vm.uiState.value.amountText).isEqualTo("")
        vm.onAmountChange("abc")
        assertThat(vm.uiState.value.amountText).isEqualTo("")
        vm.onAmountChange("12.34")
        assertThat(vm.uiState.value.amountText).isEqualTo("12.34")
        vm.onAmountChange("1234567890123")
        assertThat(vm.uiState.value.amountText).isEqualTo("12.34")
    }

    @Test
    fun `qr route arguments prefill the form, lock the payee and record the QR method`() = runTest(dispatcher) {
        val handle = SavedStateHandle(
            mapOf(
                PayViewModel.ARG_PREFILL_VPA to "shop@okbank", PayViewModel.ARG_PREFILL_NAME to "Chai Point",
                PayViewModel.ARG_PREFILL_AMOUNT to 4_500L, PayViewModel.ARG_PREFILL_NOTE to "Table 4", PayViewModel.ARG_SOURCE to "qr",
            ),
        )
        val vm = vm(handle = handle)
        val s = vm.uiState.value
        assertThat(s.vpa).isEqualTo("shop@okbank")
        assertThat(s.name).isEqualTo("Chai Point")
        assertThat(s.amountText).isEqualTo("45.00")
        assertThat(s.note).isEqualTo("Table 4")
        assertThat(s.payeeLocked).isTrue()
        vm.onVpaChange("mallory@evil") // locked: ignored
        assertThat(vm.uiState.value.vpa).isEqualTo("shop@okbank")
        vm.submit(); advanceUntilIdle()
        assertThat(repo.getById("pay-1")!!.method).isEqualTo(com.darshan.payflow.domain.model.PaymentMethod.QR_SCAN)
        assertThat(repo.getById("pay-1")!!.amountPaise).isEqualTo(4_500)
    }

    @Test
    fun `qr without amount leaves the amount editable`() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf(PayViewModel.ARG_PREFILL_VPA to "shop@okbank", PayViewModel.ARG_SOURCE to "qr"))
        val vm = vm(handle = handle)
        assertThat(vm.uiState.value.amountText).isEmpty()
        assertThat(vm.uiState.value.amountLocked).isFalse()
        vm.onAmountChange("10"); vm.onNameChange("x")
        assertThat(vm.uiState.value.amountText).isEqualTo("10")
        assertThat(vm.uiState.value.name).isEqualTo("shop@okbank") // name locked with the payee
    }
}
