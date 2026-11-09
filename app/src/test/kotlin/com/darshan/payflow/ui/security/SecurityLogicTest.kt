package com.darshan.payflow.ui.security

import com.darshan.payflow.data.security.BindingStatus
import com.darshan.payflow.data.security.BiometricAvailability
import com.darshan.payflow.data.security.IntegrityVerdict
import com.darshan.payflow.data.security.TamperFinding
import com.darshan.payflow.data.security.TamperReport
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SecurityLogicTest {
    private val allGood = SecurityUiState(
        loading = false,
        pinConfigured = true,
        binding = BindingStatus.Bound("fp", "+91 ****3210"),
        tamper = TamperReport.CLEAN,
        integrity = IntegrityStatus.Result(IntegrityVerdict.MEETS_DEVICE_INTEGRITY, sandboxVerifier = false),
        biometric = BiometricAvailability.AVAILABLE,
    )

    private fun SecurityUiState.check(id: CheckId) = SecurityChecks.of(this).single { it.id == id }

    @Test
    fun `the order is binding, device, integrity, app lock, biometric`() {
        assertThat(SecurityChecks.of(allGood).map { it.id })
            .containsExactly(CheckId.BINDING, CheckId.DEVICE, CheckId.INTEGRITY, CheckId.APP_LOCK, CheckId.BIOMETRIC).inOrder()
        assertThat(SecurityChecks.of(allGood).map { it.verdict }.toSet()).containsExactly(CheckVerdict.Passed)
    }

    @Test
    fun `nothing reads as passed before it was checked`() {
        val fresh = SecurityUiState()
        SecurityChecks.of(fresh).forEach { assertThat(it.verdict).isEqualTo(CheckVerdict.Checking) }
        assertThat(allGood.copy(integrity = IntegrityStatus.NotChecked).check(CheckId.INTEGRITY).verdict).isEqualTo(CheckVerdict.Checking)
        assertThat(allGood.copy(integrity = IntegrityStatus.Checking).check(CheckId.INTEGRITY).verdict).isEqualTo(CheckVerdict.Checking)
        assertThat(allGood.copy(tamper = null).check(CheckId.DEVICE).verdict).isEqualTo(CheckVerdict.Checking)
        assertThat(allGood.copy(biometric = null).check(CheckId.BIOMETRIC).verdict).isEqualTo(CheckVerdict.Checking)
    }

    @Test
    fun `a sandbox verdict or a missing configuration is never a pass`() {
        val sandbox = allGood.copy(integrity = IntegrityStatus.Result(IntegrityVerdict.MEETS_DEVICE_INTEGRITY, sandboxVerifier = true)).check(CheckId.INTEGRITY)
        assertThat(sandbox.verdict).isEqualTo(CheckVerdict.Info)
        assertThat(sandbox.detail).isEqualTo(CheckDetail.IntegritySandbox)
        val unconfigured = allGood.copy(integrity = IntegrityStatus.NotConfigured).check(CheckId.INTEGRITY)
        assertThat(unconfigured.verdict).isEqualTo(CheckVerdict.Info)
        assertThat(unconfigured.detail).isEqualTo(CheckDetail.IntegrityNotConfigured)
        assertThat(allGood.copy(integrity = IntegrityStatus.Error("API_NOT_AVAILABLE")).check(CheckId.INTEGRITY).verdict).isEqualTo(CheckVerdict.Attention)
        assertThat(allGood.copy(integrity = IntegrityStatus.Result(IntegrityVerdict.UNEVALUATED, false)).check(CheckId.INTEGRITY).verdict).isEqualTo(CheckVerdict.Attention)
        assertThat(allGood.copy(integrity = IntegrityStatus.Result(IntegrityVerdict.FAILED, true)).check(CheckId.INTEGRITY).verdict).isEqualTo(CheckVerdict.Failed)
    }

    @Test
    fun `a debug build is named, not passed, and root blocks`() {
        val debug = allGood.copy(tamper = TamperReport(listOf(TamperFinding.DEBUGGABLE))).check(CheckId.DEVICE)
        assertThat(debug.verdict).isEqualTo(CheckVerdict.Info)
        assertThat(debug.detail).isEqualTo(CheckDetail.DeviceDebug)
        // A debugger attached or a test-keys build is a real warning, even though payments still go through.
        val debugger = allGood.copy(tamper = TamperReport(listOf(TamperFinding.DEBUGGABLE, TamperFinding.DEBUGGER))).check(CheckId.DEVICE)
        assertThat(debugger.verdict).isEqualTo(CheckVerdict.Attention)
        assertThat(debugger.detail).isEqualTo(CheckDetail.DeviceWarnings)
        val rooted = allGood.copy(tamper = TamperReport(listOf(TamperFinding.MAGISK, TamperFinding.DEBUGGABLE))).check(CheckId.DEVICE)
        assertThat(rooted.verdict).isEqualTo(CheckVerdict.Failed)
        assertThat(rooted.detail).isEqualTo(CheckDetail.DeviceBlocked)
    }

    @Test
    fun `binding, app lock and biometric states`() {
        assertThat(allGood.copy(binding = BindingStatus.Unbound).check(CheckId.BINDING).verdict).isEqualTo(CheckVerdict.Attention)
        assertThat(allGood.copy(binding = BindingStatus.OtpPending("+91 ****3210")).check(CheckId.BINDING).verdict).isEqualTo(CheckVerdict.Attention)
        assertThat(allGood.copy(binding = BindingStatus.Mismatch("other", "+91 ****3210")).check(CheckId.BINDING).verdict).isEqualTo(CheckVerdict.Failed)
        assertThat(allGood.copy(loading = true).check(CheckId.BINDING).verdict).isEqualTo(CheckVerdict.Checking)
        assertThat(allGood.copy(pinConfigured = false).check(CheckId.APP_LOCK).verdict).isEqualTo(CheckVerdict.Attention)
        // A fingerprint can't unlock an app that has no lock.
        val noPin = allGood.copy(pinConfigured = false).check(CheckId.BIOMETRIC)
        assertThat(noPin.verdict).isEqualTo(CheckVerdict.Attention)
        assertThat(noPin.detail).isEqualTo(CheckDetail.BiometricNeedsPin)
        assertThat(allGood.copy(biometric = BiometricAvailability.NONE_ENROLLED).check(CheckId.BIOMETRIC).verdict).isEqualTo(CheckVerdict.Info)
        assertThat(allGood.copy(biometric = BiometricAvailability.NO_HARDWARE).check(CheckId.BIOMETRIC).verdict).isEqualTo(CheckVerdict.Info)
    }

    @Test
    fun `the summary guides rather than alarms, and says when payments are off`() {
        assertThat(SecurityChecks.summary(SecurityChecks.of(allGood))).isEqualTo(SecuritySummary.AllClear)
        val fresh = SecurityChecks.of(SecurityUiState())
        assertThat(SecurityChecks.summary(fresh)).isEqualTo(SecuritySummary.Checking)
        val todo = SecurityChecks.of(allGood.copy(pinConfigured = false, binding = BindingStatus.Unbound))
        assertThat(SecurityChecks.summary(todo)).isEqualTo(SecuritySummary.NeedsYou(3)) // lock, binding and the biometric that needs the lock
        val rooted = SecurityChecks.of(allGood.copy(tamper = TamperReport(listOf(TamperFinding.SU_BINARY))))
        assertThat(SecurityChecks.summary(rooted)).isEqualTo(SecuritySummary.PaymentsOff)
        val failedIntegrity = SecurityChecks.of(allGood.copy(integrity = IntegrityStatus.Result(IntegrityVerdict.FAILED, true)))
        assertThat(SecurityChecks.summary(failedIntegrity)).isEqualTo(SecuritySummary.PaymentsOff)
        // Info rows (sandbox, not configured) are honest notes, not chores.
        val sandbox = SecurityChecks.of(allGood.copy(integrity = IntegrityStatus.NotConfigured, biometric = BiometricAvailability.NO_HARDWARE))
        assertThat(SecurityChecks.summary(sandbox)).isEqualTo(SecuritySummary.ClearWithNotes(2))
    }

    @Test
    fun `one next step, the most important first`() {
        assertThat(SecurityChecks.nextStep(SecurityChecks.of(allGood))).isNull()
        val todo = SecurityChecks.of(allGood.copy(pinConfigured = false, binding = BindingStatus.Unbound))
        assertThat(SecurityChecks.nextStep(todo)).isEqualTo(NextStep.SetUpLock)
        assertThat(SecurityChecks.nextStep(SecurityChecks.of(allGood.copy(binding = BindingStatus.Unbound)))).isEqualTo(NextStep.BindPhone)
        assertThat(SecurityChecks.nextStep(SecurityChecks.of(allGood.copy(binding = BindingStatus.Mismatch("other", "x"))))).isEqualTo(NextStep.BindPhone)
        val rooted = SecurityChecks.of(allGood.copy(pinConfigured = false, tamper = TamperReport(listOf(TamperFinding.FRIDA_PORT))))
        assertThat(SecurityChecks.nextStep(rooted)).isEqualTo(NextStep.ExplainBlock)
        assertThat(SecurityChecks.nextStep(SecurityChecks.of(allGood.copy(integrity = IntegrityStatus.Error("X"))))).isEqualTo(NextStep.CheckIntegrity)
        assertThat(SecurityChecks.nextStep(SecurityChecks.of(SecurityUiState()))).isNull()
    }

    @Test
    fun `reveal - in sequence, each row only once its own result is in`() {
        val reveal = CheckReveal(stepMs = 300)
        val resolved = mutableListOf(true, true, false, true, true)
        assertThat(reveal.advance(nowMs = 0, resolved)).isTrue()
        assertThat(reveal.revealed).isEqualTo(1)
        assertThat(reveal.advance(nowMs = 100, resolved)).isFalse()
        reveal.advance(nowMs = 300, resolved)
        assertThat(reveal.revealed).isEqualTo(2)
        // Row 3 isn't known yet: the sequence waits for it, and nothing after it jumps ahead.
        reveal.advance(nowMs = 2_000, resolved)
        assertThat(reveal.revealed).isEqualTo(2)
        assertThat(CheckReveal.shown(3, reveal.revealed, resolved, reduced = false)).isFalse()
        resolved[2] = true
        reveal.advance(nowMs = 2_100, resolved)
        assertThat(reveal.revealed).isEqualTo(3)
        reveal.advance(nowMs = 2_400, resolved); reveal.advance(nowMs = 2_700, resolved)
        assertThat(reveal.revealed).isEqualTo(5)
        assertThat(reveal.done(5)).isTrue()
    }

    @Test
    fun `reveal - reduced motion shows every final state at once, and still never an unknown one`() {
        val resolved = listOf(true, false, true, true, true)
        assertThat((0..4).map { CheckReveal.shown(it, revealed = 0, resolved = resolved, reduced = true) })
            .containsExactly(true, false, true, true, true).inOrder()
    }
}
