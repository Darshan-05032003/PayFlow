package com.darshan.payflow.ui.pay

import com.darshan.payflow.data.upi.UpiApp
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.StatusSource
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.ui.designsystem.effects.HapticEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AmountInputTest {
    private fun type(vararg keys: AmountKey, from: String = ""): String = keys.fold(from) { text, key ->
        when (val r = AmountInput.press(text, key)) {
            is AmountPress.Accepted -> r.text
            is AmountPress.Refused -> text
        }
    }

    private val d1 = AmountKey.digit(1)
    private val d0 = AmountKey.digit(0)
    private val d5 = AmountKey.digit(5)

    @Test
    fun `digits build an amount and a leading zero is replaced, never doubled`() {
        assertThat(type(d1, d5, d0)).isEqualTo("150")
        assertThat(type(d0, d0, d0)).isEqualTo("0")
        assertThat(type(d0, d5)).isEqualTo("5")
    }

    @Test
    fun `a dot on empty starts at zero, a second dot is refused`() {
        assertThat(type(AmountKey.Dot)).isEqualTo("0.")
        assertThat(type(AmountKey.Dot, d5)).isEqualTo("0.5")
        assertThat(AmountInput.press("1.5", AmountKey.Dot)).isEqualTo(AmountPress.Refused(AmountRefusal.EXTRA_DOT))
    }

    @Test
    fun `a third decimal is refused`() {
        assertThat(AmountInput.press("1.55", d5)).isEqualTo(AmountPress.Refused(AmountRefusal.TOO_MANY_DECIMALS))
    }

    @Test
    fun `backspace walks back, and on empty there is nothing to delete`() {
        assertThat(type(AmountKey.Backspace, from = "12.5")).isEqualTo("12.")
        assertThat(type(AmountKey.Backspace, from = "12.")).isEqualTo("12")
        assertThat(type(AmountKey.Backspace, from = "1")).isEqualTo("")
        assertThat(AmountInput.press("", AmountKey.Backspace)).isEqualTo(AmountPress.Refused(AmountRefusal.NOTHING_TO_DELETE))
    }

    @Test
    fun `the upper limit is exact to the paisa`() {
        assertThat(AmountInput.press("10000", d0)).isEqualTo(AmountPress.Accepted("100000"))
        assertThat(AmountInput.press("100000", d0)).isEqualTo(AmountPress.Refused(AmountRefusal.TOO_LARGE))
        assertThat(AmountInput.press("100000", AmountKey.Dot)).isEqualTo(AmountPress.Accepted("100000."))
        assertThat(AmountInput.press("100000.", d0)).isEqualTo(AmountPress.Accepted("100000.0"))
        assertThat(AmountInput.press("100000.0", d0)).isEqualTo(AmountPress.Accepted("100000.00"))
        assertThat(AmountInput.press("100000.0", d1)).isEqualTo(AmountPress.Refused(AmountRefusal.TOO_LARGE))
        assertThat(AmountInput.press("99999.9", AmountKey.digit(9))).isEqualTo(AmountPress.Accepted("99999.99"))
    }

    @Test
    fun `every reachable keypad state parses to a paise value inside the limit or to nothing yet`() {
        // Breadth-first over keypad presses, bounded by depth: exact at every state, with no floating point anywhere.
        val keys = AmountKey.entries
        var frontier = setOf("")
        val seen = HashSet<String>()
        repeat(6) {
            val next = HashSet<String>()
            for (text in frontier) for (key in keys) {
                val r = AmountInput.press(text, key)
                if (r is AmountPress.Accepted && seen.add(r.text)) next += r.text
            }
            frontier = next
        }
        assertThat(seen.size).isGreaterThan(1_000)
        for (text in seen) {
            val paise = AmountInput.paiseOf(text)
            if (text.isEmpty()) assertThat(paise).isNull()
            else assertThat(paise!!).isAtMost(Payment.MAX_AMOUNT_PAISE)
            assertThat(paise ?: 0L).isAtLeast(0L)
        }
    }

    @Test
    fun `paise of a typed amount is exact, including a trailing dot`() {
        assertThat(AmountInput.paiseOf("12.")).isEqualTo(1_200)
        assertThat(AmountInput.paiseOf("12.5")).isEqualTo(1_250)
        assertThat(AmountInput.paiseOf("0.07")).isEqualTo(7)
        assertThat(AmountInput.paiseOf("99999.99")).isEqualTo(99_999_99)
        assertThat(AmountInput.paiseOf("")).isNull()
        assertThat(AmountInput.paiseOf(".")).isNull()
        assertThat(AmountInput.paiseOf("abc")).isNull()
    }

    @Test
    fun `a typed amount shows decimals only once a dot exists`() {
        assertThat(AmountInput.showsDecimals("12")).isFalse()
        assertThat(AmountInput.showsDecimals("12.")).isTrue()
        assertThat(AmountInput.showsDecimals("12.5")).isTrue()
    }
}

