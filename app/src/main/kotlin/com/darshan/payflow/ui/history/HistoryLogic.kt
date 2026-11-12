package com.darshan.payflow.ui.history

import androidx.compose.runtime.Immutable
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentDirection
import com.darshan.payflow.domain.model.PaymentStatus
import java.time.LocalDate
import java.time.ZoneId

/** How a day header names its day. Older than yesterday is a date, with the year only when it isn't this year's. */
sealed interface DayLabel {
    data object Today : DayLabel
    data object Yesterday : DayLabel
    data class Date(val date: LocalDate, val showYear: Boolean) : DayLabel
}

/** One item of the history list: a sticky day header or a payment. Keys are stable across reloads, for 10,000+ rows. */
@Immutable
sealed interface HistoryEntry {
    val key: String
    val contentType: String

    /** [sentPaise] is what really left the account that day: successful outgoing payments only. */
    data class Day(val date: LocalDate, val label: DayLabel, val count: Int, val sentPaise: Long) : HistoryEntry {
        override val key: String get() = "day:$date"
        override val contentType: String get() = TYPE_DAY
    }

    /** [first] and [last] place the row in its day, so the day's card rounds only its ends. */
    data class Row(val payment: Payment, val first: Boolean, val last: Boolean) : HistoryEntry {
        override val key: String get() = "row:${payment.id}"
        override val contentType: String get() = TYPE_ROW
    }

    companion object {
        const val TYPE_DAY = "day"
        const val TYPE_ROW = "row"
    }
}

object HistorySections {
    /**
     * Groups [payments] (newest first, as the database returns them) into days in the user's [zone]. One pass, no sorting:
     * a run of payments on the same day is one section.
     */
    fun build(payments: List<Payment>, today: LocalDate, zone: ZoneId): List<HistoryEntry> {
        if (payments.isEmpty()) return emptyList()
        val out = ArrayList<HistoryEntry>(payments.size + payments.size / 4 + 1)
        var start = 0
        while (start < payments.size) {
            val date = payments[start].createdAt.atZone(zone).toLocalDate()
            var end = start
            var sent = 0L
            while (end < payments.size && payments[end].createdAt.atZone(zone).toLocalDate() == date) {
                val p = payments[end]
                if (p.status == PaymentStatus.SUCCESS && p.direction == PaymentDirection.OUTGOING) sent += p.amountPaise
                end++
            }
            out += HistoryEntry.Day(date, labelFor(date, today), end - start, sent)
            for (i in start until end) out += HistoryEntry.Row(payments[i], first = i == start, last = i == end - 1)
            start = end
        }
        return out
    }

    fun labelFor(date: LocalDate, today: LocalDate): DayLabel = when (date) {
        today -> DayLabel.Today
        today.minusDays(1) -> DayLabel.Yesterday
        else -> DayLabel.Date(date, showYear = date.year != today.year)
    }
}

/** What a row's swipe tray offers. Also the row's TalkBack actions, so nothing is swipe-only. */
enum class RowAction { PayAgain, CheckStatus }

object HistoryRowActions {
    /**
     * Paying again is offered only once a payment has settled: offering it beside a pending one is how people pay twice. A
     * pending sandbox payment can be checked with the provider; a UPI-app payment has no provider to ask. Money received
     * isn't something to pay again.
     */
    fun of(payment: Payment): List<RowAction> {
        if (payment.direction == PaymentDirection.INCOMING) return emptyList()
        return when (payment.status) {
            PaymentStatus.CREATED, PaymentStatus.PENDING -> if (payment.isUpiAppPayment) emptyList() else listOf(RowAction.CheckStatus)
            PaymentStatus.SUCCESS, PaymentStatus.FAILED, PaymentStatus.REFUNDED -> listOf(RowAction.PayAgain)
            PaymentStatus.REFUND_PENDING -> emptyList()
        }
    }
}

enum class RowSwipeTarget { Open, Closed }

/**
 * A history row's swipe, with no Compose in it. The row slides left to show its tray of actions. Dragging right from rest
 * does nothing; past the tray it resists. On release a fling decides by direction, otherwise half the tray does.
 */
class RowSwipeHolder(private val trayPx: Float) {
    var offset: Float = 0f
        private set

    fun dragBy(delta: Float) {
        if (trayPx <= 0f) return
        val next = offset + delta
        offset = when {
            next > 0f -> 0f
            next < -trayPx -> -trayPx + (next + trayPx) * OVERDRAG_RESISTANCE
            else -> next
        }
    }

    fun release(velocity: Float): RowSwipeTarget = when {
        trayPx <= 0f -> RowSwipeTarget.Closed
        velocity <= -FLING_VELOCITY -> RowSwipeTarget.Open
        velocity >= FLING_VELOCITY -> RowSwipeTarget.Closed
        offset <= -trayPx / 2 -> RowSwipeTarget.Open
        else -> RowSwipeTarget.Closed
    }

    fun targetOffset(target: RowSwipeTarget): Float = if (target == RowSwipeTarget.Open) -trayPx else 0f

    /** A frame of the settle animation, or a reset. */
    fun snapTo(value: Float) {
        offset = value.coerceIn(-trayPx * 1.5f, 0f)
    }

    companion object {
        const val FLING_VELOCITY = 1200f
        private const val OVERDRAG_RESISTANCE = 0.25f
    }
}

/** The search bar morphs between an icon in the header and a full field. A query keeps it open. */
object SearchMorph {
    fun expanded(userOpened: Boolean, query: String): Boolean = userOpened || query.isNotEmpty()
}

/**
 * What History shows while it reloads. A refresh the user asked for in place (pull, or the refresh button) keeps the last
 * answer on screen under the spinner; the first load and a retry from an error show the skeleton alone, with no spinner on top.
 */
object HistoryReload {
    fun shown(state: HistoryUiState, previous: HistoryUiState?, inPlace: Boolean): HistoryUiState =
        if (state is HistoryUiState.Loading && inPlace && (previous is HistoryUiState.Content || previous is HistoryUiState.Empty)) previous else state

    fun spinner(state: HistoryUiState, inPlace: Boolean): Boolean = state is HistoryUiState.Loading && inPlace
}
