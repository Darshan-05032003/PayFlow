package com.darshan.payflow.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.darshan.payflow.R
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A human-readable distance between a payment's timestamp and "now". Pure so it can be unit-tested. */
sealed interface RelativeTime {
    data object JustNow : RelativeTime
    data class Minutes(val minutes: Int) : RelativeTime
    data class Hours(val hours: Int) : RelativeTime
    data object Yesterday : RelativeTime
    data class Days(val days: Int) : RelativeTime
    /** Older than a week: an absolute date, with the year only when it differs from the current one. */
    data class Date(val date: LocalDate, val showYear: Boolean) : RelativeTime

    companion object {
        fun of(instant: Instant, now: Instant, zone: ZoneId): RelativeTime {
            val elapsed = Duration.between(instant, now)
            if (elapsed.isNegative || elapsed < Duration.ofMinutes(1)) return JustNow
            if (elapsed < Duration.ofHours(1)) return Minutes(elapsed.toMinutes().toInt())
            val today = now.atZone(zone).toLocalDate()
            val day = instant.atZone(zone).toLocalDate()
            if (day == today) return Hours(elapsed.toHours().toInt())
            val daysAgo = Duration.between(day.atStartOfDay(zone), today.atStartOfDay(zone)).toDays().toInt()
            if (daysAgo == 1) return Yesterday
            if (daysAgo < 7) return Days(daysAgo)
            return Date(day, showYear = day.year != today.year)
        }
    }
}

private val dayMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
private val dayMonthYear: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
fun RelativeTime.label(): String = when (this) {
    RelativeTime.JustNow -> stringResource(R.string.time_just_now)
    is RelativeTime.Minutes -> pluralStringResource(R.plurals.time_minutes_ago, minutes, minutes)
    is RelativeTime.Hours -> pluralStringResource(R.plurals.time_hours_ago, hours, hours)
    RelativeTime.Yesterday -> stringResource(R.string.time_yesterday)
    is RelativeTime.Days -> pluralStringResource(R.plurals.time_days_ago, days, days)
    is RelativeTime.Date -> (if (showYear) dayMonthYear else dayMonth).format(date)
}

/** Relative label for [instant] against the wall clock when the composition was created. */
@Composable
fun Instant.relativeLabel(clock: Clock = Clock.systemDefaultZone()): String {
    val now = remember { clock.instant() }
    return RelativeTime.of(this, now, clock.zone).label()
}
