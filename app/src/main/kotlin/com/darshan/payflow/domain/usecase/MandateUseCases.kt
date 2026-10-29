package com.darshan.payflow.domain.usecase

import com.darshan.payflow.data.psp.PspClient
import com.darshan.payflow.data.psp.PspException
import com.darshan.payflow.data.psp.PspMandateRequest
import com.darshan.payflow.data.psp.VpaVerification
import com.darshan.payflow.domain.model.ExecutionCheck
import com.darshan.payflow.domain.model.Mandate
import com.darshan.payflow.domain.model.MandateAction
import com.darshan.payflow.domain.model.MandateFrequency
import com.darshan.payflow.domain.model.MandateRules
import com.darshan.payflow.domain.model.MandateStateMachine
import com.darshan.payflow.domain.model.MandateStatus
import com.darshan.payflow.domain.model.MandateValidation
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentDirection
import com.darshan.payflow.domain.model.PaymentEvent
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.VpaValidation
import com.darshan.payflow.domain.model.VpaValidator
import com.darshan.payflow.domain.repository.MandateRepository
import com.darshan.payflow.domain.repository.PaymentRepository
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CreateMandateRequest(
    val payeeVpa: String,
    val payeeName: String,
    val maxAmountPaise: Long,
    val frequency: MandateFrequency,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val note: String,
)

sealed interface CreateMandateResult {
    data class Created(val mandate: Mandate) : CreateMandateResult
    data class Invalid(val validation: MandateValidation) : CreateMandateResult
    data class Unavailable(val error: PspException) : CreateMandateResult
}

/** Rules first (offline), payee verified at the PSP, then the mandate is registered and stored ACTIVE with its UMN. */
class CreateMandateUseCase(
    private val mandates: MandateRepository,
    private val psp: PspClient,
    private val clock: Clock,
    private val zone: ZoneId,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    @Inject constructor(mandates: MandateRepository, psp: PspClient, clock: Clock, zone: ZoneId) : this(mandates, psp, clock, zone, { UUID.randomUUID().toString() })

    suspend operator fun invoke(request: CreateMandateRequest): CreateMandateResult {
        val now = clock.instant()
        val today = now.atZone(zone).toLocalDate()
        val vpa = (VpaValidator.validate(request.payeeVpa) as? VpaValidation.Valid)?.normalized
            ?: return CreateMandateResult.Invalid(MandateValidation.InvalidPayee)
        val validation = MandateRules.validateCreation(request.maxAmountPaise, request.startDate, request.endDate, today)
        if (validation != MandateValidation.Ok) return CreateMandateResult.Invalid(validation)
        return try {
            val payeeName = when (val v = psp.verifyVpa(vpa)) {
                is VpaVerification.NotFound -> return CreateMandateResult.Invalid(MandateValidation.InvalidPayee)
                is VpaVerification.Verified -> v.payeeName
            }
            val registered = psp.createMandate(
                PspMandateRequest(vpa, payeeName, request.maxAmountPaise, request.frequency.name, request.startDate.toString(), request.endDate.toString(), request.note.take(99)),
            )
            val mandate = Mandate(
                id = idGenerator(), umn = registered.umn, payeeVpa = vpa, payeeName = payeeName, maxAmountPaise = request.maxAmountPaise,
                frequency = request.frequency, startDate = request.startDate, endDate = request.endDate, status = registered.status,
                note = request.note.take(99), createdAt = now, updatedAt = now, lastExecutedAt = null, executionCount = 0,
            )
            mandates.create(mandate)
            CreateMandateResult.Created(mandate)
        } catch (e: PspException) {
            CreateMandateResult.Unavailable(e)
        }
    }
}

sealed interface UpdateMandateResult {
    data class Updated(val mandate: Mandate) : UpdateMandateResult
    data class IllegalTransition(val from: MandateStatus, val action: MandateAction) : UpdateMandateResult
    data class Unavailable(val error: PspException) : UpdateMandateResult
    data object NotFound : UpdateMandateResult
}

/**
 * Pause / resume / revoke. Actions on one mandate are serialised with a per-mandate mutex so "read local → PSP → write
 * local" is atomic: without it, PAUSE and REVOKE racing could leave the PSP at REVOKED while the local CAS for REVOKE lost
 * (found by `MandateStressTest.thousandRacingLifecycleActionsStayConsistent`). The PSP's returned status is mirrored locally.
 */
