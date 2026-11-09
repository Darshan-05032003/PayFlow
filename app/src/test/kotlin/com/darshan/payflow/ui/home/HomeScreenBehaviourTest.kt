package com.darshan.payflow.ui.home

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.domain.model.HistorySummary
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentDirection
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.RecentPayee
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.LocalPaymentMode
import com.darshan.payflow.ui.designsystem.FakeHaptics
import com.darshan.payflow.ui.designsystem.assertInside
import com.darshan.payflow.ui.designsystem.commitState
import com.darshan.payflow.ui.designsystem.recompositionCycles
import com.darshan.payflow.ui.designsystem.setDs
import com.darshan.payflow.ui.navigation.LocalBottomBarInset
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h640dp-xhdpi")
class HomeScreenBehaviourTest {
    @get:Rule val rule = createComposeRule()

    private val max = 99_99_99_999_99L
    private lateinit var scroll: ScrollState

    private fun payment(id: String, status: PaymentStatus = PaymentStatus.SUCCESS, name: String = "Alice Kumar", paise: Long = 12_345, incoming: Boolean = false): Payment =
        testPayment(id, txnRef = "T$id", status = status, payeeName = name, amountPaise = paise.coerceAtMost(Payment.MAX_AMOUNT_PAISE))
            .copy(direction = if (incoming) PaymentDirection.INCOMING else PaymentDirection.OUTGOING)

    private fun content(sent: Long = 12_345, payments: List<Payment> = List(5) { payment("p$it") }, payees: List<RecentPayee> = emptyList()) =
        HomeUiState.Content(payments, HistorySummary.of(payments), HistorySummary.of(payments).copy(sentPaise = sent), payees)

    private fun show(
        state: HomeUiState = content(),
        callbacks: HomeCallbacks = HomeCallbacks(),
        hidden: Boolean = false,
        refreshing: Boolean = false,
        pinConfigured: Boolean = true,
        fontScale: Float = 1f,
        reduceMotion: Boolean = false,
        haptics: FakeHaptics = FakeHaptics(),
        memory: HomeEntranceMemory = HomeEntranceMemory(),
        mode: PaymentMode = PaymentMode.UPI_APPS,
        barInset: Boolean = false,
    ) {
        rule.setDs(fontScale = fontScale, reduceMotion = reduceMotion, haptics = haptics) {
            scroll = rememberScrollState()
            CompositionLocalProvider(LocalPaymentMode provides mode, LocalBottomBarInset provides if (barInset) 80.dp else 0.dp) {
                HomeScreen(state, callbacks, pinConfigured = pinConfigured, amountsHidden = hidden, refreshing = refreshing, memory = memory, scroll = scroll)
            }
        }
    }

