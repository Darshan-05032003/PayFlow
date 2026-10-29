package com.darshan.payflow.ui.collect

import app.cash.turbine.test
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.domain.model.CollectStatus
import com.darshan.payflow.domain.usecase.RefreshCollectRequestsUseCase
import com.darshan.payflow.domain.usecase.RespondToCollectUseCase
import com.darshan.payflow.testutil.FakeCollectRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
class CollectViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val mainRule = MainDispatcherRule(dispatcher)
    private val collects = FakeCollectRepository()
    private val payments = FakePaymentRepository()
    private val psp = FakePsp(fixedClock).also {
        it.simulateIncomingCollect("req-1", "shop@okbank", "Chai Point", 4_500, "Table 4", ttlMillis = 600_000)
    }

    private fun vm(psp: FakePsp = this.psp) = CollectViewModel(
        collects,
        RefreshCollectRequestsUseCase(collects, psp, fixedClock),
        RespondToCollectUseCase(collects, payments, psp, fixedClock) { "pay-1" },
        fixedClock,
        TestDispatcherProvider(dispatcher),
    )

    @Test
    fun `loads, refreshes from the psp and shows content`() = runTest(dispatcher) {
        vm().uiState.test {
            assertThat(awaitItem()).isEqualTo(CollectUiState.Loading)
            val content = awaitItem() as CollectUiState.Content
            assertThat(content.requests.map { it.request.id }).containsExactly("req-1")
            assertThat(content.requests.single().request.status).isEqualTo(CollectStatus.PENDING)
            assertThat(content.refreshing).isFalse()
        }
    }

    @Test
    fun `empty when the psp has nothing`() = runTest(dispatcher) {
        vm(FakePsp(fixedClock)).uiState.test {
            awaitItem()
            assertThat(awaitItem()).isEqualTo(CollectUiState.Empty)
        }
    }

    @Test
    fun `approve shows the item busy then approved and emits a result event`() = runTest(dispatcher) {
        val vm = vm()
        vm.uiState.test { awaitItem(); awaitItem() }
        vm.events.test {
            vm.approve("req-1")
            val event = awaitItem() as CollectEvent.Answered
            assertThat(event.result).isInstanceOf(com.darshan.payflow.domain.usecase.CollectActionResult.Approved::class.java)
        }
        advanceUntilIdle()
        val content = vm.uiState.value as CollectUiState.Content
        assertThat(content.requests.single().request.status).isEqualTo(CollectStatus.APPROVED)
        assertThat(content.requests.single().busy).isFalse()
        assertThat(payments.getById("pay-1")).isNotNull()
    }

    @Test
    fun `decline`() = runTest(dispatcher) {
        val vm = vm()
        vm.uiState.test { awaitItem(); awaitItem() }
        vm.decline("req-1"); advanceUntilIdle()
        assertThat((vm.uiState.value as CollectUiState.Content).requests.single().request.status).isEqualTo(CollectStatus.DECLINED)
    }

    @Test
    fun `double tap approve answers once`() = runTest(dispatcher) {
        val vm = vm()
        vm.uiState.test { awaitItem(); awaitItem() }
        vm.approve("req-1"); vm.approve("req-1"); vm.decline("req-1")
        advanceUntilIdle()
        assertThat(psp.collectRequests["req-1"]!!.status).isEqualTo(CollectStatus.APPROVED)
        assertThat(payments.getById("pay-1")).isNotNull()
    }

    @Test
    fun `repository failure is an error state and retry recovers`() = runTest(dispatcher) {
        collects.failure = IOException("db")
        val vm = vm()
        vm.uiState.test {
            awaitItem()
            assertThat(awaitItem()).isInstanceOf(CollectUiState.Error::class.java)
            collects.failure = null
            vm.refresh()
            assertThat(awaitItem()).isEqualTo(CollectUiState.Loading)
            assertThat(awaitItem()).isInstanceOf(CollectUiState.Content::class.java)
        }
    }

    @Test
    fun `psp refresh failure keeps local content and reports via event`() = runTest(dispatcher) {
        val vm = vm()
        vm.uiState.test { awaitItem(); awaitItem() }
        val down = FakePsp(fixedClock, com.darshan.payflow.data.psp.FakePspConfig(serverErrorRate = 1.0))
        val vm2 = CollectViewModel(
            collects, RefreshCollectRequestsUseCase(collects, down, fixedClock),
            RespondToCollectUseCase(collects, payments, down, fixedClock) { "p" }, fixedClock, TestDispatcherProvider(dispatcher),
        )
        vm2.events.test {
            vm2.uiState.test { awaitItem(); assertThat(awaitItem()).isInstanceOf(CollectUiState.Content::class.java) }
            assertThat(awaitItem()).isEqualTo(CollectEvent.RefreshFailed)
        }
    }
}
