package com.darshan.payflow.ui.history

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.darshan.payflow.domain.model.HistoryFilter
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.testutil.FakePaymentRepository
import com.darshan.payflow.testutil.MainDispatcherRule
import com.darshan.payflow.testutil.TestDispatcherProvider
import com.darshan.payflow.testutil.testPayment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val mainRule = MainDispatcherRule(dispatcher)
    private val repo = FakePaymentRepository()

    private fun vm(handle: SavedStateHandle = SavedStateHandle()) =
        HistoryViewModel(repo, TestDispatcherProvider(dispatcher), handle)

    @Test
    fun `empty history shows empty state`() = runTest(dispatcher) {
        vm().uiState.test {
            assertThat(awaitItem()).isEqualTo(HistoryUiState.Loading)
            val empty = awaitItem() as HistoryUiState.Empty
            assertThat(empty.filter).isEqualTo(HistoryFilter.ALL)
        }
    }

    @Test
    fun `filter and query narrow the list and are kept in SavedStateHandle`() = runTest(dispatcher) {
        repo.seed(
            testPayment("a", status = PaymentStatus.SUCCESS, payeeName = "Alice"),
            testPayment("b", status = PaymentStatus.FAILED, payeeName = "Bob", payeeVpa = "bob@upi"),
        )
        val handle = SavedStateHandle()
        val vm = vm(handle)
        vm.uiState.test {
            awaitItem()
            assertThat((awaitItem() as HistoryUiState.Content).payments).hasSize(2)
            vm.setFilter(HistoryFilter.FAILED)
            val filtered = awaitItem() as HistoryUiState.Content
            assertThat(filtered.payments.map { it.id }).containsExactly("b")
            assertThat(filtered.filter).isEqualTo(HistoryFilter.FAILED)
            vm.setQuery("ali")
            val none = awaitItem() as HistoryUiState.Empty
            assertThat(none.query).isEqualTo("ali")
            assertThat(none.filter).isEqualTo(HistoryFilter.FAILED)
        }
        assertThat(handle.get<String>(HistoryViewModel.KEY_FILTER)).isEqualTo("FAILED")
        assertThat(handle.get<String>(HistoryViewModel.KEY_QUERY)).isEqualTo("ali")
    }

    @Test
    fun `restores filter and query from SavedStateHandle after process death`() = runTest(dispatcher) {
        repo.seed(testPayment("a", status = PaymentStatus.SUCCESS, payeeName = "Alice"))
        val handle = SavedStateHandle(mapOf(HistoryViewModel.KEY_FILTER to "SUCCESS", HistoryViewModel.KEY_QUERY to "ali"))
        vm(handle).uiState.test {
            awaitItem()
            val content = awaitItem() as HistoryUiState.Content
            assertThat(content.filter).isEqualTo(HistoryFilter.SUCCESS)
            assertThat(content.query).isEqualTo("ali")
            assertThat(content.payments).hasSize(1)
        }
    }

    @Test
    fun `garbage in saved state falls back to ALL`() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf(HistoryViewModel.KEY_FILTER to "DROP TABLE"))
        vm(handle).uiState.test {
            awaitItem()
            assertThat((awaitItem() as HistoryUiState.Empty).filter).isEqualTo(HistoryFilter.ALL)
        }
    }

    @Test
    fun `repository error surfaces as error state and retry reloads`() = runTest(dispatcher) {
        repo.failure = IOException("boom")
        val vm = vm()
        vm.uiState.test {
            awaitItem()
            assertThat(awaitItem()).isInstanceOf(HistoryUiState.Error::class.java)
            repo.failure = null
            vm.retry()
            assertThat(awaitItem()).isEqualTo(HistoryUiState.Loading)
            assertThat(awaitItem()).isInstanceOf(HistoryUiState.Empty::class.java)
        }
    }

    @Test
    fun `query is trimmed and length capped`() = runTest(dispatcher) {
        val vm = vm()
        vm.setQuery("  \u0000" + "x".repeat(500) + "\n  ")
        vm.uiState.test {
            awaitItem()
            val state = awaitItem() as HistoryUiState.Empty
            assertThat(state.query).hasLength(HistoryViewModel.MAX_QUERY_LENGTH)
            assertThat(state.query).isEqualTo("x".repeat(HistoryViewModel.MAX_QUERY_LENGTH))
        }
    }
}
