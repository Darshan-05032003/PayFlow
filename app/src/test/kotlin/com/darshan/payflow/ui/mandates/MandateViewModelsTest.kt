package com.darshan.payflow.ui.mandates

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.psp.FakePspConfig
import com.darshan.payflow.domain.model.MandateAction
import com.darshan.payflow.domain.model.MandateFrequency
import com.darshan.payflow.domain.model.MandateStatus
import com.darshan.payflow.domain.model.MandateValidation
import com.darshan.payflow.domain.usecase.CreateMandateRequest
import com.darshan.payflow.domain.usecase.CreateMandateUseCase
import com.darshan.payflow.domain.usecase.ExecuteMandateUseCase
import com.darshan.payflow.domain.usecase.ExecuteMandateResult
import com.darshan.payflow.domain.usecase.UpdateMandateUseCase
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.FakeMandateRepository
import com.darshan.payflow.testutil.FakePaymentRepository
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
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class MandateViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val mainRule = MainDispatcherRule(dispatcher)
    private val mandates = FakeMandateRepository()
    private val payments = FakePaymentRepository()
    private val psp = FakePsp(fixedClock)
    private val today: LocalDate = FIXED_INSTANT.atZone(ZoneOffset.UTC).toLocalDate()
    private val dispatchers = TestDispatcherProvider(dispatcher)

    private fun createUseCase(psp: FakePsp = this.psp) = CreateMandateUseCase(mandates, psp, fixedClock, ZoneOffset.UTC) { "m-1" }
    private suspend fun seedMandate() = createUseCase().invoke(CreateMandateRequest("netflix@okbank", "Netflix", 64_900, MandateFrequency.MONTHLY, today, today.plusYears(1), ""))

    // ---------- list ----------

    @Test
    fun `list goes loading, empty, then content and derives expiry`() = runTest(dispatcher) {
        val vm = MandatesViewModel(mandates, fixedClock, ZoneOffset.UTC, dispatchers)
        vm.uiState.test {
            assertThat(awaitItem()).isEqualTo(MandatesUiState.Loading)
            assertThat(awaitItem()).isEqualTo(MandatesUiState.Empty)
            seedMandate()
            val content = awaitItem() as MandatesUiState.Content
            assertThat(content.mandates.single().status).isEqualTo(MandateStatus.ACTIVE)
        }
    }

    @Test
    fun `list error and retry`() = runTest(dispatcher) {
        mandates.failure = IOException("db")
        val vm = MandatesViewModel(mandates, fixedClock, ZoneOffset.UTC, dispatchers)
        vm.uiState.test {
            awaitItem()
            assertThat(awaitItem()).isInstanceOf(MandatesUiState.Error::class.java)
            mandates.failure = null
            vm.retry()
            assertThat(awaitItem()).isEqualTo(MandatesUiState.Loading)
            assertThat(awaitItem()).isEqualTo(MandatesUiState.Empty)
        }
    }

    // ---------- create ----------

    private fun createVm(psp: FakePsp = this.psp, handle: SavedStateHandle = SavedStateHandle()) =
        CreateMandateViewModel(createUseCase(psp), fixedClock, ZoneOffset.UTC, dispatchers, handle)

    @Test
    fun `create form validates and emits created on success`() = runTest(dispatcher) {
        val vm = createVm()
        vm.submit(); advanceUntilIdle()
        assertThat(vm.uiState.value.errors.vpa).isNotNull()
        assertThat(vm.uiState.value.errors.amount).isNotNull()
        vm.onVpaChange("netflix@okbank"); vm.onNameChange("Netflix"); vm.onAmountChange("649"); vm.onFrequencyChange(MandateFrequency.MONTHLY); vm.onValidityMonthsChange(12)
        vm.events.test {
            vm.submit()
            val created = awaitItem() as CreateMandateEvent.Created
            assertThat(created.mandateId).isEqualTo("m-1")
        }
        assertThat(mandates.getById("m-1")!!.endDate).isEqualTo(today.plusMonths(12))
        assertThat(mandates.getById("m-1")!!.maxAmountPaise).isEqualTo(64_900)
    }

    @Test
    fun `create form shows rule violations from the use case and survives process death`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val vm = createVm(handle = handle)
        vm.onVpaChange("unknown@okbank"); vm.onNameChange("X"); vm.onAmountChange("10")
        vm.submit(); advanceUntilIdle()
        assertThat(vm.uiState.value.ruleError).isEqualTo(MandateValidation.InvalidPayee)
        val restored = createVm(handle = handle)
        assertThat(restored.uiState.value.vpa).isEqualTo("unknown@okbank")
        assertThat(restored.uiState.value.amountText).isEqualTo("10")
    }

    @Test
    fun `create double tap submits once and psp outage is reported`() = runTest(dispatcher) {
        val down = FakePsp(fixedClock, FakePspConfig(serverErrorRate = 1.0))
        val vm = createVm(down)
        vm.onVpaChange("netflix@okbank"); vm.onNameChange("Netflix"); vm.onAmountChange("649")
        vm.events.test {
            vm.submit(); vm.submit()
            assertThat(awaitItem()).isEqualTo(CreateMandateEvent.Unavailable)
            expectNoEvents()
        }
        assertThat(vm.uiState.value.submitting).isFalse()
    }

    // ---------- detail ----------

    private fun detailVm(id: String = "m-1", psp: FakePsp = this.psp) = MandateDetailViewModel(
        mandates, payments, UpdateMandateUseCase(mandates, psp, fixedClock),
        ExecuteMandateUseCase(mandates, payments, psp, fixedClock, ZoneOffset.UTC) { "pay-m1" },
        fixedClock, ZoneOffset.UTC, dispatchers, SavedStateHandle(mapOf(MandateDetailViewModel.ARG_ID to id)),
    )

    @Test
    fun `detail shows the mandate and its executions, pause and resume update it`() = runTest(dispatcher) {
        seedMandate()
        val vm = detailVm()
        vm.uiState.test {
            assertThat(awaitItem()).isEqualTo(MandateDetailUiState.Loading)
            val content = awaitItem() as MandateDetailUiState.Content
            assertThat(content.mandate.status).isEqualTo(MandateStatus.ACTIVE)
            assertThat(content.executions).isEmpty()
            vm.act(MandateAction.PAUSE)
            skipItems(1) // busy
            assertThat((awaitItem() as MandateDetailUiState.Content).mandate.status).isEqualTo(MandateStatus.PAUSED)
            cancelAndIgnoreRemainingEvents() // the trailing busy=false emission
        }
    }

    @Test
    fun `simulate debit runs the rules and lists the execution`() = runTest(dispatcher) {
        seedMandate()
        val vm = detailVm()
        vm.uiState.test { awaitItem(); awaitItem() }
        vm.events.test {
            vm.simulateDebit("649")
            assertThat((awaitItem() as MandateDetailEvent.ExecutionResult).result).isInstanceOf(ExecuteMandateResult.Executed::class.java)
            vm.simulateDebit("649")
            assertThat(((awaitItem() as MandateDetailEvent.ExecutionResult).result as ExecuteMandateResult.Rejected).check)
                .isEqualTo(com.darshan.payflow.domain.model.ExecutionCheck.AlreadyExecutedThisPeriod)
            vm.simulateDebit("abc")
            assertThat(((awaitItem() as MandateDetailEvent.ExecutionResult).result as ExecuteMandateResult.Rejected).check)
                .isEqualTo(com.darshan.payflow.domain.model.ExecutionCheck.InvalidAmount)
        }
        advanceUntilIdle()
        val content = vm.uiState.value as MandateDetailUiState.Content
        assertThat(content.executions).hasSize(1)
        assertThat(content.mandate.executionCount).isEqualTo(1)
    }

    @Test
    fun `detail not found and revoke`() = runTest(dispatcher) {
        detailVm("ghost").uiState.test { awaitItem(); assertThat(awaitItem()).isEqualTo(MandateDetailUiState.NotFound) }
        seedMandate()
        val vm = detailVm()
        vm.uiState.test { awaitItem(); awaitItem() }
        vm.act(MandateAction.REVOKE); advanceUntilIdle()
        assertThat((vm.uiState.value as MandateDetailUiState.Content).mandate.status).isEqualTo(MandateStatus.REVOKED)
        assertThat((vm.uiState.value as MandateDetailUiState.Content).availableActions).isEmpty()
    }
}
