package com.darshan.payflow.ui.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShellLogicTest {
    @Test
    fun `the bar shows on tab roots and nowhere else`() {
        val visible = ShellRoute.entries.filter(ShellLogic::barVisible)
        assertThat(visible).containsExactly(ShellRoute.Home, ShellRoute.History, ShellRoute.Me)
        assertThat(ShellLogic.barVisible(null)).isFalse()
    }

    @Test
    fun `scan is in the bar but is not a tab`() {
        assertThat(ShellLogic.barIndex(ShellRoute.Scan)).isEqualTo(1)
        assertThat(ShellLogic.isTabRoot(ShellRoute.Scan)).isFalse()
    }

    @Test
    fun `each bar item maps to its position and flows leave the pill alone`() {
        assertThat(ShellLogic.barIndex(ShellRoute.Home)).isEqualTo(0)
        assertThat(ShellLogic.barIndex(ShellRoute.History)).isEqualTo(2)
        assertThat(ShellLogic.barIndex(ShellRoute.Me)).isEqualTo(3)
        assertThat(ShellLogic.barIndex(ShellRoute.Pay)).isNull()
        assertThat(ShellLogic.barIndex(ShellRoute.PaymentDetail)).isNull()
        assertThat(ShellLogic.barIndex(null)).isNull()
    }

    @Test
    fun `tab to tab fades`() {
        assertThat(ShellLogic.transition(ShellRoute.Home, ShellRoute.History)).isEqualTo(ScreenTransition.FadeThrough)
        assertThat(ShellLogic.transition(ShellRoute.Me, ShellRoute.Home)).isEqualTo(ScreenTransition.FadeThrough)
    }

    @Test
    fun `flows move along one axis`() {
        for (route in listOf(ShellRoute.Pay, ShellRoute.Receive, ShellRoute.Collect, ShellRoute.Checkout, ShellRoute.Mandates)) {
            assertThat(ShellLogic.transition(ShellRoute.Home, route)).isEqualTo(ScreenTransition.SharedAxis)
            assertThat(ShellLogic.transition(route, ShellRoute.Home)).isEqualTo(ScreenTransition.SharedAxis)
        }
        assertThat(ShellLogic.transition(ShellRoute.Mandates, ShellRoute.CreateMandate)).isEqualTo(ScreenTransition.SharedAxis)
        assertThat(ShellLogic.transition(ShellRoute.CreateMandate, ShellRoute.MandateDetail)).isEqualTo(ScreenTransition.SharedAxis)
    }

    @Test
    fun `a payment detail is a container transform from anywhere`() {
        for (route in listOf(ShellRoute.Home, ShellRoute.History, ShellRoute.Pay, ShellRoute.MandateDetail, ShellRoute.Collect, ShellRoute.Checkout)) {
            assertThat(ShellLogic.transition(route, ShellRoute.PaymentDetail)).isEqualTo(ScreenTransition.ContainerTransform)
            assertThat(ShellLogic.transition(ShellRoute.PaymentDetail, route)).isEqualTo(ScreenTransition.ContainerTransform)
        }
    }

    @Test
    fun `the scanner rises in, and a scanned code moves forward`() {
        assertThat(ShellLogic.transition(ShellRoute.Home, ShellRoute.Scan)).isEqualTo(ScreenTransition.Rise)
        assertThat(ShellLogic.transition(ShellRoute.Scan, ShellRoute.Home)).isEqualTo(ScreenTransition.Rise)
        assertThat(ShellLogic.transition(ShellRoute.Scan, ShellRoute.Pay)).isEqualTo(ScreenTransition.SharedAxis)
    }

    @Test
    fun `every pair of routes has an answer and going back undoes the way in`() {
        val routes = ShellRoute.entries + listOf<ShellRoute?>(null)
        for (from in routes) for (to in routes) {
            val there = ShellLogic.transition(from, to)
            val back = ShellLogic.transition(to, from)
            if (from == ShellRoute.Scan && to == ShellRoute.Pay || from == ShellRoute.Pay && to == ShellRoute.Scan) continue
            assertThat(back).isEqualTo(there)
        }
    }
}