class PayReadinessTest {
    private val ready = PayUiState(vpa = "alice@okbank", name = "Alice Kumar", amountText = "10", verification = VpaVerificationState.Verified("Alice Kumar"), paymentMode = PaymentMode.SANDBOX)

    @Test
    fun `a verified payee and an amount inside the limit is ready`() {
        assertThat(PayReadiness.blockOf(ready)).isNull()
        assertThat(PayReadiness.blockOf(ready.copy(amountText = "0.01"))).isNull()
        assertThat(PayReadiness.blockOf(ready.copy(amountText = "100000"))).isNull()
    }

    @Test
    fun `amount blocks at each edge`() {
        assertThat(PayReadiness.blockOf(ready.copy(amountText = ""))).isEqualTo(PayBlock.ENTER_AMOUNT)
        assertThat(PayReadiness.blockOf(ready.copy(amountText = "0"))).isEqualTo(PayBlock.ENTER_AMOUNT)
        assertThat(PayReadiness.blockOf(ready.copy(amountText = "0.00"))).isEqualTo(PayBlock.ENTER_AMOUNT)
        assertThat(PayReadiness.blockOf(ready.copy(amountText = "100000.01"))).isEqualTo(PayBlock.AMOUNT_TOO_LARGE)
        // A locked amount never comes from the keypad: the largest displayable value must still not get through.
        assertThat(PayReadiness.blockOf(ready.copy(amountText = "999999999.99", amountLocked = true))).isEqualTo(PayBlock.AMOUNT_TOO_LARGE)
    }

    @Test
    fun `sandbox waits for the directory so the registered name is on screen before any swipe`() {
        assertThat(PayReadiness.blockOf(ready.copy(verification = VpaVerificationState.Idle))).isEqualTo(PayBlock.CHECKING_PAYEE)
        assertThat(PayReadiness.blockOf(ready.copy(verification = VpaVerificationState.Verifying))).isEqualTo(PayBlock.CHECKING_PAYEE)
        assertThat(PayReadiness.blockOf(ready.copy(verification = VpaVerificationState.NotFound))).isEqualTo(PayBlock.PAYEE_NOT_FOUND)
        assertThat(PayReadiness.blockOf(ready.copy(verification = VpaVerificationState.Unavailable))).isNull()
        assertThat(PayReadiness.blockOf(ready.copy(verification = VpaVerificationState.Unavailable, name = " "))).isEqualTo(PayBlock.ENTER_NAME)
    }

    @Test
    fun `upi apps mode has no directory and the name is optional`() {
        val real = ready.copy(name = "", paymentMode = PaymentMode.UPI_APPS, verification = VpaVerificationState.Skipped)
        assertThat(PayReadiness.blockOf(real)).isNull()
    }

    @Test
    fun `an unread mode, a missing and a bad payee block`() {
        assertThat(PayReadiness.blockOf(ready.copy(paymentMode = null))).isEqualTo(PayBlock.CHECKING_PAYEE)
        assertThat(PayReadiness.blockOf(ready.copy(vpa = ""))).isEqualTo(PayBlock.ENTER_PAYEE)
        assertThat(PayReadiness.blockOf(ready.copy(vpa = "not a vpa"))).isEqualTo(PayBlock.INVALID_PAYEE)
    }
}

class PayBackPolicyTest {
    private val running = listOf<PayPhase>(
        PayPhase.Submitting,
        PayPhase.Confirming("p"),
        PayPhase.ChoosingApp(listOf(UpiApp("a", "A")), picked = "a"),
    )

    @Test
    fun `a running payment blocks back until the limit, then the way out opens`() {
        for (phase in running) {
            assertThat(PayBackPolicy.blocksBack(phase, 0)).isTrue()
            assertThat(PayBackPolicy.blocksBack(phase, PayBackPolicy.BLOCK_LIMIT_MS - 1)).isTrue()
            assertThat(PayBackPolicy.blocksBack(phase, PayBackPolicy.BLOCK_LIMIT_MS)).isFalse()
        }
    }

    @Test
    fun `nothing else blocks back, so no phase can trap the user`() {
        val free = listOf(
            PayPhase.Idle,
            PayPhase.ChoosingApp(listOf(UpiApp("a", "A"))),
            PayPhase.AwaitingApp("p"),
            PayPhase.Unresolved("p"),
            PayPhase.Done("p", PaymentStatus.PENDING, null),
            PayPhase.Done("p", PaymentStatus.SUCCESS, null),
        )
        for (phase in free) assertThat(PayBackPolicy.blocksBack(phase, 0)).isFalse()
    }

