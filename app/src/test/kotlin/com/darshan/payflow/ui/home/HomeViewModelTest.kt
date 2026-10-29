package com.darshan.payflow.ui.home

import app.cash.turbine.test
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
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val mainRule = MainDispatcherRule(dispatcher)
    private val repo = FakePaymentRepository()

    private fun vm() = HomeViewModel(repo, TestDispatcherProvider(dispatcher))

    @Test
    fun `starts loading then shows empty when there are no payments`() = runTest(dispatcher) {
        vm().uiState.test {
            assertThat(awaitItem()).isEqualTo(HomeUiState.Loading)
            assertThat(awaitItem()).isEqualTo(HomeUiState.Empty)
        }
    }

    @Test
    fun `shows recent payments and summary`() = runTest(dispatcher) {
        repo.seed(
            testPayment("a", status = PaymentStatus.SUCCESS, amountPaise = 100),
            testPayment("b", status = PaymentStatus.PENDING, amountPaise = 200, createdAt = testPayment().createdAt.plusSeconds(1)),
        )
        vm().uiState.test {
            awaitItem() // loading
            val content = awaitItem() as HomeUiState.Content
            assertThat(content.recent.map { it.id }).containsExactly("b", "a").inOrder()
            assertThat(content.summary.total).isEqualTo(2)
            assertThat(content.summary.sentPaise).isEqualTo(100)
        }
    }

    @Test
    fun `repository failure becomes error state and retry recovers`() = runTest(dispatcher) {
        repo.failure = IOException("disk")
        val vm = vm()
        vm.uiState.test {
            awaitItem()
            assertThat(awaitItem()).isInstanceOf(HomeUiState.Error::class.java)
            repo.failure = null
            vm.retry()
            assertThat(awaitItem()).isEqualTo(HomeUiState.Loading)
            assertThat(awaitItem()).isEqualTo(HomeUiState.Empty)
        }
    }

    @Test
    fun `recent list is capped`() = runTest(dispatcher) {
        repo.seed(*(1..20).map { testPayment("p$it", txnRef = "t$it", createdAt = testPayment().createdAt.plusSeconds(it.toLong())) }.toTypedArray())
        vm().uiState.test {
            awaitItem()
            val content = awaitItem() as HomeUiState.Content
            assertThat(content.recent).hasSize(HomeViewModel.RECENT_LIMIT)
            assertThat(content.recent.first().id).isEqualTo("p20")
        }
    }
}
