package com.darshan.payflow.testutil

import com.darshan.payflow.core.BackoffPolicy
import com.darshan.payflow.data.psp.PspClient
import com.darshan.payflow.domain.repository.PaymentRepository
import com.darshan.payflow.domain.usecase.ConfirmPaymentUseCase
import com.darshan.payflow.domain.usecase.PollPaymentStatusUseCase
import com.darshan.payflow.domain.usecase.ReconcilePendingPaymentsUseCase
import kotlin.random.Random

val fastPolicy = BackoffPolicy(initialDelayMillis = 10, maxDelayMillis = 50, multiplier = 2.0, jitterFactor = 0.0, maxAttempts = 4)

fun pollerFor(repo: PaymentRepository, psp: PspClient) = PollPaymentStatusUseCase(ConfirmPaymentUseCase(repo, psp, fixedClock), fastPolicy, Random(1))

/** A reconciler over a PSP that knows nothing: it never settles anything, so home tests stay deterministic. */
fun reconcileNoop(repo: PaymentRepository) = ReconcilePendingPaymentsUseCase(repo, pollerFor(repo, StubPsp()), fixedClock)
