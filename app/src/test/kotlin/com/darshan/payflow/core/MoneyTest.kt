package com.darshan.payflow.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MoneyTest {
    @Test
    fun `formats paise with indian grouping`() {
        assertThat(Money.formatInr(0)).isEqualTo("₹0.00")
        assertThat(Money.formatInr(5)).isEqualTo("₹0.05")
        assertThat(Money.formatInr(12_345)).isEqualTo("₹123.45")
        assertThat(Money.formatInr(100_000_00)).isEqualTo("₹1,00,000.00")
        assertThat(Money.formatInr(123_456_789_01)).isEqualTo("₹12,34,56,789.01")
        assertThat(Money.formatInr(-12_345)).isEqualTo("-₹123.45")
    }

    @Test
    fun `parses rupee text to paise exactly`() {
        assertThat(Money.parseInrToPaise("123.45")).isEqualTo(12_345)
        assertThat(Money.parseInrToPaise("₹ 1,00,000")).isEqualTo(100_000_00)
        assertThat(Money.parseInrToPaise("0.5")).isEqualTo(50)
        assertThat(Money.parseInrToPaise("10")).isEqualTo(1_000)
        assertThat(Money.parseInrToPaise(".99")).isEqualTo(99)
    }

    @Test
    fun `rejects garbage, negatives, too many decimals and overflow`() {
        for (bad in listOf("", "abc", "-1", "1.234", "1e5", "1,2,3.4.5", "99999999999999999999", "١٢٣", "1 00", "+5", "0x10")) {
            assertThat(Money.parseInrToPaise(bad)).isNull()
        }
    }
}
