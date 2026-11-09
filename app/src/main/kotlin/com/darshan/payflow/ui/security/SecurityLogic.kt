package com.darshan.payflow.ui.security

import androidx.compose.runtime.Immutable
import com.darshan.payflow.data.security.BindingStatus
import com.darshan.payflow.data.security.BiometricAvailability
import com.darshan.payflow.data.security.IntegrityVerdict
import com.darshan.payflow.data.security.TamperFinding

enum class CheckId { BINDING, DEVICE, INTEGRITY, APP_LOCK, BIOMETRIC }

/**
 * What a row may show. Only [Passed] earns a tick, and only for something that really ran and passed. [Info] is the honest
 * neutral: sandbox verdicts, a build with no Play Integrity project, a phone without a fingerprint sensor.
 */
enum class CheckVerdict { Checking, Passed, Attention, Failed, Info }

enum class CheckDetail {
    Checking,
    BindingBound, BindingUnbound, BindingOtpPending, BindingMismatch,
    DeviceClean, DeviceDebug, DeviceWarnings, DeviceBlocked,
    IntegrityVerified, IntegritySandbox, IntegrityNotConfigured, IntegrityUnevaluated, IntegrityError, IntegrityFailed,
    AppLockOn, AppLockOff,
    BiometricReady, BiometricNeedsPin, BiometricNotEnrolled, BiometricUnavailable,
}

@Immutable
data class SecurityCheck(val id: CheckId, val verdict: CheckVerdict, val detail: CheckDetail)

sealed interface SecuritySummary {
    data object Checking : SecuritySummary
    data object AllClear : SecuritySummary
    /** Nothing to do, but some rows are notes (sandbox, not configured, a debug build), so "everything checks out" would overstate it. */
    data class ClearWithNotes(val notes: Int) : SecuritySummary
    data class NeedsYou(val count: Int) : SecuritySummary
    /** A finding the payment guard blocks on: root, hooking, a re-signed app, a failed integrity verdict. */
    data object PaymentsOff : SecuritySummary
}

/** The single primary action the centre offers, most important first. */
enum class NextStep { ExplainBlock, SetUpLock, BindPhone, CheckIntegrity }

/** Turns the security layer's real answers into the rows of the security centre. Pure, so the honesty rules are tested. */
object SecurityChecks {
    fun of(state: SecurityUiState): List<SecurityCheck> = listOf(binding(state), device(state), integrity(state), appLock(state), biometric(state))

    private fun checking(id: CheckId) = SecurityCheck(id, CheckVerdict.Checking, CheckDetail.Checking)

    private fun binding(state: SecurityUiState): SecurityCheck {
        if (state.loading) return checking(CheckId.BINDING)
        return when (state.binding) {
            is BindingStatus.Bound -> SecurityCheck(CheckId.BINDING, CheckVerdict.Passed, CheckDetail.BindingBound)
            BindingStatus.Unbound -> SecurityCheck(CheckId.BINDING, CheckVerdict.Attention, CheckDetail.BindingUnbound)
            is BindingStatus.OtpPending -> SecurityCheck(CheckId.BINDING, CheckVerdict.Attention, CheckDetail.BindingOtpPending)
            is BindingStatus.Mismatch -> SecurityCheck(CheckId.BINDING, CheckVerdict.Failed, CheckDetail.BindingMismatch)
        }
    }

    private fun device(state: SecurityUiState): SecurityCheck {
        val report = state.tamper ?: return checking(CheckId.DEVICE)
        return when {
            report.shouldBlockPayments -> SecurityCheck(CheckId.DEVICE, CheckVerdict.Failed, CheckDetail.DeviceBlocked)
            // A debug build always carries the debuggable flag. That's a fact about the build, not a chore for the user.
            report.findings.all { it == TamperFinding.DEBUGGABLE } && report.findings.isNotEmpty() ->
                SecurityCheck(CheckId.DEVICE, CheckVerdict.Info, CheckDetail.DeviceDebug)
            report.findings.isNotEmpty() -> SecurityCheck(CheckId.DEVICE, CheckVerdict.Attention, CheckDetail.DeviceWarnings)
            else -> SecurityCheck(CheckId.DEVICE, CheckVerdict.Passed, CheckDetail.DeviceClean)
        }
    }

