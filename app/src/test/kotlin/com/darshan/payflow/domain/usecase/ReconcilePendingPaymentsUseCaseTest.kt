package com.darshan.payflow.domain.usecase

import com.darshan.payflow.core.BackoffPolicy
import com.darshan.payflow.data.psp.FakeOutcome
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.psp.FakePspConfig
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.UpiPayRequest
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.FakePaymentRepository
import com.darshan.payflow.testutil.fixedClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.ZoneOffset
import kotlin.random.Random

class ReconcilePendingPaymentsUseCaseTest {
    private val repo = FakePaymentRepository()
    private val policy = BackoffPolicy(initialDelayMillis = 10, maxDelayMillis = 100, multiplier = 2.0, jitterFactor = 0.0, maxAttempts = 3)

    @Test
    fun `after process death every pending payment is re-checked and settled from the psp`() = runTest {
        val psp = FakePsp(fixedClock, FakePspConfig(outcome = FakeOutcome.PENDING_UNTIL_SETTLED))
        var n = 0
        val pay = PayViaUpiIntentUseCase(repo, psp, fixedClock) { "p${++n}" }
        pay(UpiPayRequest("alice@okbank", "Alice", 100, "", "R1"), PaymentMethod.UPI_INTENT).getOrThrow()
        pay(UpiPayRequest("bob@upi", "Bob", 200, "", "R2"), PaymentMethod.UPI_INTENT).getOrThrow()
        pay(UpiPayRequest("shop@okbank", "Shop", 300, "", "R3"), PaymentMethod.UPI_INTENT).getOrThrow()
        psp.settle("R1", approved = true)
        psp.settle("R2", approved = false)
        // R3 stays pending at the PSP

        val later = Clock.fixed(FIXED_INSTANT.plusSeconds(60), ZoneOffset.UTC)
        val summary = ReconcilePendingPaymentsUseCase(repo, PollPaymentStatusUseCase(ConfirmPaymentUseCase(repo, psp, later), policy, Random(1)), later).invoke()
        assertThat(repo.getById("p1")!!.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(repo.getById("p2")!!.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(repo.getById("p3")!!.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(summary.checked).isEqualTo(3)
        assertThat(summary.settled).isEqualTo(2)
    }

    @Test
    fun `very fresh pending payments are left alone (the screen that created them is still polling)`() = runTest {
        val psp = FakePsp(fixedClock)
        PayViaUpiIntentUseCase(repo, psp, fixedClock) { "p1" }.invoke(UpiPayRequest("alice@okbank", "Alice", 100, "", "R1"), PaymentMethod.UPI_INTENT)
        val summary = ReconcilePendingPaymentsUseCase(repo, PollPaymentStatusUseCase(ConfirmPaymentUseCase(repo, psp, fixedClock), policy, Random(1)), fixedClock).invoke()
        assertThat(summary.checked).isEqualTo(0)
        assertThat(repo.getById("p1")!!.status).isEqualTo(PaymentStatus.PENDING)
    }
}
