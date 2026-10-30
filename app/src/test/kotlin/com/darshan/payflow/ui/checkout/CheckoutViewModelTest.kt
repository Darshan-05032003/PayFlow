package com.darshan.payflow.ui.checkout

import com.darshan.checkoutkit.core.CheckoutResult
import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.usecase.RecordCheckoutResultUseCase
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

@OptIn(ExperimentalCoroutinesApi::class)
class CheckoutViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val mainRule = MainDispatcherRule(dispatcher)
    private val repo = FakePaymentRepository()
    private fun vm() = CheckoutViewModel(RecordCheckoutResultUseCase(repo, fixedClock, { "ck-1" }) { "CKREF" }, TestDispatcherProvider(dispatcher), androidx.lifecycle.SavedStateHandle())

    @Test
    fun `catalogue, order creation and result recording`() = runTest(dispatcher) {
        val vm = vm()
        assertThat(vm.uiState.value.products).hasSize(3)
        val order = vm.startOrder(vm.uiState.value.products.first())
        assertThat(order.amountPaise).isEqualTo(vm.uiState.value.products.first().pricePaise)
        assertThat(order.orderId).startsWith("PF-")
        assertThat(vm.uiState.value.activeOrder).isEqualTo(order)
        vm.onResult(CheckoutResult.Success("CKREF", order.amountPaise)); advanceUntilIdle()
        val last = vm.uiState.value.lastOutcome as CheckoutOutcome.Recorded
        assertThat(last.payment.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(vm.uiState.value.activeOrder).isNull()
        assertThat(repo.getById("ck-1")!!.amountPaise).isEqualTo(order.amountPaise)
    }

    @Test
    fun `cancelled is shown but not stored and a second order gets a new id`() = runTest(dispatcher) {
        val vm = vm()
        val first = vm.startOrder(vm.uiState.value.products[1])
        vm.onResult(CheckoutResult.Cancelled); advanceUntilIdle()
        assertThat(vm.uiState.value.lastOutcome).isEqualTo(CheckoutOutcome.Cancelled)
        assertThat(repo.getById("ck-1")).isNull()
        val second = vm.startOrder(vm.uiState.value.products[1])
        assertThat(second.orderId).isNotEqualTo(first.orderId)
    }

    @Test
    fun `a result without an active order is ignored and failed results carry the error`() = runTest(dispatcher) {
        val vm = vm()
        vm.onResult(CheckoutResult.Failed(CheckoutError.Declined)); advanceUntilIdle()
        assertThat(vm.uiState.value.lastOutcome).isNull()
        vm.startOrder(vm.uiState.value.products[2])
        vm.onResult(CheckoutResult.Failed(CheckoutError.Declined)); advanceUntilIdle()
        assertThat((vm.uiState.value.lastOutcome as CheckoutOutcome.Recorded).payment.status).isEqualTo(PaymentStatus.FAILED)
    }

    @Test
    fun `the active order survives process death so a late sheet result is still recorded`() = runTest(dispatcher) {
        val handle = androidx.lifecycle.SavedStateHandle()
        val vm1 = CheckoutViewModel(RecordCheckoutResultUseCase(repo, fixedClock, { "ck-1" }) { "CKREF" }, TestDispatcherProvider(dispatcher), handle)
        val order = vm1.startOrder(vm1.uiState.value.products[0])
        val vm2 = CheckoutViewModel(RecordCheckoutResultUseCase(repo, fixedClock, { "ck-1" }) { "CKREF" }, TestDispatcherProvider(dispatcher), handle)
        assertThat(vm2.uiState.value.activeOrder).isEqualTo(order)
    }
}
