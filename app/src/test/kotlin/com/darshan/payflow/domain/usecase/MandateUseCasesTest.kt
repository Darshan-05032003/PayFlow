package com.darshan.payflow.domain.usecase

import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.psp.FakePspConfig
import com.darshan.payflow.domain.model.ExecutionCheck
import com.darshan.payflow.domain.model.MandateAction
import com.darshan.payflow.domain.model.MandateFrequency
import com.darshan.payflow.domain.model.MandateStatus
import com.darshan.payflow.domain.model.MandateValidation
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.FakeMandateRepository
import com.darshan.payflow.testutil.FakePaymentRepository
import com.darshan.payflow.testutil.fixedClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

class MandateUseCasesTest {
    private val mandates = FakeMandateRepository()
    private val payments = FakePaymentRepository()
    private val today: LocalDate = FIXED_INSTANT.atZone(ZoneOffset.UTC).toLocalDate()
    private val request = CreateMandateRequest("netflix@okbank", "Netflix", 64_900, MandateFrequency.MONTHLY, today, today.plusYears(1), "Premium plan")

    private fun create(psp: FakePsp = FakePsp(fixedClock)) = CreateMandateUseCase(mandates, psp, fixedClock, ZoneOffset.UTC) { "m-1" }
    private fun update(psp: FakePsp) = UpdateMandateUseCase(mandates, psp, fixedClock)
    private fun execute(psp: FakePsp, clock: Clock = fixedClock) = ExecuteMandateUseCase(mandates, payments, psp, clock, ZoneOffset.UTC) { "pay-m1" }

    @Test
    fun `create validates rules, registers with the psp and stores an active mandate with its umn`() = runTest {
        val psp = FakePsp(fixedClock)
        val result = create(psp).invoke(request) as CreateMandateResult.Created
        val stored = mandates.getById("m-1")!!
        assertThat(stored.status).isEqualTo(MandateStatus.ACTIVE)
        assertThat(stored.umn).isEqualTo(result.mandate.umn)
        assertThat(stored.umn).isNotEmpty()
        assertThat(stored.payeeName).isEqualTo("Netflix")
        assertThat(psp.mandates[stored.umn]!!.maxAmountPaise).isEqualTo(64_900)
    }

    @Test
    fun `create rejects rule violations before touching the psp`() = runTest {
        val psp = FakePsp(fixedClock)
        assertThat(create(psp).invoke(request.copy(maxAmountPaise = 0))).isEqualTo(CreateMandateResult.Invalid(MandateValidation.InvalidAmount))
        assertThat(create(psp).invoke(request.copy(startDate = today.minusDays(1)))).isEqualTo(CreateMandateResult.Invalid(MandateValidation.StartInPast))
        assertThat(create(psp).invoke(request.copy(payeeVpa = "bad"))).isEqualTo(CreateMandateResult.Invalid(MandateValidation.InvalidPayee))
        assertThat(create(psp).invoke(request.copy(payeeVpa = "unknown@okbank"))).isEqualTo(CreateMandateResult.Invalid(MandateValidation.InvalidPayee))
        assertThat(mandates.observeAll().first()).isEmpty()
        assertThat(psp.mandates).isEmpty()
    }

    @Test
    fun `psp failure on create stores nothing`() = runTest {
        val result = create(FakePsp(fixedClock, FakePspConfig(serverErrorRate = 1.0))).invoke(request)
        assertThat(result).isInstanceOf(CreateMandateResult.Unavailable::class.java)
        assertThat(mandates.getById("m-1")).isNull()
    }

    @Test
    fun `pause, resume and revoke follow the state machine and the psp`() = runTest {
        val psp = FakePsp(fixedClock)
        create(psp).invoke(request)
        assertThat((update(psp).invoke("m-1", MandateAction.PAUSE) as UpdateMandateResult.Updated).mandate.status).isEqualTo(MandateStatus.PAUSED)
        assertThat(psp.mandates.values.single().status).isEqualTo(MandateStatus.PAUSED)
        assertThat(update(psp).invoke("m-1", MandateAction.PAUSE)).isInstanceOf(UpdateMandateResult.IllegalTransition::class.java)
        assertThat((update(psp).invoke("m-1", MandateAction.RESUME) as UpdateMandateResult.Updated).mandate.status).isEqualTo(MandateStatus.ACTIVE)
        assertThat((update(psp).invoke("m-1", MandateAction.REVOKE) as UpdateMandateResult.Updated).mandate.status).isEqualTo(MandateStatus.REVOKED)
        assertThat(update(psp).invoke("m-1", MandateAction.RESUME)).isInstanceOf(UpdateMandateResult.IllegalTransition::class.java)
        assertThat(update(psp).invoke("ghost", MandateAction.PAUSE)).isEqualTo(UpdateMandateResult.NotFound)
    }

