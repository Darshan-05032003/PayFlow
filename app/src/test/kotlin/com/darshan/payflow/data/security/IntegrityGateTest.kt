package com.darshan.payflow.data.security

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class IntegrityGateTest {
    private class FakeChecker(val result: IntegrityResult) : IntegrityChecker {
        var requests = mutableListOf<String>()
        override val isConfigured: Boolean get() = result !is IntegrityResult.NotConfigured
        override suspend fun requestToken(requestHash: String): IntegrityResult { requests += requestHash; return result }
    }

    @Test
    fun `request hash binds the token to the payment and never contains raw details`() {
        val h1 = IntegrityGate.requestHash(paymentId = "p1", amountPaise = 12_300, payeeVpa = "alice@okbank")
        val h2 = IntegrityGate.requestHash(paymentId = "p1", amountPaise = 12_300, payeeVpa = "alice@okbank")
        val h3 = IntegrityGate.requestHash(paymentId = "p1", amountPaise = 12_301, payeeVpa = "alice@okbank")
        assertThat(h1).isEqualTo(h2)
        assertThat(h1).isNotEqualTo(h3)
        assertThat(h1).doesNotContain("alice")
        assertThat(h1.length).isAtMost(500) // Play Integrity limit
    }

    @Test
    fun `a verified token that meets device integrity allows the payment`() = runTest {
        val checker = FakeChecker(IntegrityResult.Token("tok"))
        val gate = IntegrityGate(checker, FakeIntegrityVerifier(IntegrityVerdict.MEETS_DEVICE_INTEGRITY))
        assertThat(gate.check("p1", 100, "a@b")).isEqualTo(IntegrityDecision.Allow(IntegrityVerdict.MEETS_DEVICE_INTEGRITY))
        assertThat(checker.requests).hasSize(1)
    }

    @Test
    fun `a failing verdict blocks, an unevaluated one allows with a warning`() = runTest {
        assertThat(IntegrityGate(FakeChecker(IntegrityResult.Token("t")), FakeIntegrityVerifier(IntegrityVerdict.FAILED)).check("p", 1, "a@b"))
            .isEqualTo(IntegrityDecision.Block(IntegrityVerdict.FAILED))
        assertThat(IntegrityGate(FakeChecker(IntegrityResult.Token("t")), FakeIntegrityVerifier(IntegrityVerdict.UNEVALUATED)).check("p", 1, "a@b"))
            .isEqualTo(IntegrityDecision.AllowWithWarning(IntegrityReason.UNEVALUATED))
    }

    @Test
    fun `not configured skips, play services errors are a warning not a block`() = runTest {
        val skipped = IntegrityGate(FakeChecker(IntegrityResult.NotConfigured), FakeIntegrityVerifier(IntegrityVerdict.FAILED)).check("p", 1, "a@b")
        assertThat(skipped).isEqualTo(IntegrityDecision.Skipped)
        val failed = IntegrityGate(FakeChecker(IntegrityResult.Error("API_NOT_AVAILABLE")), FakeIntegrityVerifier(IntegrityVerdict.MEETS_DEVICE_INTEGRITY)).check("p", 1, "a@b")
        assertThat(failed).isEqualTo(IntegrityDecision.AllowWithWarning(IntegrityReason.REQUEST_FAILED))
    }

    @Test
    fun `tokens are opaque to the client - the fake verifier is explicit about being a sandbox stand in`() = runTest {
        val verifier = FakeIntegrityVerifier(IntegrityVerdict.MEETS_DEVICE_INTEGRITY)
        assertThat(verifier.verify("anything")).isEqualTo(IntegrityVerdict.MEETS_DEVICE_INTEGRITY)
        assertThat(verifier.isSandbox).isTrue()
    }
}
