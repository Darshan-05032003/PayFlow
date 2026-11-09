package com.darshan.payflow.ui.mandates

import com.darshan.payflow.domain.model.Mandate
import com.darshan.payflow.domain.model.MandateAction
import com.darshan.payflow.domain.model.MandateFrequency
import com.darshan.payflow.domain.model.MandateRules
import com.darshan.payflow.domain.model.MandateStatus
import java.time.LocalDate
import java.time.ZoneId

/** When the next debit is expected, or why none is. */
sealed interface NextDebit {
    data class On(val date: LocalDate) : NextDebit
    data object Paused : NextDebit
    data object WhenPresented : NextDebit
    data object Ended : NextDebit
}

/**
 * Expected debit dates. A mandate only allows a debit; the merchant decides when to take it. These are the dates the
 * frequency allows from the start date, once per period, which is what the UI calls "expected", never "scheduled".
 */
object MandateSchedule {
    fun upcoming(mandate: Mandate, today: LocalDate, zone: ZoneId, count: Int = DEFAULT_COUNT): List<LocalDate> {
        if (mandate.status != MandateStatus.ACTIVE || mandate.frequency == MandateFrequency.AS_PRESENTED || count <= 0) return emptyList()
        if (mandate.endDate.isBefore(today)) return emptyList()
        val takenPeriod = mandate.lastExecutedAt?.let { MandateRules.periodKey(mandate.frequency, it, zone) }
        val todayPeriod = periodOf(mandate.frequency, today, zone)
        val out = ArrayList<LocalDate>(count)
        var k = 0L
        while (out.size < count && k <= MAX_STEPS) {
            val date = step(mandate.startDate, mandate.frequency, k++)
            if (date.isAfter(mandate.endDate)) break
            val period = periodOf(mandate.frequency, date, zone)
            if (period == takenPeriod) continue
            when {
                !date.isBefore(today) -> out += date
                // A debit that fell earlier in the current period and hasn't been taken is still due: it shows as today.
                period == todayPeriod -> out += today
            }
        }
        return out.distinct()
    }

    fun nextDebit(mandate: Mandate, today: LocalDate, zone: ZoneId): NextDebit = when {
        mandate.status == MandateStatus.PAUSED -> NextDebit.Paused
        mandate.status.isTerminal || mandate.endDate.isBefore(today) -> NextDebit.Ended
        mandate.frequency == MandateFrequency.AS_PRESENTED -> NextDebit.WhenPresented
        else -> upcoming(mandate, today, zone, 1).firstOrNull()?.let { NextDebit.On(it) } ?: NextDebit.Ended
    }

    private fun periodOf(frequency: MandateFrequency, date: LocalDate, zone: ZoneId): String =
        MandateRules.periodKey(frequency, date.atStartOfDay(zone).toInstant(), zone)

    /** Each step counts from the start, so the 31st stays the 31st wherever a month allows it. */
    private fun step(start: LocalDate, frequency: MandateFrequency, k: Long): LocalDate = when (frequency) {
        MandateFrequency.DAILY -> start.plusDays(k)
        MandateFrequency.WEEKLY -> start.plusWeeks(k)
        MandateFrequency.MONTHLY -> start.plusMonths(k)
        MandateFrequency.QUARTERLY -> start.plusMonths(3 * k)
        MandateFrequency.YEARLY -> start.plusYears(k)
        MandateFrequency.AS_PRESENTED -> start
    }

    const val DEFAULT_COUNT = 4
    private const val MAX_STEPS = 50_000L
}

object MandateQuickAction {
    /** The one action on a card: pause an active mandate, resume a paused one. Revoking is in the detail, behind a confirmation. */
    fun of(status: MandateStatus): MandateAction? = when (status) {
        MandateStatus.ACTIVE -> MandateAction.PAUSE
        MandateStatus.PAUSED -> MandateAction.RESUME
        MandateStatus.REVOKED, MandateStatus.EXPIRED -> null
    }

    /** 0 draws two pause bars, 1 a play triangle: the icon shows what a tap will do. */
    fun morphTarget(status: MandateStatus): Float = if (status == MandateStatus.PAUSED) 1f else 0f
}

/**
 * Where a card sits in the AutoPay stack. A card [depth] steps back is scaled down by [SCALE_STEP] per step and moved down so
 * its bottom edge lands exactly [depth] peeks below the front card's: the peek strip shows only the card's bottom padding,
 * never its text. (The scale compensation was once multiplied by the depth a second time, which dropped the third card by
 * `step x height` and showed its text at 1.3x.)
 */
object StackGeometry {
    const val SCALE_STEP = 0.05f

    fun scale(depth: Float): Float = 1f - depth * SCALE_STEP

    /** Layer translation for a card scaled about its centre: the peek plus half of what scaling took off its height. */
    fun translationY(depth: Float, heightPx: Float, peekPx: Float): Float = depth * peekPx + heightPx * (1f - scale(depth)) / 2f

    /** The card's bottom edge relative to its unscaled, untranslated top. */
    fun bottom(depth: Float, heightPx: Float, peekPx: Float): Float =
        heightPx / 2f + scale(depth) * heightPx / 2f + translationY(depth, heightPx, peekPx)
}

/**
 * The AutoPay card stack, with no Compose in it. The front card follows the finger; past a third of its width, or on a
 * fling, the stack moves one card. Past either end the drag resists, and a release never moves more than one card.
 */
class CardStackHolder(count: Int, private var widthPx: Float) {
    var count: Int = count
        private set
    var index: Int = 0
        private set
    var offset: Float = 0f
        private set

    fun setWidth(px: Float) {
        widthPx = px
    }

    fun dragBy(delta: Float) {
        val next = offset + delta
        val atStart = index == 0 && next > 0f
        val atEnd = index >= count - 1 && next < 0f
        offset = if (atStart || atEnd) offset + delta * EDGE_RESISTANCE else next
    }

    /** Returns the card now in front. The owner animates [offset] back to zero. */
    fun release(velocity: Float): Int {
        val threshold = widthPx * SWIPE_FRACTION
        // A fling decides by its direction; otherwise how far the card was dragged does.
        val target = when {
            velocity <= -FLING_VELOCITY -> index + 1
            velocity >= FLING_VELOCITY -> index - 1
            offset <= -threshold -> index + 1
            offset >= threshold -> index - 1
            else -> index
        }
        index = target.coerceIn(0, (count - 1).coerceAtLeast(0))
        return index
    }

    /** A frame of the settle animation. */
    fun snapOffset(value: Float) {
        offset = value
    }

    fun goTo(i: Int) {
        index = i.coerceIn(0, (count - 1).coerceAtLeast(0))
        offset = 0f
    }

    fun resize(newCount: Int) {
        count = newCount
        index = index.coerceIn(0, (newCount - 1).coerceAtLeast(0))
    }

    companion object {
        const val FLING_VELOCITY = 1000f
        const val SWIPE_FRACTION = 1f / 3f
        private const val EDGE_RESISTANCE = 0.25f
    }
}