@Singleton
class UpdateMandateUseCase @Inject constructor(
    private val mandates: MandateRepository,
    private val psp: PspClient,
    private val clock: Clock,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend operator fun invoke(id: String, action: MandateAction): UpdateMandateResult = locks.getOrPut(id) { Mutex() }.withLock {
        val mandate = mandates.getById(id) ?: return UpdateMandateResult.NotFound
        if (MandateStateMachine.transition(mandate.status, action) == null) return UpdateMandateResult.IllegalTransition(mandate.status, action)
        val remote = try {
            psp.updateMandate(mandate.umn, action.name)
        } catch (e: PspException) {
            // 409 = the PSP already holds a different state (another device raced us); re-read and report honestly.
            if (e is PspException.ClientError && e.status == 409) {
                val latest = mandates.getById(id) ?: return UpdateMandateResult.NotFound
                return UpdateMandateResult.IllegalTransition(latest.status, action)
            }
            return UpdateMandateResult.Unavailable(e)
        }
        val applied = mandates.compareAndSetStatus(id, mandate.status, remote.status, clock.instant())
        val latest = mandates.getById(id) ?: return UpdateMandateResult.NotFound
        return if (applied) UpdateMandateResult.Updated(latest) else UpdateMandateResult.IllegalTransition(latest.status, action)
    }
}

sealed interface ExecuteMandateResult {
    data class Executed(val payment: Payment) : ExecuteMandateResult
    data class Rejected(val check: ExecutionCheck) : ExecuteMandateResult
    data class Unavailable(val error: PspException) : ExecuteMandateResult
    data object NotFound : ExecuteMandateResult
}

/**
 * A debit presented under the mandate (simulated merchant). The period is reserved with a compare-and-set on the execution
 * counter *before* the PSP is called, so two racing debits can never both reach the PSP; a PSP failure releases the slot.
 */
class ExecuteMandateUseCase(
    private val mandates: MandateRepository,
    private val payments: PaymentRepository,
    private val psp: PspClient,
    private val clock: Clock,
    private val zone: ZoneId,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    @Inject constructor(mandates: MandateRepository, payments: PaymentRepository, psp: PspClient, clock: Clock, zone: ZoneId) :
        this(mandates, payments, psp, clock, zone, { UUID.randomUUID().toString() })

    private val confirm = ConfirmPaymentUseCase(payments, psp, clock)

    suspend operator fun invoke(mandateId: String, amountPaise: Long): ExecuteMandateResult {
        val now = clock.instant()
        val mandate = mandates.getById(mandateId) ?: return ExecuteMandateResult.NotFound
        val check = MandateRules.checkExecution(mandate, amountPaise, now, zone)
        if (check != ExecutionCheck.Allowed) return ExecuteMandateResult.Rejected(check)
        // Reserve this period: only one caller wins the counter CAS.
        if (!mandates.recordExecution(mandateId, now, mandate.executionCount)) return ExecuteMandateResult.Rejected(ExecutionCheck.AlreadyExecutedThisPeriod)
        val attempt = mandate.executionCount + 1
        val txnRef = ("MND" + mandate.umn.filter { it.isLetterOrDigit() }.take(24) + "X$attempt").take(35)
        val payment = Payment(
            id = idGenerator(), txnRef = txnRef, payeeVpa = mandate.payeeVpa, payeeName = mandate.payeeName, amountPaise = amountPaise,
            note = mandate.note, status = PaymentStatus.CREATED, method = PaymentMethod.MANDATE, direction = PaymentDirection.OUTGOING,
            pspTxnId = null, upiTxnId = null, refundId = null, failureReason = null, createdAt = now, updatedAt = now,
            idempotencyKey = txnRef, mandateId = mandateId,
        )
        payments.create(payment)
        try {
            psp.executeMandate(mandate.umn, txnRef, amountPaise)
        } catch (e: PspException) {
            payments.applyEvent(payment.id, PaymentEvent.Fail("PSP rejected the mandate debit: ${e.message}"))
            release(mandateId, mandate)
            return if (e is PspException.ClientError) ExecuteMandateResult.Rejected(e.toCheck()) else ExecuteMandateResult.Unavailable(e)
        }
        payments.applyEvent(payment.id, PaymentEvent.Initiate)
        val confirmed = confirm(payment.id, appResponse = null).payment ?: payment
        return ExecuteMandateResult.Executed(confirmed)
    }

    /** Gives the period back after a PSP rejection: the counter and timestamp return to their previous values. */
    private suspend fun release(mandateId: String, before: Mandate) {
        mandates.releaseExecution(mandateId, before.lastExecutedAt, before.executionCount)
    }

    private fun PspException.ClientError.toCheck(): ExecutionCheck = when (status) {
        422 -> ExecutionCheck.AmountExceedsMax
        409 -> ExecutionCheck.NotActive
        else -> ExecutionCheck.NotActive
    }
}

/** Sweeps mandates whose end date has passed. Called when the list opens. */
class ExpireMandatesUseCase @Inject constructor(
    private val mandates: MandateRepository,
    private val clock: Clock,
    private val zone: ZoneId,
) {
    suspend operator fun invoke(): Int {
        val now = clock.instant()
        return mandates.expireEndedBefore(now.atZone(zone).toLocalDate(), now)
    }
}