    private fun allText(): List<String> = rule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { node ->
        node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } +
            node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
    }

    private fun scrollBy(px: Float) {
        rule.runOnIdle { scroll.dispatchRawDelta(px) }
        rule.waitForIdle()
    }

    private fun frame(): IntArray {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        return IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
    }

    // One primary action

    @Test
    fun thereIsNoFloatingPayButtonAndScanAndPayIsThePrimaryAction() {
        var scans = 0
        var pays = 0
        show(callbacks = HomeCallbacks(onScan = { scans++ }, onPay = { pays++ }))
        rule.onAllNodesWithTag(PayFlowTestTags.HOME_PAY).assertCountEquals(0)
        rule.onNodeWithTag(PayFlowTestTags.HOME_SCAN).assertIsDisplayed().performClick()
        assertThat(scans).isEqualTo(1)
        rule.onNodeWithTag("${PayFlowTestTags.HOME_PAY}:tile").performScrollTo().performClick()
        assertThat(pays).isEqualTo(1)
    }

    @Test
    fun theEmptyStateHasNoSecondCallToActionAndNothingCoversIt() {
        show(HomeUiState.Empty, barInset = true)
        rule.onNodeWithText("Pay someone or scan a QR code to see it here.").performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("Pay", useUnmergedTree = true).assertCountEquals(1) // the quick action only
    }

    // Hero: honest and hideable

    @Test
    fun theHeroSaysSentThisMonthAndNeverCallsItABalanceAsIfItWereOne() {
        show()
        rule.onNodeWithText("Sent this month").assertIsDisplayed()
        rule.onNodeWithText("Recorded on this phone. Not a bank balance.").assertIsDisplayed()
    }

    @Test
    fun sandboxModeLabelsTheHeroAsSandboxAndKeepsTheBadgeWhenDocked() {
        show(mode = PaymentMode.SANDBOX)
        rule.onNodeWithText("Sandbox payments. No real money moved.").assertIsDisplayed()
        scrollBy(100_000f)
        rule.onNodeWithTag(PayFlowTestTags.SANDBOX_BADGE).assertIsDisplayed()
        val badge = rule.onNodeWithTag(PayFlowTestTags.SANDBOX_BADGE).fetchSemanticsNode().boundsInRoot
        assertThat(badge.top).isAtLeast(0f)
    }

    @Test
    fun theSandboxOnlyActionsAreLabelledInUpiAppsModeOnly() {
        show(mode = PaymentMode.UPI_APPS)
        rule.onNodeWithContentDescription("Requests, sandbox").assertExists()
        rule.onNodeWithContentDescription("AutoPay, sandbox").assertExists()
    }

    @Test
    fun theHeroLineLeavesOutZeroCounts() {
        show(content(payments = listOf(payment("a"), payment("b", PaymentStatus.FAILED))))
        rule.onNodeWithText("1 paid · 1 failed").assertIsDisplayed()
    }

    @Test
    fun aQuietMonthSaysSoInsteadOfATableOfZeros() {
        show(HomeUiState.Empty)
        rule.onNodeWithText("No payments this month").assertIsDisplayed()
    }

    @Test
    fun hiddenAmountsLeakNothingAnywhereOnTheScreen() {
        show(content(sent = 77_700), hidden = true)
        rule.waitForIdle()
        val text = allText()
        assertThat(text).contains("Amount hidden")
        // Not the hero, the all-time line, the rows or their spoken descriptions.
        listOf("777", "123.45", "617.25").forEach { digits -> assertThat(text.filter { it.contains(digits) }).isEmpty() }
    }

    @Test
    fun theHiddenHeroIsIdenticalForASmallAndAHugeAmount() {
        var state by mutableStateOf<HomeUiState>(content(sent = 100))
        rule.setDs {
            scroll = rememberScrollState()
            HomeScreen(state, HomeCallbacks(), amountsHidden = true, scroll = scroll)
        }
        rule.waitForIdle()
        val amount = rule.onNodeWithTag(PayFlowTestTags.HOME_HERO_AMOUNT)
        val smallBounds = amount.fetchSemanticsNode().boundsInRoot
        val smallText = amount.fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }
        state = content(sent = max)
        rule.waitForIdle()
        assertThat(amount.fetchSemanticsNode().boundsInRoot).isEqualTo(smallBounds)
        assertThat(amount.fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }).isEqualTo(smallText)
    }

    @Test
    fun theEyeAndTheAmountBothAskToHide() {
        var toggles = 0
        val haptics = FakeHaptics()
        show(callbacks = HomeCallbacks(onToggleAmounts = { toggles++ }), haptics = haptics)
        rule.onNodeWithContentDescription("Hide amounts").performClick()
        assertThat(toggles).isEqualTo(1)
        rule.onNodeWithTag(PayFlowTestTags.HOME_HERO_AMOUNT).performClick()
        assertThat(toggles).isEqualTo(2)
    }

    @Test
    fun theEyeOffersToShowOnceHidden() {
        show(hidden = true)
        rule.onNodeWithContentDescription("Show amounts").assertExists()
    }

    @Test
    fun hidingScramblesThenSettlesOnTheMaskAndTheValueIsGoneAfterThePeak() {
        var hidden by mutableStateOf(false)
        rule.setDs {
            scroll = rememberScrollState()
            HomeScreen(content(sent = 12_345), HomeCallbacks(), amountsHidden = hidden, scroll = scroll)
        }
        rule.waitForIdle()
        val rest = rule.onNodeWithTag(PayFlowTestTags.HOME_HERO).captureToImage().asAndroidBitmap()
        rule.mainClock.autoAdvance = false
        commitState { hidden = true }
        rule.mainClock.advanceTimeBy(64)
        val midway = rule.onNodeWithTag(PayFlowTestTags.HOME_HERO).captureToImage().asAndroidBitmap()
        assertThat(midway.sameAs(rest)).isFalse()
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag(PayFlowTestTags.HOME_HERO_AMOUNT).assertExists()
        assertThat(allText()).contains("Amount hidden")
        assertThat(allText().none { it == "₹123.45" }).isTrue()
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun reducedMotionHidesWithoutScrambleAndStillEndsMasked() {
        var hidden by mutableStateOf(false)
        rule.setDs(reduceMotion = true) {
            scroll = rememberScrollState()
            HomeScreen(content(sent = 12_345), HomeCallbacks(), amountsHidden = hidden, scroll = scroll)
        }
        rule.waitForIdle()
        hidden = true
        rule.waitForIdle()
        assertThat(allText()).contains("Amount hidden")
    }

    @Test
    fun aRapidToggleNeverEndsOnTheWrongSide() {
        var hidden by mutableStateOf(false)
        rule.setDs {
            scroll = rememberScrollState()
            HomeScreen(content(sent = 12_345), HomeCallbacks(), amountsHidden = hidden, scroll = scroll)
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        repeat(21) { // ends hidden
            commitState { hidden = !hidden }
            rule.mainClock.advanceTimeBy(16)
        }
        rule.mainClock.advanceTimeBy(2_000)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertThat(allText()).contains("Amount hidden")
    }

    // Docking

    @Test
    fun theAmountDocksIntoTheTopBarAndStaysOnScreen() {
        show()
        val amount = rule.onNodeWithTag(PayFlowTestTags.HOME_HERO_AMOUNT)
        val hero = rule.onNodeWithTag(PayFlowTestTags.HOME_HERO)
        val restTop = amount.fetchSemanticsNode().boundsInRoot.top
        val restHeight = amount.fetchSemanticsNode().boundsInRoot.height
        scrollBy(100_000f)
        val docked = amount.fetchSemanticsNode().boundsInRoot
        assertThat(docked.top).isLessThan(restTop)
        assertThat(docked.top).isAtLeast(0f)
        assertThat(docked.height).isLessThan(restHeight * 0.6f)
        assertThat(docked.bottom).isAtMost(hero.fetchSemanticsNode().boundsInRoot.bottom)
        assertThat(hero.fetchSemanticsNode().boundsInRoot.bottom).isGreaterThan(0f)
    }

    @Test
    fun scrollingHomeRecomposesAtMostAtTheDockingThreshold() {
        show()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(2_000)
        val heroBottom = rule.onNodeWithTag(PayFlowTestTags.HOME_HERO).fetchSemanticsNode().boundsInRoot.bottom
        val before = recompositionCycles()
        repeat(60) {
            rule.runOnUiThread { scroll.dispatchRawDelta(8f) }
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        val cycles = recompositionCycles() - before
        rule.mainClock.autoAdvance = true
        // The page moved past where the header docks, so the one flip this allows really happened.
        assertThat(scroll.value).isGreaterThan(400)
        assertThat(rule.onNodeWithTag(PayFlowTestTags.HOME_HERO).fetchSemanticsNode().boundsInRoot.bottom).isLessThan(heroBottom)
        assertThat(cycles).isAtLeast(1L)
        assertThat(cycles).isAtMost(3L)
    }

    @Test
    fun aTapOnTheDockedBarDoesNotReachTheScanActionHiddenBelowIt() {
        var scans = 0
        show(callbacks = HomeCallbacks(onScan = { scans++ }))
        scrollBy(100_000f)
        rule.onNodeWithTag(PayFlowTestTags.HOME_HERO).performTouchInput { click(center) }
        rule.onNodeWithTag(PayFlowTestTags.HOME_HERO).performTouchInput { click(centerLeft) }
        rule.waitForIdle()
        assertThat(scans).isEqualTo(0)
    }

    // Motion

    /** Infinite animations are cancelled for good if they start while the clock auto-advances, so the clock is paused first. */
    private fun frameChangesOverTime(reduceMotion: Boolean = false, before: () -> Unit = {}): Boolean {
        rule.mainClock.autoAdvance = false
        show(reduceMotion = reduceMotion)
        rule.mainClock.advanceTimeBy(1_500)
        before()
        rule.mainClock.advanceTimeBy(1_000)
        val first = frame()
        rule.mainClock.advanceTimeBy(800)
        return !frame().contentEquals(first)
    }

    @Test
    fun theScanRingBreathes() {
        assertThat(frameChangesOverTime()).isTrue()
    }

    @Test
    fun theScanRingHoldsStillUnderReducedMotion() {
        assertThat(frameChangesOverTime(reduceMotion = true)).isFalse()
    }

    @Test
    fun theRingStopsOnceTheHeaderHasDocked() {
        val changes = frameChangesOverTime {
            rule.runOnUiThread { scroll.dispatchRawDelta(100_000f) }
            rule.mainClock.advanceTimeBy(100)
        }
        assertThat(changes).isFalse()
    }

    @Test
    fun aNewTotalRollsFromTheOneLastSeen() {
        val memory = HomeEntranceMemory().apply { lastShownSentPaise = 10_000 }
        show(content(sent = 50_000), memory = memory)
        rule.waitForIdle()
        assertThat(memory.lastShownSentPaise).isEqualTo(50_000)
        rule.onNodeWithTag(PayFlowTestTags.HOME_HERO_AMOUNT).fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }.let {
            assertThat(it).containsExactly("₹500")
        }
    }

    @Test
    fun comingBackDoesNotReplayTheEntrance() {
        val memory = HomeEntranceMemory()
        show(memory = memory)
        rule.waitForIdle()
        assertThat(memory.sectionsPlayed).isTrue()
        assertThat(memory.firstSight("p0")).isFalse()
    }

    // Pull to refresh

    @Test
    fun aPullPastTheThresholdRefreshesOnceWithOneTick() {
        var refreshes = 0
        val haptics = FakeHaptics()
        show(callbacks = HomeCallbacks(onRefresh = { refreshes++ }), haptics = haptics)
        rule.onRoot().performTouchInput { swipeDown(startY = centerY / 2, endY = bottom, durationMillis = 400) }
        rule.waitForIdle()
        assertThat(refreshes).isEqualTo(1)
        assertThat(haptics.events.count { it == com.darshan.payflow.ui.designsystem.effects.HapticEvent.Tick }).isEqualTo(1)
    }

    @Test
    fun aShortPullDoesNotRefresh() {
        var refreshes = 0
        show(callbacks = HomeCallbacks(onRefresh = { refreshes++ }))
        rule.onRoot().performTouchInput { swipeDown(startY = centerY, endY = centerY + 40f, durationMillis = 300) }
        rule.waitForIdle()
        assertThat(refreshes).isEqualTo(0)
    }

    @Test
    fun talkBackCanRefreshWithoutTheGesture() {
        var refreshes = 0
        show(callbacks = HomeCallbacks(onRefresh = { refreshes++ }))
        val node = rule.onNode(SemanticsMatcher("has refresh") { n ->
            n.config.getOrElse(SemanticsActions.CustomActions) { emptyList() }.any { it.label == "Refresh" }
        })
        rule.runOnIdle { node.fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Refresh" }.action() }
        assertThat(refreshes).isEqualTo(1)
    }

    @Test
    fun whileRefreshingTheCoinIsShownAndAnnounced() {
        show(refreshing = true)
        rule.onNodeWithTag(PayFlowTestTags.HOME_PULL_COIN).assertExists()
        rule.onNodeWithContentDescription("Refreshing").assertExists()
    }

    // Contacts and feed

    private val payees = listOf(RecentPayee("alice@okbank", "Alice Kumar", testPayment().createdAt), RecentPayee("bob@upi", "Bob", testPayment().createdAt))

    @Test
    fun aContactTapOpensPayOnceAndRipples() {
        val tapped = mutableListOf<RecentPayee>()
        show(content(payees = payees), HomeCallbacks(onPayee = { tapped += it }))
        rule.onNodeWithTag(PayFlowTestTags.HOME_PAYEES).performScrollTo()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(PayFlowTestTags.homePayee("alice@okbank")).performClick()
        rule.mainClock.advanceTimeBy(48)
        rule.mainClock.autoAdvance = true
        assertThat(tapped).containsExactly(payees[0])
    }

    @Test
    fun payeeNamesLoseInvisibleDirectionControlsEverywhere() {
        val spoof = "‮ecilA"
        show(content(payments = listOf(payment("a", name = spoof)), payees = listOf(RecentPayee("a@okbank", spoof, testPayment().createdAt))))
        rule.waitForIdle()
        assertThat(allText().none { it.contains('‮') }).isTrue()
    }

    @Test
    fun emojiOnlyRightToLeftAndEmptyNamesStayInsideTheirSlots() {
        val names = listOf("😀😀😀", "محمد علي", "   ", "\u200B\u200B")
        val at = testPayment().createdAt
        show(
            content(
                payments = names.mapIndexed { i, name -> payment("n$i", name = name) },
                payees = names.mapIndexed { i, name -> RecentPayee("p$i@okbank", name, at) },
            ),
            fontScale = 1.3f,
        )
        rule.waitForIdle()
        val right = rule.onRoot().fetchSemanticsNode().boundsInRoot.right
        names.indices.forEach { i ->
            rule.onNodeWithTag(PayFlowTestTags.paymentRow("n$i")).performScrollTo()
            assertThat(rule.onNodeWithTag(PayFlowTestTags.paymentRow("n$i")).fetchSemanticsNode().boundsInRoot.right).isAtMost(right)
        }
        // A name with nothing visible in it falls back to the handle, so the contact still has a label.
        assertThat(allText().any { it == "p2@okbank" || it == "p3@okbank" }).isTrue()
    }

    @Test
    fun theRefreshToastCarriesNoAmounts() {
        val resources = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().resources
        listOf(HomeEffect.Refreshed(0, 0), HomeEffect.Refreshed(3, 2), HomeEffect.Refreshed(3, 0), HomeEffect.RefreshFailed).forEach {
            assertThat(refreshMessage(it, resources)).doesNotContain("₹")
        }
    }

    @Test
    fun aSuccessfulRowHasNoPillAndExceptionsDo() {
        show(content(payments = listOf(payment("ok"), payment("bad", PaymentStatus.FAILED), payment("wait", PaymentStatus.PENDING))))
        rule.onAllNodesWithText("Success", useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithText("Failed", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Pending", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Payment to Alice Kumar, ₹123.45, Success").assertExists()
    }

    @Test
    fun aHiddenRowSaysAmountHiddenToTalkBack() {
        show(content(payments = listOf(payment("ok"))), hidden = true)
        rule.onNodeWithContentDescription("Payment to Alice Kumar, Amount hidden, Success").assertExists()
    }

    @Test
    fun aRowOpensItsPayment() {
        var opened: String? = null
        show(content(payments = listOf(payment("ok"))), HomeCallbacks(onPaymentClick = { opened = it }))
        rule.onNodeWithTag(PayFlowTestTags.paymentRow("ok")).performScrollTo().performClick()
        assertThat(opened).isEqualTo("ok")
    }

    @Test
    fun theDemoStoreIsLabelledDemo() {
        var opened = false
        show(callbacks = HomeCallbacks(onCheckout = { opened = true }))
        rule.onNodeWithTag(PayFlowTestTags.HOME_CHECKOUT).performScrollTo().performClick()
        assertThat(opened).isTrue()
        rule.onNodeWithText("Demo", useUnmergedTree = true).assertExists()
    }

    // The app lock prompt

    @Test
    fun theLockBannerStillRoutesToPinSetup() {
        var setup = 0
        show(pinConfigured = false, callbacks = HomeCallbacks(onSetupPin = { setup++ }))
        rule.onNodeWithTag(PayFlowTestTags.HOME_LOCK_BANNER).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Set up").performClick()
        assertThat(setup).isEqualTo(1)
    }

    @Test
    fun noBannerOnceAPinIsSet() {
        show(pinConfigured = true)
        rule.onAllNodesWithTag(PayFlowTestTags.HOME_LOCK_BANNER).assertCountEquals(0)
    }

    // End of scroll and scale

    @Test
    fun theEndOfTheScrollClearsTheFloatingBar() {
        show(barInset = true)
        scrollBy(100_000f)
        val footer = rule.onNodeWithTag(PayFlowTestTags.HOME_FOOTER).fetchSemanticsNode().boundsInRoot
        val height = rule.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        val density = rule.density.density
        assertThat(footer.bottom).isAtMost(height - 80f * density)
    }

    @Test
    fun theLargestAmountAt13xStaysInsideTheHeroWithAndWithoutMotion() {
        show(content(sent = max), fontScale = 1.3f)
        rule.waitForIdle()
        val hero = rule.onNodeWithTag(PayFlowTestTags.HOME_HERO)
        rule.onNodeWithTag(PayFlowTestTags.HOME_HERO_AMOUNT).assertInside(hero)
        rule.onNodeWithContentDescription("Hide amounts").assertInside(hero)
        assertThat(allText()).contains("₹99,99,99,999.99")
    }

    @Test
    fun theLargestAmountAt13xDocksInsideTheBarUnderReducedMotion() {
        show(content(sent = max), fontScale = 1.3f, reduceMotion = true)
        rule.waitForIdle()
        scrollBy(100_000f)
        val hero = rule.onNodeWithTag(PayFlowTestTags.HOME_HERO)
        val amount = rule.onNodeWithTag(PayFlowTestTags.HOME_HERO_AMOUNT).fetchSemanticsNode().boundsInRoot
        assertThat(amount.top).isAtLeast(0f)
        assertThat(amount.bottom).isAtMost(hero.fetchSemanticsNode().boundsInRoot.bottom)
    }

    @Test
    fun aHugeNameAndAmountKeepTheRowOnScreen() {
        val row = payment("a", name = "A".repeat(500), paise = Payment.MAX_AMOUNT_PAISE)
        show(content(payments = listOf(row)), fontScale = 1.3f)
        rule.onNodeWithTag(PayFlowTestTags.paymentRow("a")).performScrollTo()
        val width = rule.onRoot().fetchSemanticsNode().boundsInRoot.right
        assertThat(rule.onNodeWithTag(PayFlowTestTags.paymentRow("a")).fetchSemanticsNode().boundsInRoot.right).isAtMost(width)
    }
}
