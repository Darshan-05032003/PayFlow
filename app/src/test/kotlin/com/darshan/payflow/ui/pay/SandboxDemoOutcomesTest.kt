package com.darshan.payflow.ui.pay

import androidx.lifecycle.SavedStateHandle
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.psp.FakePspConfig
import com.darshan.payflow.data.settings.InMemoryPaymentModeRepository
import com.darshan.payflow.data.upi.UpiLaunchResult
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.SandboxDemoPayees
import com.darshan.payflow.domain.usecase.ConfirmPaymentUseCase
import com.darshan.payflow.domain.usecase.MarkPaymentManuallyUseCase
import com.darshan.payflow.domain.usecase.PayViaUpiIntentUseCase
import com.darshan.payflow.domain.usecase.RecordUpiAppResultUseCase
import com.darshan.payflow.testutil.FakePaymentRepository
import com.darshan.payflow.testutil.FakeUpiAppLauncher
import com.darshan.payflow.testutil.MainDispatcherRule
import com.darshan.payflow.testutil.TestDispatcherProvider
import com.darshan.payflow.testutil.fixedClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Every outcome can be reached in the app, from the sandbox's own payees. */
@OptIn(ExperimentalCoroutinesApi::class)
class SandboxDemoOutcomesTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val mainRule = MainDispatcherRule(dispatcher)
    private val repo = FakePaymentRepository()
    private val psp = FakePsp(fixedClock, FakePspConfig(pendingPolls = 1, demoPayees = true))

    private fun vm() = PayViewModel(
        payViaUpiIntent = PayViaUpiIntentUseCase(repo, psp, fixedClock),
        confirmPayment = ConfirmPaymentUseCase(repo, psp, fixedClock),
        recordUpiAppResult = RecordUpiAppResultUseCase(repo),
        markPaymentManually = MarkPaymentManuallyUseCase(repo),
        verifyVpa = com.darshan.payflow.domain.usecase.VerifyVpaUseCase(psp),
        pollPaymentStatus = com.darshan.payflow.domain.usecase.PollPaymentStatusUseCase(
            ConfirmPaymentUseCase(repo, psp, fixedClock),
            com.darshan.payflow.core.BackoffPolicy(initialDelayMillis = 1_000, maxDelayMillis = 4_000, multiplier = 2.0, jitterFactor = 0.0, maxAttempts = 8),
            kotlin.random.Random(1),
        ),
        paymentGuard = com.darshan.payflow.data.security.AllowAllPaymentGuard(),
        paymentModes = InMemoryPaymentModeRepository(PaymentMode.SANDBOX),
        upiAppLauncher = FakeUpiAppLauncher(),
        dispatchers = TestDispatcherProvider(dispatcher),
        savedState = SavedStateHandle(),
    )

    private suspend fun kotlinx.coroutines.test.TestScope.pay(vpa: String): PayViewModel {
        val vm = vm()
        vm.onVpaChange(vpa); vm.onAmountChange("100"); advanceUntilIdle()
        assertThat(PayReadiness.blockOf(vm.uiState.value)).isNull()
        vm.submit(); advanceUntilIdle()
        vm.onUpiAppResult(UpiLaunchResult.Responded("Status=SUCCESS"))
        return vm
    }

    @Test
    fun `success celebrates`() = runTest(dispatcher) {
        val vm = pay(SandboxDemoPayees.SUCCESS); advanceUntilIdle()
        val done = vm.uiState.value.phase as PayPhase.Done
        assertThat(done.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(PayOutcomes.of(done).celebration).isEqualTo(Celebration.FULL)
    }

    @Test
    fun `a slow bank is pending while it polls, then settles`() = runTest(dispatcher) {
        val vm = pay(SandboxDemoPayees.SLOW); runCurrent()
        assertThat((vm.uiState.value.phase as PayPhase.Done).status).isEqualTo(PaymentStatus.PENDING)
        assertThat(vm.uiState.value.polling).isTrue()
        advanceUntilIdle()
        assertThat((vm.uiState.value.phase as PayPhase.Done).status).isEqualTo(PaymentStatus.SUCCESS)
    }

    @Test
    fun `stuck stays pending and does not celebrate`() = runTest(dispatcher) {
        val vm = pay(SandboxDemoPayees.STUCK); advanceUntilIdle()
        val done = vm.uiState.value.phase as PayPhase.Done
        assertThat(done.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(PayOutcomes.of(done).celebration).isEqualTo(Celebration.NONE)
    }

    @Test
    fun `declined fails even though the upi app said success`() = runTest(dispatcher) {
        val vm = pay(SandboxDemoPayees.DECLINED); advanceUntilIdle()
        assertThat((vm.uiState.value.phase as PayPhase.Done).status).isEqualTo(PaymentStatus.FAILED)
    }

    @Test
    fun `refunded reaches the refund outcome`() = runTest(dispatcher) {
        val vm = pay(SandboxDemoPayees.REFUNDED); advanceUntilIdle()
        val done = vm.uiState.value.phase as PayPhase.Done
        assertThat(done.status).isEqualTo(PaymentStatus.REFUNDED)
        assertThat(PayOutcomes.of(done).kind).isEqualTo(OutcomeKind.REFUND)
    }

    @Test
    fun `a wrong answer stays unresolved and the record stays pending`() = runTest(dispatcher) {
        val vm = pay(SandboxDemoPayees.MISMATCH); advanceUntilIdle()
        assertThat(vm.uiState.value.phase).isInstanceOf(PayPhase.Unresolved::class.java)
        assertThat((vm.uiState.value.phase as PayPhase.Unresolved).mismatch).isTrue()
        val stored = repo.getById((vm.uiState.value.phase as PayPhase.Unresolved).paymentId)!!
        assertThat(stored.status).isEqualTo(PaymentStatus.PENDING)
    }

    @Test
    fun `without the sandbox flag a magic payee is just a payee`() = runTest(dispatcher) {
        val plain = FakePsp(fixedClock)
        plain.initiatePayment(com.darshan.payflow.data.psp.PspInitiateRequest("TR1", 100, SandboxDemoPayees.DECLINED, "x", "", null))
        assertThat(plain.orders["TR1"]!!.finalOutcome).isEqualTo(com.darshan.payflow.data.psp.FakeOutcome.SUCCESS)
    }
}