    @Test
    fun `long waits change the words at the same moment the way out opens`() {
        assertThat(ProcessingStage.at(0)).isEqualTo(ProcessingStage.NORMAL)
        assertThat(ProcessingStage.at(ProcessingStage.STILL_CHECKING_MS - 1)).isEqualTo(ProcessingStage.NORMAL)
        assertThat(ProcessingStage.at(ProcessingStage.STILL_CHECKING_MS)).isEqualTo(ProcessingStage.STILL_CHECKING)
        assertThat(ProcessingStage.at(PayBackPolicy.BLOCK_LIMIT_MS - 1)).isEqualTo(ProcessingStage.STILL_CHECKING)
        assertThat(ProcessingStage.at(PayBackPolicy.BLOCK_LIMIT_MS)).isEqualTo(ProcessingStage.BANK_SLOW)
    }
}

class PayOutcomesTest {
    private fun done(status: PaymentStatus, source: StatusSource = StatusSource.PSP, handler: String? = null, reason: String? = null): PayPhase.Done {
        val payment = testPayment("p1", status = status).copy(statusSource = source, handlerPackage = handler)
        return PayPhase.Done("p1", status, reason, payment, handler?.let { "GPay" })
    }

    @Test
    fun `only a provider-confirmed success gets the full celebration`() {
        val o = PayOutcomes.of(done(PaymentStatus.SUCCESS))
        assertThat(o.kind).isEqualTo(OutcomeKind.SUCCESS)
        assertThat(o.celebration).isEqualTo(Celebration.FULL)
        assertThat(o.haptic).isEqualTo(HapticEvent.Confirm)
        assertThat(o.sound).isEqualTo(com.darshan.payflow.ui.designsystem.effects.UiSoundEvent.Success)
    }

    @Test
    fun `a success the upi app reported is a quiet check, never the burst or sheen`() {
        val o = PayOutcomes.of(done(PaymentStatus.SUCCESS, StatusSource.UPI_APP, "com.example.gpay"))
        assertThat(o.kind).isEqualTo(OutcomeKind.REPORTED_SUCCESS)
        assertThat(o.celebration).isEqualTo(Celebration.QUIET)
        assertThat(o.sound).isNull() // a success PayFlow can't verify doesn't chime
    }

    @Test
    fun `a payment the user marked has no celebration at all`() {
        val paid = PayOutcomes.of(done(PaymentStatus.SUCCESS, StatusSource.USER, "com.example.gpay"))
        assertThat(paid.kind).isEqualTo(OutcomeKind.MARKED_PAID)
        assertThat(paid.celebration).isEqualTo(Celebration.NONE)
        assertThat(paid.haptic).isNull()
        val notPaid = PayOutcomes.of(done(PaymentStatus.FAILED, StatusSource.USER, "com.example.gpay"))
        assertThat(notPaid.kind).isEqualTo(OutcomeKind.MARKED_NOT_PAID)
        assertThat(notPaid.celebration).isEqualTo(Celebration.NONE)
    }

    @Test
    fun `pending, unknown, mismatch and refunds never celebrate`() {
        for (phase in listOf(
            done(PaymentStatus.PENDING),
            done(PaymentStatus.CREATED),
            done(PaymentStatus.PENDING, StatusSource.UPI_APP, "com.example.gpay"),
            done(PaymentStatus.PENDING, StatusSource.UPI_APP, "com.example.gpay", reason = PayViewModel.MISMATCH_REASON),
            done(PaymentStatus.SUCCESS, StatusSource.UPI_APP, "com.example.gpay", reason = PayViewModel.MISMATCH_REASON),
            done(PaymentStatus.REFUND_PENDING),
            done(PaymentStatus.REFUNDED),
            done(PaymentStatus.FAILED),
        )) {
            val o = PayOutcomes.of(phase)
            assertThat(o.celebration).isEqualTo(Celebration.NONE)
            assertThat(o.haptic).isNotEqualTo(HapticEvent.Confirm)
            assertThat(o.sound).isNull()
        }
    }

    @Test
    fun `a success with no stored record never celebrates`() {
        assertThat(PayOutcomes.of(PayPhase.Done("p", PaymentStatus.SUCCESS, null)).celebration).isEqualTo(Celebration.NONE)
    }

    @Test
    fun `the record's status wins over the phase's, so the screen cannot disagree with the row`() {
        val refunded = testPayment("p1", status = PaymentStatus.REFUNDED)
        val o = PayOutcomes.of(PayPhase.Done("p1", PaymentStatus.SUCCESS, null, refunded))
        assertThat(o.kind).isEqualTo(OutcomeKind.REFUND)
    }

    @Test
    fun `failures reject, and each failure says who decided`() {
        assertThat(PayOutcomes.of(done(PaymentStatus.FAILED)).kind).isEqualTo(OutcomeKind.FAILED)
        assertThat(PayOutcomes.of(done(PaymentStatus.FAILED)).haptic).isEqualTo(HapticEvent.Reject)
        assertThat(PayOutcomes.of(done(PaymentStatus.FAILED, StatusSource.UPI_APP, "com.example.gpay")).kind).isEqualTo(OutcomeKind.REPORTED_FAILURE)
    }
}
