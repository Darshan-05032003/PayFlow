package com.darshan.payflow.ui.components

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Test

class RelativeTimeTest {
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-29T10:15:30Z")

    private fun at(iso: String) = RelativeTime.of(Instant.parse(iso), now, zone)

    @Test
    fun `under a minute and future timestamps are just now`() {
        assertThat(at("2026-10-29T10:15:00Z")).isEqualTo(RelativeTime.JustNow)
        assertThat(at("2026-10-29T10:16:30Z")).isEqualTo(RelativeTime.JustNow)
    }

    @Test
    fun `minutes and hours within the same day`() {
        assertThat(at("2026-10-29T10:10:30Z")).isEqualTo(RelativeTime.Minutes(5))
        assertThat(at("2026-10-29T09:16:30Z")).isEqualTo(RelativeTime.Minutes(59))
        assertThat(at("2026-10-29T07:15:30Z")).isEqualTo(RelativeTime.Hours(3))
    }

    @Test
    fun `calendar days, not 24-hour windows, decide yesterday and days ago`() {
        assertThat(at("2026-10-28T23:50:00Z")).isEqualTo(RelativeTime.Yesterday) // 10 h ago but on the previous date
        assertThat(at("2026-10-26T12:00:00Z")).isEqualTo(RelativeTime.Days(3))
        assertThat(at("2026-10-23T12:00:00Z")).isEqualTo(RelativeTime.Days(6))
    }

    @Test
    fun `a week or older becomes a date, with the year only when it differs`() {
        assertThat(at("2026-10-22T12:00:00Z")).isEqualTo(RelativeTime.Date(LocalDate.of(2026, 10, 22), showYear = false))
        assertThat(at("2025-12-31T12:00:00Z")).isEqualTo(RelativeTime.Date(LocalDate.of(2025, 12, 31), showYear = true))
    }
}
