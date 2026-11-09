package com.darshan.payflow.ui.mandates

import app.cash.turbine.test
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.psp.FakePspConfig
import com.darshan.payflow.domain.model.MandateAction
import com.darshan.payflow.domain.model.MandateFrequency
import com.darshan.payflow.domain.model.MandateStatus
import com.darshan.payflow.domain.usecase.CreateMandateRequest
import com.darshan.payflow.domain.usecase.CreateMandateUseCase
import com.darshan.payflow.domain.usecase.UpdateMandateResult
import com.darshan.payflow.domain.usecase.UpdateMandateUseCase
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.FakeMandateRepository
import com.darshan.payflow.testutil.MainDispatcherRule
import com.darshan.payflow.testutil.TestDispatcherProvider
import com.darshan.payflow.testutil.fixedClock
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Pause and resume from the card stack: what the card shows is what the data layer holds. */
@OptIn(ExperimentalCoroutinesApi::class)
class MandatesStackViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val mainRule = MainDispatcherRule(dispatcher)
    private val mandates = FakeMandateRepository()
    private val today: LocalDate = FIXED_INSTANT.atZone(ZoneOffset.UTC).toLocalDate()

    private suspend fun seed(psp: FakePsp) =
        CreateMandateUseCase(mandates, psp, fixedClock, ZoneOffset.UTC) { "m-1" }
            .invoke(CreateMandateRequest("lumora@okbank", "Lumora", 64_900, MandateFrequency.MONTHLY, today, today.plusYears(1), ""))

    private fun vm(psp: FakePsp) = MandatesViewModel(mandates, fixedClock, ZoneOffset.UTC, TestDispatcherProvider(dispatcher), UpdateMandateUseCase(mandates, psp, fixedClock))


    @Test
    fun `pause and resume from a card change the stored mandate and the card follows it`() = runTest(dispatcher) {
        val psp = FakePsp(fixedClock)
        seed(psp)
        val vm = vm(psp)
        vm.uiState.test {
            awaitItem()
            assertThat((awaitItem() as MandatesUiState.Content).mandates.single().status).isEqualTo(MandateStatus.ACTIVE)
            vm.act("m-1", MandateAction.PAUSE)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(mandates.getById("m-1")!!.status).isEqualTo(MandateStatus.PAUSED)
        vm.uiState.test {
            assertThat((expectMostRecentItem() as MandatesUiState.Content).mandates.single().status).isEqualTo(MandateStatus.PAUSED)
            vm.act("m-1", MandateAction.RESUME)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(mandates.getById("m-1")!!.status).isEqualTo(MandateStatus.ACTIVE)
    }

    @Test
    fun `a provider that can't be reached leaves the mandate as it was and says so`() = runTest(dispatcher) {
        seed(FakePsp(fixedClock))
        val down = FakePsp(fixedClock, FakePspConfig(serverErrorRate = 1.0))
        val vm = vm(down)
        vm.uiState.test { awaitItem(); awaitItem(); cancelAndIgnoreRemainingEvents() }
        vm.events.test {
            vm.act("m-1", MandateAction.PAUSE)
            advanceUntilIdle()
            val result = (awaitItem() as MandateDetailEvent.ActionResult).result
            assertThat(result).isInstanceOf(UpdateMandateResult.Unavailable::class.java)
        }
        assertThat(mandates.getById("m-1")!!.status).isEqualTo(MandateStatus.ACTIVE)
    }

    @Test
    fun `a flood of taps on one card sends one action`() = runTest(dispatcher) {
        val psp = FakePsp(fixedClock)
        seed(psp)
        val vm = vm(psp)
        vm.uiState.test { awaitItem(); awaitItem(); cancelAndIgnoreRemainingEvents() }
        vm.events.test {
            repeat(1_000) { vm.act("m-1", MandateAction.PAUSE) }
            advanceUntilIdle()
            assertThat((awaitItem() as MandateDetailEvent.ActionResult).result).isInstanceOf(UpdateMandateResult.Updated::class.java)
            expectNoEvents()
        }
        assertThat(mandates.getById("m-1")!!.status).isEqualTo(MandateStatus.PAUSED)
    }

    @Test
    fun `revoke and expire are never quick actions`() = runTest(dispatcher) {
        val psp = FakePsp(fixedClock)
        seed(psp)
        val vm = vm(psp)
        vm.act("m-1", MandateAction.REVOKE)
        vm.act("m-1", MandateAction.EXPIRE)
        advanceUntilIdle()
        assertThat(mandates.getById("m-1")!!.status).isEqualTo(MandateStatus.ACTIVE)
    }
}
