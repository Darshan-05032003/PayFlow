package com.darshan.payflow.ui.history

import androidx.lifecycle.SavedStateHandle
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

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val mainRule = MainDispatcherRule(dispatcher)
    private val repo = FakePaymentRepository()

    private val launcher = com.darshan.payflow.testutil.FakeUpiAppLauncher()

    private fun vm(id: String) = PaymentDetailViewModel(
        repo, com.darshan.payflow.domain.usecase.MarkPaymentManuallyUseCase(repo), launcher, TestDispatcherProvider(dispatcher),
        SavedStateHandle(mapOf(PaymentDetailViewModel.ARG_ID to id)),
    )

    @Test
    fun `a pending upi app payment shows the app name and can be marked by hand`() = runTest(dispatcher) {
        repo.seed(testPayment("r", status = PaymentStatus.PENDING).copy(handlerPackage = "com.example.gpay"))
        val vm = vm("r")
        vm.uiState.test {
            awaitItem() // loading
            val content = awaitItem() as PaymentDetailUiState.Content
            assertThat(content.handlerLabel).isEqualTo("GPay")
            assertThat(content.payment.canBeMarkedManually).isTrue()
            vm.markManually(paid = false)
            val marked = awaitItem() as PaymentDetailUiState.Content
            assertThat(marked.marking).isTrue()
            val settled = awaitItem() as PaymentDetailUiState.Content
            assertThat(settled.payment.status).isEqualTo(PaymentStatus.FAILED)
            assertThat(settled.payment.statusSource).isEqualTo(com.darshan.payflow.domain.model.StatusSource.USER)
            assertThat(awaitItem()).isEqualTo(settled.copy(marking = false))
        }
    }

    @Test
    fun `shows the payment and follows updates`() = runTest(dispatcher) {
        repo.seed(testPayment("a", status = PaymentStatus.PENDING))
        vm("a").uiState.test {
            assertThat(awaitItem()).isEqualTo(PaymentDetailUiState.Loading)
            val content = awaitItem() as PaymentDetailUiState.Content
            assertThat(content.payment.status).isEqualTo(PaymentStatus.PENDING)
            repo.applyEvent("a", com.darshan.payflow.domain.model.PaymentEvent.Confirm("psp", null))
            assertThat((awaitItem() as PaymentDetailUiState.Content).payment.status).isEqualTo(PaymentStatus.SUCCESS)
        }
    }

    @Test
    fun `missing payment is not found`() = runTest(dispatcher) {
        vm("ghost").uiState.test {
            awaitItem()
            assertThat(awaitItem()).isEqualTo(PaymentDetailUiState.NotFound)
        }
    }

    @Test
    fun `repository failure is an error`() = runTest(dispatcher) {
        repo.failure = IllegalStateException("db")
        vm("a").uiState.test {
            awaitItem()
            assertThat(awaitItem()).isInstanceOf(PaymentDetailUiState.Error::class.java)
        }
    }
}
