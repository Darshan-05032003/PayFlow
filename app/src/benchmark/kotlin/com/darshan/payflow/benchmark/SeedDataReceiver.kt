package com.darshan.payflow.benchmark

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentDirection
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.repository.PaymentRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Inserts [ROWS] payments through the repository so the scroll benchmarks have a list to scroll. Safe to send repeatedly.
 * Statuses are settled ones only, so the pending-payment reconciler never touches these rows during a measurement.
 *
 * `adb shell am broadcast -n com.darshan.payflow/.benchmark.SeedDataReceiver` replies `seeded=<rows>`.
 */
class SeedDataReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun paymentRepository(): PaymentRepository
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val repository = EntryPointAccessors.fromApplication(context.applicationContext, Dependencies::class.java).paymentRepository()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                seed(repository)
                pending.resultCode = Activity.RESULT_OK
                pending.resultData = "seeded=$ROWS"
            } catch (e: Exception) {
                pending.resultCode = Activity.RESULT_CANCELED
                pending.resultData = "failed=${e.javaClass.simpleName}"
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun seed(repository: PaymentRepository) {
        val now = Instant.now()
        for (index in 0 until ROWS) {
            val id = "bench-%03d".format(index)
            if (repository.getById(id) == null) repository.create(payment(id, index, now))
        }
    }

    private fun payment(id: String, index: Int, now: Instant): Payment {
        val status = STATUSES[index % STATUSES.size]
        val createdAt = now.minus(Duration.ofHours(3L * index))
        val payee = PAYEES[index % PAYEES.size]
        return Payment(
            id = id,
            txnRef = "BENCH%03d".format(index),
            payeeVpa = "${payee.substringBefore(' ').lowercase()}@okbank",
            payeeName = payee,
            amountPaise = (index * 137L % 4_000L + 50L) * 100L,
            note = if (index % 3 == 0) "Benchmark note $index" else "",
            status = status,
            method = PaymentMethod.entries[index % PaymentMethod.entries.size],
            direction = if (index % 7 == 0) PaymentDirection.INCOMING else PaymentDirection.OUTGOING,
            pspTxnId = if (status == PaymentStatus.FAILED) null else "PSP$id",
            upiTxnId = if (status == PaymentStatus.FAILED) null else "UPI$id",
            refundId = if (status == PaymentStatus.REFUNDED) "RFD$id" else null,
            failureReason = if (status == PaymentStatus.FAILED) "Declined by bank" else null,
            createdAt = createdAt,
            updatedAt = createdAt,
            idempotencyKey = null,
        )
    }

    private companion object {
        const val ROWS = 200
        val STATUSES = listOf(PaymentStatus.SUCCESS, PaymentStatus.SUCCESS, PaymentStatus.FAILED, PaymentStatus.SUCCESS, PaymentStatus.REFUNDED)
        val PAYEES = listOf(
            "Aarav Sharma", "Diya Patel", "Kabir Singh", "Meera Nair", "Rohan Gupta", "Saanvi Iyer",
            "Vivaan Reddy", "Anaya Joshi", "Ishaan Mehta", "Kiara Desai", "Arjun Rao", "Tara Kulkarni",
        )
    }
}
