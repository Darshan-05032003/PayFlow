package com.darshan.payflow.domain.model

import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.testPayment
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PaymentStateMachineTest {

    private val allEvents = listOf(
        PaymentEvent.Initiate,
        PaymentEvent.Confirm(pspTxnId = "psp-1", upiTxnId = "upi-1"),
        PaymentEvent.Fail(reason = "declined"),
        PaymentEvent.RequestRefund,
        PaymentEvent.RefundConfirmed(refundId = "rf-1"),
        PaymentEvent.RefundFailed,
    )

    private val legal: Map<Pair<PaymentStatus, Class<out PaymentEvent>>, PaymentStatus> = mapOf(
        (PaymentStatus.CREATED to PaymentEvent.Initiate::class.java) to PaymentStatus.PENDING,
        (PaymentStatus.CREATED to PaymentEvent.Fail::class.java) to PaymentStatus.FAILED,
        (PaymentStatus.PENDING to PaymentEvent.Confirm::class.java) to PaymentStatus.SUCCESS,
        (PaymentStatus.PENDING to PaymentEvent.Fail::class.java) to PaymentStatus.FAILED,
        (PaymentStatus.SUCCESS to PaymentEvent.RequestRefund::class.java) to PaymentStatus.REFUND_PENDING,
        (PaymentStatus.REFUND_PENDING to PaymentEvent.RefundConfirmed::class.java) to PaymentStatus.REFUNDED,
        (PaymentStatus.REFUND_PENDING to PaymentEvent.RefundFailed::class.java) to PaymentStatus.SUCCESS,
    )

    @Test
    fun `every state and event pair is exhaustively classified`() {
        var checked = 0
        for (state in PaymentStatus.entries) {
            for (event in allEvents) {
                val expected = legal[state to event.javaClass]
                val result = PaymentStateMachine.transition(state, event)
                if (expected != null) {
                    assertThat(result).isEqualTo(TransitionResult.Accepted(expected))
                } else {
                    assertThat(result).isEqualTo(TransitionResult.Rejected(state, event))
                }
                checked++
            }
        }
        assertThat(checked).isEqualTo(PaymentStatus.entries.size * allEvents.size)
    }

    @Test
    fun `created payment cannot be confirmed without going pending first`() {
        val result = PaymentStateMachine.transition(PaymentStatus.CREATED, PaymentEvent.Confirm("psp", null))
        assertThat(result).isInstanceOf(TransitionResult.Rejected::class.java)
    }

    @Test
    fun `terminal states reject everything`() {
        for (state in listOf(PaymentStatus.FAILED, PaymentStatus.REFUNDED)) {
            assertThat(state.isTerminal).isTrue()
            for (event in allEvents) {
                assertThat(PaymentStateMachine.transition(state, event))
                    .isInstanceOf(TransitionResult.Rejected::class.java)
            }
        }
        assertThat(PaymentStatus.SUCCESS.isTerminal).isFalse()
        assertThat(PaymentStatus.PENDING.isTerminal).isFalse()
    }

    @Test
    fun `confirm records psp and upi transaction ids and bumps updatedAt`() {
        val pending = testPayment(status = PaymentStatus.PENDING)
        val later = FIXED_INSTANT.plusSeconds(30)
        val updated = PaymentStateMachine.apply(pending, PaymentEvent.Confirm("psp-9", "upi-9"), later).getOrThrow()
        assertThat(updated.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(updated.pspTxnId).isEqualTo("psp-9")
        assertThat(updated.upiTxnId).isEqualTo("upi-9")
        assertThat(updated.updatedAt).isEqualTo(later)
        assertThat(updated.createdAt).isEqualTo(FIXED_INSTANT)
    }

    @Test
    fun `fail records the reason`() {
        val failed = PaymentStateMachine.apply(testPayment(), PaymentEvent.Fail("U30 declined"), FIXED_INSTANT).getOrThrow()
        assertThat(failed.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(failed.failureReason).isEqualTo("U30 declined")
    }

    @Test
    fun `refund confirmed records refund id and refund failed restores success`() {
        val success = testPayment(status = PaymentStatus.SUCCESS)
        val refundPending = PaymentStateMachine.apply(success, PaymentEvent.RequestRefund, FIXED_INSTANT).getOrThrow()
        assertThat(refundPending.status).isEqualTo(PaymentStatus.REFUND_PENDING)
        val refunded = PaymentStateMachine.apply(refundPending, PaymentEvent.RefundConfirmed("rf-1"), FIXED_INSTANT).getOrThrow()
        assertThat(refunded.status).isEqualTo(PaymentStatus.REFUNDED)
        assertThat(refunded.refundId).isEqualTo("rf-1")
        val back = PaymentStateMachine.apply(refundPending, PaymentEvent.RefundFailed, FIXED_INSTANT).getOrThrow()
        assertThat(back.status).isEqualTo(PaymentStatus.SUCCESS)
    }

    @Test
    fun `illegal apply returns a failure and never mutates`() {
        val success = testPayment(status = PaymentStatus.SUCCESS)
        val result = PaymentStateMachine.apply(success, PaymentEvent.Initiate, FIXED_INSTANT)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalTransitionException::class.java)
        val ex = result.exceptionOrNull() as IllegalTransitionException
        assertThat(ex.from).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(ex.event).isEqualTo(PaymentEvent.Initiate)
    }

    @Test
    fun `payment rejects non positive and absurd amounts`() {
        assertThat(runCatching { testPayment(amountPaise = 0) }.isFailure).isTrue()
        assertThat(runCatching { testPayment(amountPaise = -1) }.isFailure).isTrue()
        assertThat(runCatching { testPayment(amountPaise = Payment.MAX_AMOUNT_PAISE + 1) }.isFailure).isTrue()
        assertThat(testPayment(amountPaise = Payment.MAX_AMOUNT_PAISE).amountPaise).isEqualTo(Payment.MAX_AMOUNT_PAISE)
    }

    @Test
    fun `history filter matches the right statuses`() {
        assertThat(PaymentStatus.entries.filter { HistoryFilter.ALL.matches(it) }).containsExactlyElementsIn(PaymentStatus.entries)
        assertThat(PaymentStatus.entries.filter { HistoryFilter.PENDING.matches(it) })
            .containsExactly(PaymentStatus.CREATED, PaymentStatus.PENDING, PaymentStatus.REFUND_PENDING)
        assertThat(PaymentStatus.entries.filter { HistoryFilter.SUCCESS.matches(it) }).containsExactly(PaymentStatus.SUCCESS)
        assertThat(PaymentStatus.entries.filter { HistoryFilter.FAILED.matches(it) }).containsExactly(PaymentStatus.FAILED)
        assertThat(PaymentStatus.entries.filter { HistoryFilter.REFUNDED.matches(it) }).containsExactly(PaymentStatus.REFUNDED)
    }

    @Test
    fun `summary counts statuses and sums only successful money per direction`() {
        val summary = HistorySummary.of(
            listOf(
                testPayment("a", status = PaymentStatus.SUCCESS, amountPaise = 100),
                testPayment("b", status = PaymentStatus.SUCCESS, amountPaise = 250, direction = PaymentDirection.INCOMING),
                testPayment("c", status = PaymentStatus.PENDING, amountPaise = 999),
                testPayment("d", status = PaymentStatus.FAILED, amountPaise = 999),
                testPayment("e", status = PaymentStatus.REFUNDED, amountPaise = 999),
                testPayment("f", status = PaymentStatus.REFUND_PENDING, amountPaise = 50),
            ),
        )
        assertThat(summary.total).isEqualTo(6)
        assertThat(summary.success).isEqualTo(3) // SUCCESS + REFUND_PENDING are still captured money
        assertThat(summary.pending).isEqualTo(1)
        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.refunded).isEqualTo(1)
        assertThat(summary.sentPaise).isEqualTo(150)
        assertThat(summary.receivedPaise).isEqualTo(250)
    }
}
