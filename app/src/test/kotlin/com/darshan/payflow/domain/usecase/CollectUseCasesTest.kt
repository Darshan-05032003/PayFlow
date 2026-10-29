package com.darshan.payflow.domain.usecase

import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.psp.FakePspConfig
import com.darshan.payflow.domain.model.CollectStatus
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.testutil.FakeCollectRepository
import com.darshan.payflow.testutil.FakePaymentRepository
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.fixedClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.ZoneOffset

class CollectUseCasesTest {
    private val payments = FakePaymentRepository()
    private val collects = FakeCollectRepository()

    private fun psp(config: FakePspConfig = FakePspConfig()) = FakePsp(fixedClock, config).also {
        it.simulateIncomingCollect("req-1", "shop@okbank", "Chai Point", 4_500, "Table 4", ttlMillis = 10 * 60_000)
        it.simulateIncomingCollect("req-2", "landlord@ybl", "Mr Rao", 1_500_000, "Rent", ttlMillis = 60_000)
    }

    @Test
    fun `refresh pulls incoming requests from the psp into the repository`() = runTest {
        val psp = psp()
        RefreshCollectRequestsUseCase(collects, psp, fixedClock).invoke().getOrThrow()
        val all = collects.observeAll().first()
        assertThat(all.map { it.id }).containsExactly("req-1", "req-2")
        assertThat(all.first { it.id == "req-1" }.status).isEqualTo(CollectStatus.PENDING)
        assertThat(all.first { it.id == "req-2" }.amountPaise).isEqualTo(1_500_000)
    }

    @Test
    fun `refresh marks overdue pending requests expired locally and remotely`() = runTest {
        val psp = psp()
        RefreshCollectRequestsUseCase(collects, psp, fixedClock).invoke().getOrThrow()
        val later = Clock.fixed(FIXED_INSTANT.plusSeconds(120), ZoneOffset.UTC)
        RefreshCollectRequestsUseCase(collects, psp, later).invoke().getOrThrow()
        val all = collects.observeAll().first().associateBy { it.id }
        assertThat(all["req-2"]!!.status).isEqualTo(CollectStatus.EXPIRED)
        assertThat(all["req-1"]!!.status).isEqualTo(CollectStatus.PENDING)
    }

    @Test
    fun `approve creates a collect payment, answers the psp and confirms through the status check`() = runTest {
        val psp = psp()
        RefreshCollectRequestsUseCase(collects, psp, fixedClock).invoke().getOrThrow()
        val result = RespondToCollectUseCase(collects, payments, psp, fixedClock) { "pay-c1" }.invoke("req-1", approve = true)
        assertThat(result).isInstanceOf(CollectActionResult.Approved::class.java)
        val payment = payments.getById("pay-c1")!!
        assertThat(payment.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(payment.method).isEqualTo(PaymentMethod.COLLECT)
        assertThat(payment.amountPaise).isEqualTo(4_500)
        assertThat(payment.payeeVpa).isEqualTo("shop@okbank")
        val request = collects.getById("req-1")!!
        assertThat(request.status).isEqualTo(CollectStatus.APPROVED)
        assertThat(request.paymentId).isEqualTo("pay-c1")
        assertThat(psp.collectRequests["req-1"]!!.status).isEqualTo(CollectStatus.APPROVED)
    }

    @Test
    fun `decline answers the psp and creates no payment`() = runTest {
        val psp = psp()
        RefreshCollectRequestsUseCase(collects, psp, fixedClock).invoke().getOrThrow()
        val result = RespondToCollectUseCase(collects, payments, psp, fixedClock) { "pay-c1" }.invoke("req-1", approve = false)
        assertThat(result).isInstanceOf(CollectActionResult.Declined::class.java)
        assertThat(payments.getById("pay-c1")).isNull()
        assertThat(collects.getById("req-1")!!.status).isEqualTo(CollectStatus.DECLINED)
    }

    @Test
    fun `an expired request cannot be approved, locally or by the psp`() = runTest {
        val psp = psp()
        RefreshCollectRequestsUseCase(collects, psp, fixedClock).invoke().getOrThrow()
        val later = Clock.fixed(FIXED_INSTANT.plusSeconds(120), ZoneOffset.UTC)
        val result = RespondToCollectUseCase(collects, payments, psp, later) { "pay-c1" }.invoke("req-2", approve = true)
        assertThat(result).isInstanceOf(CollectActionResult.Expired::class.java)
        assertThat(payments.getById("pay-c1")).isNull()
        assertThat(collects.getById("req-2")!!.status).isEqualTo(CollectStatus.EXPIRED)
    }

    @Test
    fun `answering twice is rejected and never creates a second payment`() = runTest {
        val psp = psp()
        RefreshCollectRequestsUseCase(collects, psp, fixedClock).invoke().getOrThrow()
        var n = 0
        val respond = RespondToCollectUseCase(collects, payments, psp, fixedClock) { "pay-c${++n}" }
        respond("req-1", approve = true)
        val again = respond("req-1", approve = true)
        assertThat(again).isInstanceOf(CollectActionResult.AlreadyAnswered::class.java)
        assertThat(payments.getById("pay-c2")).isNull()
        assertThat(respond("req-1", approve = false)).isInstanceOf(CollectActionResult.AlreadyAnswered::class.java)
    }

    @Test
    fun `psp failure while approving leaves the request pending and the payment failed, nothing is stuck`() = runTest {
        val psp = psp()
        RefreshCollectRequestsUseCase(collects, psp, fixedClock).invoke().getOrThrow()
        val flaky = FakePsp(fixedClock, FakePspConfig(timeoutRate = 1.0))
        val result = RespondToCollectUseCase(collects, payments, flaky, fixedClock) { "pay-c1" }.invoke("req-1", approve = true)
        assertThat(result).isInstanceOf(CollectActionResult.Unavailable::class.java)
        assertThat(collects.getById("req-1")!!.status).isEqualTo(CollectStatus.PENDING)
        assertThat(payments.getById("pay-c1")!!.status).isEqualTo(PaymentStatus.FAILED)
    }

    @Test
    fun `unknown request id`() = runTest {
        val result = RespondToCollectUseCase(collects, payments, psp(), fixedClock) { "x" }.invoke("ghost", approve = true)
        assertThat(result).isEqualTo(CollectActionResult.NotFound)
    }

    @Test
    fun `refresh failure is reported but keeps local data`() = runTest {
        RefreshCollectRequestsUseCase(collects, psp(), fixedClock).invoke().getOrThrow()
        val down = FakePsp(fixedClock, FakePspConfig(serverErrorRate = 1.0))
        assertThat(RefreshCollectRequestsUseCase(collects, down, fixedClock).invoke().isFailure).isTrue()
        assertThat(collects.observeAll().first()).hasSize(2)
    }

    @Test
    fun `psp says already answered (another device) - reported as such, no second payment stays open`() = runTest {
        RefreshCollectRequestsUseCase(collects, psp(), fixedClock).invoke().getOrThrow()
        val other = object : com.darshan.payflow.testutil.StubPsp() {
            override suspend fun respondToCollect(requestId: String, approve: Boolean) = throw com.darshan.payflow.data.psp.PspException.ClientError(409, "answered")
        }
        val result = RespondToCollectUseCase(collects, payments, other, fixedClock) { "pay-c1" }.invoke("req-1", approve = true)
        assertThat(result).isInstanceOf(CollectActionResult.AlreadyAnswered::class.java)
        assertThat(payments.getById("pay-c1")!!.status).isEqualTo(PaymentStatus.FAILED)
    }
}
