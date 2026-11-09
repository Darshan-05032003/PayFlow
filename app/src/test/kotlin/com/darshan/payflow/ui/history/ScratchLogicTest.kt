package com.darshan.payflow.ui.history

import com.darshan.payflow.domain.model.PaymentDirection
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.StatusSource
import com.darshan.payflow.testutil.testPayment
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScratchLogicTest {
    private val paid = testPayment("pay-42", status = PaymentStatus.SUCCESS)

    @Test
    fun `only a provider-confirmed outgoing success earns a demo card`() {
        assertThat(DemoReward.eligible(paid)).isTrue()
        PaymentStatus.entries.filter { it != PaymentStatus.SUCCESS }.forEach { assertThat(DemoReward.eligible(paid.copy(status = it))).isFalse() }
        assertThat(DemoReward.eligible(paid.copy(statusSource = StatusSource.UPI_APP, handlerPackage = "com.example.wallet"))).isFalse()
        assertThat(DemoReward.eligible(paid.copy(statusSource = StatusSource.USER, handlerPackage = "com.example.wallet"))).isFalse()
        assertThat(DemoReward.eligible(paid.copy(direction = PaymentDirection.INCOMING))).isFalse()
        assertThat(DemoReward.forPayment(paid.copy(status = PaymentStatus.FAILED))).isNull()
    }

    @Test
    fun `the demo amount is small, whole rupees, and the same every time for a payment`() {
        val first = DemoReward.forPayment(paid)!!
        assertThat(DemoReward.forPayment(paid)).isEqualTo(first)
        (0 until 2_000).forEach { i ->
            val r = DemoReward.forPayment(paid.copy(id = "p$i"))!!
            assertThat(r.amountPaise).isAtLeast(DemoReward.MIN_PAISE)
            assertThat(r.amountPaise).isAtMost(DemoReward.MAX_PAISE)
            assertThat(r.amountPaise % 100).isEqualTo(0)
        }
    }

    @Test
    fun `a demo reward never touches the payment it came from`() {
        val before = paid.copy()
        DemoReward.forPayment(paid)
        DemoRewardLedger().markScratched(paid.id)
        assertThat(paid).isEqualTo(before)
    }

    @Test
    fun `scratching marks the cells under the finger and reveals past the threshold`() {
        val coverage = ScratchCoverage(cols = 10, rows = 5)
        assertThat(coverage.scratch(5f, 5f, 4f, 100f, 50f)).isTrue()
        assertThat(coverage.cleared).isGreaterThan(0)
        assertThat(coverage.revealed).isFalse()
        // The same spot again changes nothing.
        assertThat(coverage.scratch(5f, 5f, 4f, 100f, 50f)).isFalse()
        var y = 0f
        while (y <= 50f) {
            var x = 0f
            while (x <= 100f) { coverage.scratch(x, y, 6f, 100f, 50f); x += 8f }
            y += 8f
        }
        assertThat(coverage.revealed).isTrue()
        assertThat(coverage.fraction).isAtMost(1f)
    }

    @Test
    fun `strokes off the card do nothing, and a zero-size card is safe`() {
        val coverage = ScratchCoverage()
        assertThat(coverage.scratch(-100f, -100f, 10f, 300f, 150f)).isFalse()
        assertThat(coverage.scratch(10f, 10f, 10f, 0f, 0f)).isFalse()
        assertThat(coverage.cleared).isEqualTo(0)
    }

    @Test
    fun `the ledger remembers a scratched card for the run`() {
        val ledger = DemoRewardLedger()
        assertThat(ledger.isScratched("a")).isFalse()
        ledger.markScratched("a")
        assertThat(ledger.isScratched("a")).isTrue()
        assertThat(ledger.isScratched("b")).isFalse()
    }
}