    @Test
    fun `psp failure on update keeps the local status`() = runTest {
        val psp = FakePsp(fixedClock)
        create(psp).invoke(request)
        val down = FakePsp(fixedClock, FakePspConfig(timeoutRate = 1.0))
        assertThat(update(down).invoke("m-1", MandateAction.PAUSE)).isInstanceOf(UpdateMandateResult.Unavailable::class.java)
        assertThat(mandates.getById("m-1")!!.status).isEqualTo(MandateStatus.ACTIVE)
    }

    @Test
    fun `execute within the rules creates a confirmed mandate payment and records the execution`() = runTest {
        val psp = FakePsp(fixedClock)
        create(psp).invoke(request)
        val result = execute(psp).invoke("m-1", 64_900) as ExecuteMandateResult.Executed
        assertThat(result.payment.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(result.payment.method).isEqualTo(PaymentMethod.MANDATE)
        assertThat(result.payment.mandateId).isEqualTo("m-1")
        val m = mandates.getById("m-1")!!
        assertThat(m.executionCount).isEqualTo(1)
        assertThat(m.lastExecutedAt).isEqualTo(FIXED_INSTANT)
    }

    @Test
    fun `execution above the max amount or twice in a period is rejected without touching the psp`() = runTest {
        val psp = FakePsp(fixedClock)
        create(psp).invoke(request)
        assertThat(execute(psp).invoke("m-1", 64_901)).isEqualTo(ExecuteMandateResult.Rejected(ExecutionCheck.AmountExceedsMax))
        execute(psp).invoke("m-1", 10_000)
        val calls = psp.initiateCalls
        assertThat(execute(psp).invoke("m-1", 10_000)).isEqualTo(ExecuteMandateResult.Rejected(ExecutionCheck.AlreadyExecutedThisPeriod))
        assertThat(psp.initiateCalls).isEqualTo(calls)
        val nextMonth = Clock.fixed(FIXED_INSTANT.plusSeconds(31L * 24 * 3600), ZoneOffset.UTC)
        assertThat(ExecuteMandateUseCase(mandates, payments, psp, nextMonth, ZoneOffset.UTC) { "pay-m2" }.invoke("m-1", 10_000))
            .isInstanceOf(ExecuteMandateResult.Executed::class.java)
        assertThat(mandates.getById("m-1")!!.executionCount).isEqualTo(2)
    }

    @Test
    fun `paused, revoked or expired mandates never execute`() = runTest {
        val psp = FakePsp(fixedClock)
        create(psp).invoke(request)
        update(psp).invoke("m-1", MandateAction.PAUSE)
        assertThat(execute(psp).invoke("m-1", 100)).isEqualTo(ExecuteMandateResult.Rejected(ExecutionCheck.NotActive))
        update(psp).invoke("m-1", MandateAction.RESUME)
        val afterEnd = Clock.fixed(FIXED_INSTANT.plusSeconds(400L * 24 * 3600), ZoneOffset.UTC)
        assertThat(execute(psp, afterEnd).invoke("m-1", 100)).isEqualTo(ExecuteMandateResult.Rejected(ExecutionCheck.OutsideValidity))
        update(psp).invoke("m-1", MandateAction.REVOKE)
        assertThat(execute(psp).invoke("m-1", 100)).isEqualTo(ExecuteMandateResult.Rejected(ExecutionCheck.NotActive))
    }

    @Test
    fun `psp rejecting an execution fails the payment and does not count as executed`() = runTest {
        val psp = FakePsp(fixedClock)
        create(psp).invoke(request)
        val down = FakePsp(fixedClock, FakePspConfig(serverErrorRate = 1.0))
        val result = execute(down).invoke("m-1", 100)
        assertThat(result).isInstanceOf(ExecuteMandateResult.Unavailable::class.java)
        assertThat(payments.getById("pay-m1")!!.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(mandates.getById("m-1")!!.executionCount).isEqualTo(0)
    }

    @Test
    fun `expire overdue marks mandates past their end date`() = runTest {
        val psp = FakePsp(fixedClock)
        create(psp).invoke(request.copy(endDate = today))
        val later = Clock.fixed(FIXED_INSTANT.plusSeconds(2L * 24 * 3600), ZoneOffset.UTC)
        ExpireMandatesUseCase(mandates, later, ZoneOffset.UTC).invoke()
        assertThat(mandates.getById("m-1")!!.status).isEqualTo(MandateStatus.EXPIRED)
    }
}
