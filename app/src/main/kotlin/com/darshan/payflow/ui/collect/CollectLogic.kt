package com.darshan.payflow.ui.collect

import com.darshan.payflow.domain.model.CollectRequest
import com.darshan.payflow.domain.model.CollectStatus
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.usecase.CollectActionResult
import com.darshan.payflow.ui.designsystem.effects.HapticEvent
import java.time.Duration
import java.time.Instant
import java.util.Locale

enum class Urgency { Calm, Soon, Expired }

/** A collect request's countdown: what's left of its life, as a ring and a short label. */
data class CollectCountdown(val remaining: Duration, val fraction: Float, val urgency: Urgency) {
    companion object {
        /** The last stretch before expiry, drawn in the pending tone. */
        val SOON: Duration = Duration.ofMinutes(2)

        fun of(request: CollectRequest, now: Instant): CollectCountdown {
            val left = Duration.between(now, request.expiresAt).let { if (it.isNegative) Duration.ZERO else it }
            val life = Duration.between(request.createdAt, request.expiresAt)
            val fraction = if (life.isNegative || life.isZero) 0f else (left.toMillis().toFloat() / life.toMillis()).coerceIn(0f, 1f)
            val urgency = when {
                left.isZero -> Urgency.Expired
                left < SOON -> Urgency.Soon
                else -> Urgency.Calm
            }
            return CollectCountdown(left, if (left.isZero) 0f else fraction, urgency)
        }

        /** "9:41" under an hour, then whole hours, then whole days. */
        fun label(left: Duration): String = when {
            left < Duration.ofHours(1) -> String.format(Locale.ROOT, "%d:%02d", left.toMinutes(), left.seconds % 60)
            left < Duration.ofDays(1) -> "${left.toHours()}h"
            else -> "${left.toDays()}d"
        }
    }
}

object CollectApproval {
    /** Paying needs a request that is still open at this moment and isn't already being answered. Nothing else. */
    fun canApprove(item: CollectItem, now: Instant): Boolean =
        !item.busy && item.request.status == CollectStatus.PENDING && !item.request.isOverdue(now)

    /** Declining is offered on exactly the same requests as paying, so saying no is never harder than saying yes. */
    fun canDecline(item: CollectItem, now: Instant): Boolean = canApprove(item, now)

    /** An answer finished and the request is still open: the provider refused or couldn't be reached. */
    fun refused(wasBusy: Boolean, nowBusy: Boolean, status: CollectStatus): Boolean = wasBusy && !nowBusy && status == CollectStatus.PENDING
}

/** Open requests to act on, and the ones already answered or expired. */
data class CollectSplit(val open: List<CollectItem>, val earlier: List<CollectItem>)

object CollectSections {
    fun split(items: List<CollectItem>, now: Instant): CollectSplit {
        val shown = items.map { item ->
            val effective = item.request.effectiveStatus(now)
            if (effective == item.request.status) item else item.copy(request = item.request.copy(status = effective))
        }
        val (open, earlier) = shown.partition { it.request.status == CollectStatus.PENDING }
        return CollectSplit(
            open = open.sortedBy { it.request.expiresAt },
            // Newest ending first: an answer ends when it was given, an expired request when it ran out.
            earlier = earlier.sortedByDescending { if (it.request.status == CollectStatus.EXPIRED) it.request.expiresAt else it.request.updatedAt },
        )
    }
}

/** Which requests have already slid in on this visit, so coming back doesn't replay the entrance. */
class HeadsUpMemory {
    private val seen = LinkedHashSet<String>()

    fun firstSight(id: String): Boolean {
        val first = seen.add(id)
        if (seen.size > MAX_SEEN) seen.remove(seen.first())
        return first
    }

    private companion object {
        const val MAX_SEEN = 128
    }
}

/** What an answer felt like to the person who gave it: the haptic and the tone of the toast. */
enum class CollectFeedback(val haptic: HapticEvent?) {
    /** The provider confirmed the payment. The only case that earns the confirm haptic. */
    Paid(HapticEvent.Confirm),

    /** Approved, but the provider hasn't confirmed the payment yet. */
    Waiting(null),
    Declined(HapticEvent.Light),
    Problem(HapticEvent.Reject),
    ;

    companion object {
        fun of(result: CollectActionResult): CollectFeedback = when (result) {
            is CollectActionResult.Approved -> when (result.payment.status) {
                PaymentStatus.SUCCESS -> Paid
                PaymentStatus.FAILED -> Problem
                else -> Waiting
            }
            is CollectActionResult.Declined -> Declined
            is CollectActionResult.Expired, is CollectActionResult.AlreadyAnswered, is CollectActionResult.Unavailable, CollectActionResult.NotFound -> Problem
        }
    }
}
