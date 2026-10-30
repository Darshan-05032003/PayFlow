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

    private val psp = com.darshan.payflow.data.psp.FakePsp(com.darshan.payflow.testutil.fixedClock, com.darshan.payflow.data.psp.FakePspConfig(pendingPolls = 1))
    private fun reconcile() = com.darshan.payflow.domain.usecase.ReconcilePendingPaymentsUseCase(
        repo,
        com.darshan.payflow.domain.usecase.PollPaymentStatusUseCase(
            com.darshan.payflow.domain.usecase.ConfirmPaymentUseCase(repo, psp, com.darshan.payflow.testutil.fixedClock),
            com.darshan.payflow.core.BackoffPolicy(initialDelayMillis = 10, maxDelayMillis = 100, multiplier = 2.0, jitterFactor = 0.0, maxAttempts = 2),
            kotlin.random.Random(1),
        ),
        com.darshan.payflow.testutil.fixedClock,
    )

    private fun vm() = HomeViewModel(repo, reconcile(), TestDispatcherProvider(dispatcher))

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
            assertThat(content.recentPayees.map { it.vpa }).containsExactly("alice@okbank") // distinct by VPA
        }
    }

    @Test
    fun `month summary only counts payments created this month`() = runTest(dispatcher) {
        // fixedClock is 2026-10-29T10:15:30Z; "b" was created in September.
        repo.seed(
            testPayment("a", status = PaymentStatus.SUCCESS, amountPaise = 100),
            testPayment("b", status = PaymentStatus.SUCCESS, amountPaise = 200, createdAt = java.time.Instant.parse("2026-09-30T23:59:59Z")),
        )
        val vm = HomeViewModel(repo, reconcile(), TestDispatcherProvider(dispatcher), com.darshan.payflow.testutil.fixedClock, java.time.ZoneOffset.UTC)
        vm.uiState.test {
            awaitItem() // loading
            val content = awaitItem() as HomeUiState.Content
            assertThat(content.summary.sentPaise).isEqualTo(300)
            assertThat(content.month.sentPaise).isEqualTo(100)
            assertThat(content.month.total).isEqualTo(1)
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

    @Test
    fun `home start reconciles stale pending payments with the psp`() = runTest(dispatcher) {
        val stale = testPayment("stale", txnRef = "STALE1", status = PaymentStatus.PENDING, createdAt = testPayment().createdAt.minusSeconds(3_600))
        repo.seed(stale)
        psp.initiatePayment(com.darshan.payflow.data.psp.PspInitiateRequest("STALE1", stale.amountPaise, stale.payeeVpa, stale.payeeName, "", null))
        vm().uiState.test {
            awaitItem()
            awaitItem() // content with the pending row
            val settled = awaitItem() as HomeUiState.Content
            assertThat(settled.recent.single().status).isEqualTo(PaymentStatus.SUCCESS)
        }
    }
}