    private fun integrity(state: SecurityUiState): SecurityCheck = when (val s = state.integrity) {
        IntegrityStatus.NotChecked, IntegrityStatus.Checking -> checking(CheckId.INTEGRITY)
        IntegrityStatus.NotConfigured -> SecurityCheck(CheckId.INTEGRITY, CheckVerdict.Info, CheckDetail.IntegrityNotConfigured)
        is IntegrityStatus.Error -> SecurityCheck(CheckId.INTEGRITY, CheckVerdict.Attention, CheckDetail.IntegrityError)
        is IntegrityStatus.Result -> when (s.verdict) {
            IntegrityVerdict.FAILED -> SecurityCheck(CheckId.INTEGRITY, CheckVerdict.Failed, CheckDetail.IntegrityFailed)
            IntegrityVerdict.UNEVALUATED -> SecurityCheck(CheckId.INTEGRITY, CheckVerdict.Attention, CheckDetail.IntegrityUnevaluated)
            // A verdict nobody verified on a server is not a pass, however good it looks.
            IntegrityVerdict.MEETS_DEVICE_INTEGRITY ->
                if (s.sandboxVerifier) SecurityCheck(CheckId.INTEGRITY, CheckVerdict.Info, CheckDetail.IntegritySandbox)
                else SecurityCheck(CheckId.INTEGRITY, CheckVerdict.Passed, CheckDetail.IntegrityVerified)
        }
    }

    private fun appLock(state: SecurityUiState): SecurityCheck = when {
        state.loading -> checking(CheckId.APP_LOCK)
        state.pinConfigured -> SecurityCheck(CheckId.APP_LOCK, CheckVerdict.Passed, CheckDetail.AppLockOn)
        else -> SecurityCheck(CheckId.APP_LOCK, CheckVerdict.Attention, CheckDetail.AppLockOff)
    }

    private fun biometric(state: SecurityUiState): SecurityCheck {
        if (state.loading) return checking(CheckId.BIOMETRIC)
        return when (state.biometric ?: return checking(CheckId.BIOMETRIC)) {
            BiometricAvailability.AVAILABLE ->
                if (state.pinConfigured) SecurityCheck(CheckId.BIOMETRIC, CheckVerdict.Passed, CheckDetail.BiometricReady)
                else SecurityCheck(CheckId.BIOMETRIC, CheckVerdict.Attention, CheckDetail.BiometricNeedsPin)
            BiometricAvailability.NONE_ENROLLED -> SecurityCheck(CheckId.BIOMETRIC, CheckVerdict.Info, CheckDetail.BiometricNotEnrolled)
            BiometricAvailability.NO_HARDWARE, BiometricAvailability.UNAVAILABLE ->
                SecurityCheck(CheckId.BIOMETRIC, CheckVerdict.Info, CheckDetail.BiometricUnavailable)
        }
    }

    private val BLOCKING = setOf(CheckDetail.DeviceBlocked, CheckDetail.IntegrityFailed)

    fun summary(checks: List<SecurityCheck>): SecuritySummary = when {
        checks.any { it.detail in BLOCKING } -> SecuritySummary.PaymentsOff
        checks.any { it.verdict == CheckVerdict.Checking } -> SecuritySummary.Checking
        else -> {
            val todo = checks.count { it.verdict == CheckVerdict.Attention || it.verdict == CheckVerdict.Failed }
            val notes = checks.count { it.verdict == CheckVerdict.Info }
            when {
                todo > 0 -> SecuritySummary.NeedsYou(todo)
                notes > 0 -> SecuritySummary.ClearWithNotes(notes)
                else -> SecuritySummary.AllClear
            }
        }
    }

    fun nextStep(checks: List<SecurityCheck>): NextStep? {
        val details = checks.map { it.detail }.toSet()
        return when {
            details.any { it in BLOCKING } -> NextStep.ExplainBlock
            CheckDetail.AppLockOff in details -> NextStep.SetUpLock
            CheckDetail.BindingUnbound in details || CheckDetail.BindingMismatch in details -> NextStep.BindPhone
            CheckDetail.IntegrityError in details || CheckDetail.IntegrityUnevaluated in details -> NextStep.CheckIntegrity
            else -> null
        }
    }
}

/**
 * The order the checks tick in. A row is shown resolved only once its own answer is in and every row above it has ticked,
 * one [stepMs] apart; under reduced motion each row simply shows its final state the moment it's known.
 */
class CheckReveal(private val stepMs: Long = STEP_MS, initial: Int = 0) {
    /** Rows already ticked; a sequence restarted after reduced motion was switched off carries on from here. */
    var revealed: Int = initial
        private set
    private var lastAt: Long? = null

    /** Moves the sequence on to [nowMs]. True when another row was revealed. */
    fun advance(nowMs: Long, resolved: List<Boolean>): Boolean {
        if (revealed >= resolved.size || !resolved[revealed]) return false
        val last = lastAt
        if (last != null && nowMs - last < stepMs) return false
        revealed++
        lastAt = nowMs
        return true
    }

    fun done(count: Int): Boolean = revealed >= count

    companion object {
        const val STEP_MS = 280L

        fun shown(index: Int, revealed: Int, resolved: List<Boolean>, reduced: Boolean): Boolean =
            resolved.getOrElse(index) { false } && (reduced || index < revealed)
    }
}
