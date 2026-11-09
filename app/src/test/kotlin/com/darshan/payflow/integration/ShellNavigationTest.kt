package com.darshan.payflow.integration

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.MainActivity
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.repository.PaymentRepository
import com.darshan.payflow.testutil.runOffMain
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.testutil.waitUntilWithMainLooper
import com.darshan.payflow.ui.PayFlowTestTags
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The whole shell on the real graph: every screen still reachable, the bar where it belongs, back doing what it should. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = HiltTestApplication::class, qualifiers = "w411dp-h891dp")
class ShellNavigationTest {
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @Inject lateinit var payments: PaymentRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        compose.waitUntilWithMainLooper(10_000) { exists(PayFlowTestTags.HOME_SCAN) }
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun textExists(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    private fun barVisible() = exists(PayFlowTestTags.NAV_HOME)
    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    private fun await(tag: String) = compose.waitUntilWithMainLooper(10_000) { exists(tag) }
    private fun awaitGone(tag: String) = compose.waitUntilWithMainLooper(10_000) { !exists(tag) }

    private fun tile(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    private fun assertBackGoesHome() {
        back()
        await(PayFlowTestTags.HOME_SCAN)
        compose.waitUntilWithMainLooper(10_000) { barVisible() }
    }

    @Test
    fun theFirstScreenHasTheBarAndTheBenchmarkEntryToHistory() {
        compose.waitUntilWithMainLooper(5_000) { barVisible() }
        compose.onNodeWithTag(PayFlowTestTags.HOME_HISTORY).assertIsDisplayed()
        compose.onNodeWithTag(PayFlowTestTags.NAV_HOME).assertIsSelected()
    }

    @Test
    fun historyFromTheBarReachesTheBenchmarkList() {
        runOffMain { payments.create(testPayment(id = "p1", status = PaymentStatus.SUCCESS)) }
        compose.onNodeWithTag(PayFlowTestTags.HOME_HISTORY).performClick()
        await(PayFlowTestTags.PAYMENT_LIST)
        compose.onNodeWithTag(PayFlowTestTags.HOME_HISTORY).assertIsSelected()
        compose.waitUntilWithMainLooper(5_000) { barVisible() }
    }

    @Test
    fun homeStaysScrollableForTheBenchmarks() {
        assertThat(compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes()).isNotEmpty()
    }

    @Test
    fun theMeTabOpensMeWithNoBackArrowAndSecurityIsOneTapAway() {
        compose.onNodeWithTag(PayFlowTestTags.NAV_ME).performClick()
        await(PayFlowTestTags.ME_SCREEN)
        compose.onNodeWithTag(PayFlowTestTags.NAV_ME).assertIsSelected()
        assertThat(compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Back")).fetchSemanticsNodes()).isEmpty()
        // Security is a page inside Me: it has a back arrow, no bar, and back returns to Me.
        compose.onNodeWithTag(PayFlowTestTags.ME_SECURITY).performClick()
        await(PayFlowTestTags.SECURITY_SETUP_PIN)
        assertThat(compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Back")).fetchSemanticsNodes()).isNotEmpty()
        awaitGone(PayFlowTestTags.NAV_HOME)
        back()
        await(PayFlowTestTags.ME_SCREEN)
        compose.onNodeWithTag(PayFlowTestTags.NAV_ME).assertIsSelected()
        back()
        await(PayFlowTestTags.HOME_SCAN)
        compose.onNodeWithTag(PayFlowTestTags.NAV_HOME).assertIsSelected()
    }

    @Test
    fun meOpensReceive() {
        compose.onNodeWithTag(PayFlowTestTags.NAV_ME).performClick()
        await(PayFlowTestTags.ME_RECEIVE)
        compose.onNodeWithTag(PayFlowTestTags.ME_RECEIVE).performScrollTo().performClick()
        await(PayFlowTestTags.RECEIVE_SAVE)
        back()
        await(PayFlowTestTags.ME_SCREEN)
    }

    @Test
    fun scanFromTheBarGoesFullBleedAndBackPutsThePillBack() {
        compose.onNodeWithTag(PayFlowTestTags.NAV_SCAN).performClick()
        compose.waitUntilWithMainLooper(10_000) { !exists(PayFlowTestTags.HOME_SCAN) }
        compose.waitUntilWithMainLooper(5_000) { !barVisible() }
        back()
        await(PayFlowTestTags.HOME_SCAN)
        compose.waitUntilWithMainLooper(5_000) { barVisible() }
        compose.onNodeWithTag(PayFlowTestTags.NAV_HOME).assertIsSelected()
    }

    @Test
    fun everyTileStillReachesItsScreenAndComesBack() {
        val tiles = listOf(
            PayFlowTestTags.HOME_SCAN,
            PayFlowTestTags.HOME_RECEIVE,
            PayFlowTestTags.HOME_COLLECT,
            PayFlowTestTags.HOME_MANDATES,
            "${PayFlowTestTags.HOME_PAY}:tile",
        )
        for (tag in tiles) {
            tile(tag)
            awaitGone(PayFlowTestTags.HOME_SCAN)
            compose.waitUntilWithMainLooper(5_000) { !barVisible() }
            assertBackGoesHome()
        }
        // The demo store sits at the end of the page, clear of the bar only once scrolled there.
        val scroller = hasScrollAction() and hasTestTag(PayFlowTestTags.HOME_PAYEES).not()
        repeat(3) { compose.onAllNodes(scroller)[0].performTouchInput { swipeUp() } }
        compose.waitForIdle()
        compose.onNodeWithTag(PayFlowTestTags.HOME_CHECKOUT).performClick()
        awaitGone(PayFlowTestTags.HOME_SCAN)
        compose.waitUntilWithMainLooper(5_000) { !barVisible() }
        assertBackGoesHome()
    }

    /** The graphite header runs under the status bar, so its icons must be light on Home whatever the theme, and only on Home. */
    @Test
    fun theStatusBarIconsAreLightOnTheGraphiteHeaderAndFollowTheThemeElsewhere() {
        fun lightIcons() = compose.runOnUiThread<Boolean> {
            androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).isAppearanceLightStatusBars
        }
        compose.waitForIdle()
        assertThat(lightIcons()).isFalse()
        tile(PayFlowTestTags.HOME_RECEIVE)
        awaitGone(PayFlowTestTags.HOME_SCAN)
        compose.waitForIdle()
        assertThat(lightIcons()).isTrue()
        assertBackGoesHome()
        compose.waitForIdle()
        assertThat(lightIcons()).isFalse()
    }

    /** History and Security have no tile of their own any more: "View all" and the bar still reach them, as tab switches. */
    @Test
    fun viewAllAndTheBarSwitchTabs() {
        runOffMain { payments.create(testPayment(id = "p1", status = PaymentStatus.SUCCESS)) }
        await(PayFlowTestTags.paymentRow("p1"))
        // After scrolling, not just performScrollTo: that can leave it under the floating bar, where a real tap would hit the bar.
        val scroller = hasScrollAction() and hasTestTag(PayFlowTestTags.HOME_PAYEES).not()
        repeat(3) { compose.onAllNodes(scroller)[0].performTouchInput { swipeUp() } }
        compose.waitForIdle()
        compose.onNodeWithText("View all").performClick()
        await(PayFlowTestTags.PAYMENT_LIST)
        compose.waitForIdle()
        compose.onNodeWithTag(PayFlowTestTags.HOME_HISTORY).assertIsSelected()
        back()
        await(PayFlowTestTags.HOME_SCAN)
        compose.onNodeWithTag(PayFlowTestTags.NAV_ME).performClick()
        await(PayFlowTestTags.ME_SCREEN)
        compose.onNodeWithTag(PayFlowTestTags.NAV_ME).assertIsSelected()
    }

    @Test
    fun mandateFlowStillWorksDeepInTheStack() {
        tile(PayFlowTestTags.HOME_MANDATES)
        await(PayFlowTestTags.MANDATE_CREATE)
        compose.onNodeWithTag(PayFlowTestTags.MANDATE_CREATE).performClick()
        await(PayFlowTestTags.MANDATE_VPA)
        compose.onNodeWithContentDescription("Back").performClick()
        await(PayFlowTestTags.MANDATE_CREATE)
        assertBackGoesHome()
    }

    @Test
    fun aPaymentRowOpensItsDetailWithoutTheBarAndComesBack() {
        runOffMain { payments.create(testPayment(id = "p1", status = PaymentStatus.SUCCESS)) }
        val row = PayFlowTestTags.paymentRow("p1")
        compose.onNodeWithTag(PayFlowTestTags.HOME_HISTORY).performClick()
        await(PayFlowTestTags.PAYMENT_LIST)
        await(row)
        compose.onNodeWithTag(row).performClick()
        compose.waitUntilWithMainLooper(10_000) { textExists("Payment") }
        compose.waitUntilWithMainLooper(5_000) { !barVisible() }
        // The detail is a sheet now: its close button goes back the way it came.
        compose.onNodeWithContentDescription("Close payment").performClick()
        await(PayFlowTestTags.PAYMENT_LIST)
        compose.waitUntilWithMainLooper(5_000) { barVisible() }
        compose.onNodeWithTag(PayFlowTestTags.HOME_HISTORY).assertIsSelected()
    }

    /** The end of Home's scroll clears the floating bar, so the last row can be tapped rather than only seen. */
    @Test
    fun theLastRowOnHomeCanBeScrolledClearOfTheBar() {
        runOffMain { payments.create(testPayment(id = "p1", status = PaymentStatus.SUCCESS)) }
        val row = PayFlowTestTags.paymentRow("p1")
        await(row)
        compose.waitUntilWithMainLooper(5_000) { barVisible() }
        val scroller = hasScrollAction() and hasTestTag(PayFlowTestTags.HOME_PAYEES).not()
        repeat(3) { compose.onAllNodes(scroller)[0].performTouchInput { swipeUp() } }
        compose.waitForIdle()
        val rowBottom = compose.onNodeWithTag(row).fetchSemanticsNode().boundsInRoot.bottom
        val barTop = compose.onNodeWithTag(PayFlowTestTags.NAV_HOME).fetchSemanticsNode().boundsInRoot.top
        assertThat(rowBottom).isAtMost(barTop)
        compose.onNodeWithTag(row).performClick()
        compose.waitUntilWithMainLooper(10_000) { textExists("Payment") }
    }

    @Test
    fun aDoubleTapOnATilePushesOneScreen() {
        val node = compose.onNodeWithTag(PayFlowTestTags.HOME_RECEIVE).performScrollTo()
        compose.mainClock.autoAdvance = false // both taps land before the next frame, as a real double tap does
        node.performClick()
        node.performClick()
        compose.mainClock.autoAdvance = true
        awaitGone(PayFlowTestTags.HOME_SCAN)
        assertBackGoesHome()
    }

    @Test
    fun rapidTabTapsEndOnTheLastTabWithAConsistentStack() {
        repeat(20) {
            compose.onNodeWithTag(PayFlowTestTags.HOME_HISTORY).performClick()
            compose.onNodeWithTag(PayFlowTestTags.NAV_ME).performClick()
            compose.onNodeWithTag(PayFlowTestTags.NAV_HOME).performClick()
        }
        compose.onNodeWithTag(PayFlowTestTags.NAV_ME).performClick()
        await(PayFlowTestTags.ME_SCREEN)
        compose.onNodeWithTag(PayFlowTestTags.NAV_ME).assertIsSelected()
        back()
        await(PayFlowTestTags.HOME_SCAN)
        compose.onNodeWithTag(PayFlowTestTags.NAV_HOME).assertIsSelected()
    }

    @Test
    fun tappingTheCurrentTabScrollsToTheTop() {
        val scroller = hasScrollAction() and hasTestTag(PayFlowTestTags.HOME_PAYEES).not()
        fun offset() = compose.onAllNodes(scroller).fetchSemanticsNodes().first().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onAllNodes(scroller)[0].performTouchInput { swipeUp() }
        compose.waitUntilWithMainLooper(5_000) { offset() > 0f }
        compose.onNodeWithTag(PayFlowTestTags.NAV_HOME).performClick()
        compose.waitUntilWithMainLooper(5_000) { offset() == 0f }
    }

    @Test
    fun theTabSurvivesRecreationAndThePillFollows() {
        compose.onNodeWithTag(PayFlowTestTags.NAV_ME).performClick()
        await(PayFlowTestTags.ME_SCREEN)
        compose.activityRule.scenario.recreate()
        await(PayFlowTestTags.ME_SCREEN)
        compose.onNodeWithTag(PayFlowTestTags.NAV_ME).assertIsSelected()
        back()
        await(PayFlowTestTags.HOME_SCAN)
    }

    @Test
    fun settingUpAPinFromSecurityReturnsThere() {
        compose.onNodeWithTag(PayFlowTestTags.NAV_ME).performClick()
        await(PayFlowTestTags.ME_SECURITY)
        compose.onNodeWithTag(PayFlowTestTags.ME_SECURITY).performClick()
        await(PayFlowTestTags.SECURITY_SETUP_PIN)
        compose.onNodeWithTag(PayFlowTestTags.SECURITY_SETUP_PIN).performScrollTo().performClick()
        compose.waitUntilWithMainLooper(5_000) { exists(PayFlowTestTags.lockKey("1")) }
        assertThat(exists(PayFlowTestTags.NAV_HOME)).isFalse()
        back() // back during setup means "not now"
        await(PayFlowTestTags.SECURITY_SETUP_PIN)
        assertThat(exists(PayFlowTestTags.lockKey("1"))).isFalse()
    }
}
