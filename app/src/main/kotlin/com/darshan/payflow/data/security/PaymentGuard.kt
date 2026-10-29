package com.darshan.payflow.data.security

import javax.inject.Inject
import javax.inject.Singleton

sealed interface GuardDecision {
    data object Allow : GuardDecision
    data class Warn(val reasons: List<GuardReason>) : GuardDecision
    data class Block(val reasons: List<GuardReason>) : GuardDecision
}

enum class GuardReason { ROOTED, TAMPERED_SIGNATURE, HOOKING_FRAMEWORK, DEBUGGER, INTEGRITY_FAILED, INTEGRITY_UNVERIFIED, DEVICE_NOT_BOUND }

/** Runs the device checks that gate a payment: root/tamper scan, Play Integrity verdict and device binding. */
interface PaymentGuard {
    suspend fun evaluate(paymentId: String, amountPaise: Long, payeeVpa: String): GuardDecision
}

@Singleton
class DefaultPaymentGuard @Inject constructor(
    private val tamper: TamperDetector,
    private val integrity: IntegrityGate,
    private val binding: DeviceBindingManager,
) : PaymentGuard {
    override suspend fun evaluate(paymentId: String, amountPaise: Long, payeeVpa: String): GuardDecision {
        val block = ArrayList<GuardReason>()
        val warn = ArrayList<GuardReason>()
        val report = tamper.scan()
        if (report.findings.any { it == TamperFinding.SU_BINARY || it == TamperFinding.MAGISK }) block += GuardReason.ROOTED
        if (report.findings.any { it == TamperFinding.FRIDA_PORT || it == TamperFinding.FRIDA_LIBRARY }) block += GuardReason.HOOKING_FRAMEWORK
        if (TamperFinding.SIGNATURE_MISMATCH in report.findings) block += GuardReason.TAMPERED_SIGNATURE
        if (TamperFinding.DEBUGGER in report.findings) warn += GuardReason.DEBUGGER
        when (integrity.check(paymentId, amountPaise, payeeVpa)) {
            is IntegrityDecision.Block -> block += GuardReason.INTEGRITY_FAILED
            is IntegrityDecision.AllowWithWarning -> warn += GuardReason.INTEGRITY_UNVERIFIED
            is IntegrityDecision.Allow, IntegrityDecision.Skipped -> Unit
        }
        if (binding.status() !is BindingStatus.Bound) warn += GuardReason.DEVICE_NOT_BOUND
        return when {
            block.isNotEmpty() -> GuardDecision.Block(block)
            warn.isNotEmpty() -> GuardDecision.Warn(warn)
            else -> GuardDecision.Allow
        }
    }
}

/** Test/sandbox guard that always allows. */
class AllowAllPaymentGuard : PaymentGuard {
    override suspend fun evaluate(paymentId: String, amountPaise: Long, payeeVpa: String): GuardDecision = GuardDecision.Allow
}
