package com.darshan.payflow.ui.navigation

/** Every place the app can be. The shell's rules are written against this, so they can be tested without a NavController. */
enum class ShellRoute { Home, History, Me, Scan, Pay, Receive, Collect, Checkout, Mandates, CreateMandate, MandateDetail, PaymentDetail }

/** How one screen replaces another. One vocabulary for the whole app; see docs/DECISIONS.md. */
enum class ScreenTransition {
    /** Tab to tab: no spatial relationship, so nothing slides. */
    FadeThrough,

    /** Forward and back along a flow: a small sideways move with a fade. */
    SharedAxis,

    /** The scanner is a mode rather than a page: it rises over the app and sinks back. */
    Rise,

    /** A payment row growing into its detail screen. */
    ContainerTransform,
}

object ShellLogic {
    /** Bar order: Home, Scan, History, Me. */
    val BAR: List<ShellRoute> = listOf(ShellRoute.Home, ShellRoute.Scan, ShellRoute.History, ShellRoute.Me)

    /** Scan is in the bar but is not a tab: it opens full-bleed over the current tab, and back returns there. */
    private val tabRoots = setOf(ShellRoute.Home, ShellRoute.History, ShellRoute.Me)

    fun isTabRoot(route: ShellRoute?): Boolean = route in tabRoots

    /** The bar belongs to the tab roots. A task inside a flow gets the whole screen. */
    fun barVisible(route: ShellRoute?): Boolean = isTabRoot(route)

    /** Which bar item this route lights up; null leaves the pill where it is. */
    fun barIndex(route: ShellRoute?): Int? = BAR.indexOf(route).takeIf { it >= 0 }

    /** The same answer in both directions, so going back always undoes the way in. */
    fun transition(from: ShellRoute?, to: ShellRoute?): ScreenTransition = when {
        isTabRoot(from) && isTabRoot(to) -> ScreenTransition.FadeThrough
        from == ShellRoute.PaymentDetail || to == ShellRoute.PaymentDetail -> ScreenTransition.ContainerTransform
        // A scanned code moves forward into Pay; only entering and leaving the scanner itself is the mode change.
        from == ShellRoute.Scan && to == ShellRoute.Pay -> ScreenTransition.SharedAxis
        from == ShellRoute.Scan || to == ShellRoute.Scan -> ScreenTransition.Rise
        else -> ScreenTransition.SharedAxis
    }
}
