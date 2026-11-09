package com.darshan.payflow.ui.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.domain.model.HistoryFilter
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.assertInside
import com.darshan.payflow.ui.designsystem.commitState
import com.darshan.payflow.ui.designsystem.setDs
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class HistoryMoneyScreenTest {
    @get:Rule val rule = createComposeRule()
    private val zone = ZoneOffset.UTC
    private val today: LocalDate = FIXED_INSTANT.atZone(zone).toLocalDate()

    private fun content(payments: List<Payment>) =
        HistoryUiState.Content(payments, HistoryFilter.ALL, "", HistorySections.build(payments, today, zone))

    private val sample = listOf(
        testPayment("a", status = PaymentStatus.SUCCESS, payeeName = "Asha Rao", amountPaise = 1_25_000),
        testPayment("b", status = PaymentStatus.PENDING, payeeName = "Kiran Mehta", amountPaise = 40_000),
        testPayment("c", status = PaymentStatus.FAILED, payeeName = "Neel Varma", amountPaise = 9_900, createdAt = FIXED_INSTANT.minusSeconds(86_400)),
    )

    private fun show(
        state: HistoryUiState,
        callbacks: HistoryCallbacks = HistoryCallbacks(onBack = null),
        hidden: Boolean = false,
        fontScale: Float = 1f,
        reduceMotion: Boolean = false,
    ) {
        rule.setDs(fontScale = fontScale, reduceMotion = reduceMotion) { HistoryScreen(state, callbacks, amountsHidden = hidden, zone = zone) }
    }

    @Test
    fun paymentsSitUnderStickyDayHeadersWithWhatWasSent() {
        show(content(sample))
        rule.onNodeWithTag(PayFlowTestTags.historyDay(today.toString())).assertIsDisplayed()
        rule.onNodeWithText("Today").assertIsDisplayed()
        rule.onNodeWithText("Yesterday").assertIsDisplayed()
        // Only the success counts as sent; the pending ₹400 is not money gone.
        rule.onNodeWithText("₹1,250 sent").assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.paymentRow("a")).assertIsDisplayed()
    }

    @Test
    fun hidingAmountsMasksRowsAndDayTotals() {
        show(content(sample), hidden = true)
        rule.onAllNodesWithText("₹1,250 sent").assertCountEquals(0)
        rule.onAllNodesWithText("−₹1,250", substring = true).assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Amount hidden").fetchSemanticsNodes().let { assertThat(it).isNotEmpty() }
        rule.onNodeWithTag(PayFlowTestTags.historyDay(today.toString())).assertContentDescriptionContains("Amount hidden")
    }

    @Test
    fun theEyeTogglesHomesHideSetting() {
        var toggled = 0
        show(content(sample), HistoryCallbacks(onBack = null, onToggleAmounts = { toggled++ }))
        rule.onNodeWithTag(PayFlowTestTags.HISTORY_HIDE_AMOUNTS).performClick()
        assertThat(toggled).isEqualTo(1)
    }

    @Test
    fun swipingASettledRowLeftOffersPayAgainWithThePayeeOnly() {
        var again: Payment? = null
        show(content(sample), HistoryCallbacks(onBack = null, onPayAgain = { again = it }))
        rule.onNodeWithTag(PayFlowTestTags.paymentRow("a")).performTouchInput { swipeLeft(startX = right - 10f, endX = left + 10f) }
        rule.waitForIdle()
        rule.onNodeWithTag(PayFlowTestTags.historyAction("a", RowAction.PayAgain.name), useUnmergedTree = true).performClick()
        assertThat(again?.payeeVpa).isEqualTo("alice@okbank")
    }

    @Test
    fun aPendingRowOffersCheckStatusAndNeverPayAgain() {
        var checked: String? = null
        show(content(sample), HistoryCallbacks(onBack = null, onCheckStatus = { checked = it }))
        rule.onAllNodesWithTag(PayFlowTestTags.historyAction("b", RowAction.PayAgain.name), useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithTag(PayFlowTestTags.paymentRow("b")).performTouchInput { swipeLeft(startX = right - 10f, endX = left + 10f) }
        rule.waitForIdle()
        rule.onNodeWithTag(PayFlowTestTags.historyAction("b", RowAction.CheckStatus.name), useUnmergedTree = true).performClick()
        assertThat(checked).isEqualTo("b")
    }

    @Test
    fun rowActionsAreTalkBackActionsToo() {
        var again: Payment? = null
        show(content(sample), HistoryCallbacks(onBack = null, onPayAgain = { again = it }))
        val actions = rule.onNodeWithTag(PayFlowTestTags.paymentRow("c")).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertThat(actions.map { it.label }).containsExactly("Pay again")
        rule.runOnIdle { actions.single().action() }
        assertThat(again?.id).isEqualTo("c")
    }

    @Test
    fun aTapOnARowStillOpensIt() {
        var opened: String? = null
        show(content(sample), HistoryCallbacks(onBack = null, onPaymentClick = { opened = it }))
        rule.onNodeWithTag(PayFlowTestTags.paymentRow("a")).performClick()
        assertThat(opened).isEqualTo("a")
    }

    @Test
    fun searchMorphsOpenAndClosingClearsTheQuery() {
        val queries = mutableListOf<String>()
        show(content(sample), HistoryCallbacks(onBack = null, onQuery = { queries += it }))
        rule.onNodeWithTag(PayFlowTestTags.HISTORY_SEARCH_OPEN).performClick()
        rule.onNodeWithTag(PayFlowTestTags.SEARCH_FIELD).performTextInput("asha")
        rule.onNodeWithTag(PayFlowTestTags.HISTORY_SEARCH_CLOSE).performClick()
        rule.waitForIdle()
        assertThat(queries).containsExactly("asha", "").inOrder()
        rule.onAllNodesWithTag(PayFlowTestTags.SEARCH_FIELD).assertCountEquals(0)
    }

    @Test
    fun theSearchFieldGrowsOutOfTheButton() {
        rule.mainClock.autoAdvance = false
        show(content(sample))
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag(PayFlowTestTags.HISTORY_SEARCH_OPEN).performClick()
        rule.mainClock.advanceTimeBy(48)
        val early = rule.onRoot().captureToImage().toPixelMap()
        val closeEarly = rule.onNodeWithTag(PayFlowTestTags.HISTORY_SEARCH_CLOSE).fetchSemanticsNode().boundsInRoot
        rule.mainClock.advanceTimeBy(1_000)
        val settled = rule.onRoot().captureToImage().toPixelMap()
        val closeSettled = rule.onNodeWithTag(PayFlowTestTags.HISTORY_SEARCH_CLOSE).fetchSemanticsNode().boundsInRoot
        // The close button stays where the search button was; what changes is how much of the pill is drawn.
        assertThat(closeEarly).isEqualTo(closeSettled)
        val y = (closeSettled.center.y).toInt()
        val x = (rule.onRoot().fetchSemanticsNode().boundsInRoot.width * 0.3f).toInt()
        assertThat(early[x, y]).isNotEqualTo(settled[x, y])
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun reducedMotionOpensSearchWithoutTheMorph() {
        show(content(sample), reduceMotion = true)
        rule.onNodeWithTag(PayFlowTestTags.HISTORY_SEARCH_OPEN).performClick()
        rule.onNodeWithTag(PayFlowTestTags.SEARCH_FIELD).assertIsDisplayed()
    }

    @Test
    fun largeTextKeepsTheHeaderAndChipsOnScreen() {
        show(content(sample), fontScale = 1.3f)
        rule.onNodeWithText("History").assertInside(rule.onRoot())
        rule.onNodeWithTag(PayFlowTestTags.filterChip(HistoryFilter.ALL)).assertInside(rule.onRoot())
        rule.onNodeWithTag(PayFlowTestTags.paymentRow("a")).assertInside(rule.onRoot())
    }

    @Test
    fun tenThousandPaymentsComposeOnlyWhatIsVisible() {
        val many = (0 until 10_000).map { testPayment("p$it", status = PaymentStatus.SUCCESS, createdAt = FIXED_INSTANT.minusSeconds(it * 900L)) }
        show(content(many))
        val composedRows = rule.onAllNodes(hasTestTagPrefix("payflow:payment:")).fetchSemanticsNodes().size
        assertThat(composedRows).isLessThan(40)
        val entries = HistorySections.build(many, today, zone)
        rule.onNodeWithTag(PayFlowTestTags.PAYMENT_LIST).performScrollToIndex(entries.size)
        rule.onNodeWithTag(PayFlowTestTags.paymentRow("p9999")).assertIsDisplayed()
    }

    @Test
    fun pullingDownRefreshes() {
        var refreshed = 0
        show(content(sample), HistoryCallbacks(onBack = null, onRefresh = { refreshed++ }))
        rule.onNodeWithTag(PayFlowTestTags.PAYMENT_LIST).performTouchInput { swipeDown(startY = top + 10f, endY = bottom, durationMillis = 600) }
        rule.waitForIdle()
        assertThat(refreshed).isEqualTo(1)
    }

    @Test
    fun detailHonoursHideAmountsAndItsEyeTogglesIt() {
        val holder = mutableStateOf(true)
        var toggled = 0
        val payment = sample.first().copy(upiTxnId = "UPI1")
        rule.setDs {
            val hidden by holder
            PaymentDetailScreen(PaymentDetailUiState.Content(payment), PaymentDetailCallbacks(onToggleAmounts = { toggled++ }), amountsHidden = hidden)
        }
        rule.onNodeWithTag(PayFlowTestTags.DETAIL_AMOUNT).assertContentDescriptionContains("Amount hidden")
        rule.onAllNodesWithText("₹1,250.00").assertCountEquals(0)
        rule.onNodeWithTag(PayFlowTestTags.DETAIL_HIDE_AMOUNTS).performClick()
        assertThat(toggled).isEqualTo(1)
        commitState { holder.value = false }
        rule.onNodeWithText("₹1,250.00").assertIsDisplayed()
    }

    @Test
    fun detailUsesPaysReceiptWordingAndStatus() {
        val payment = sample.first()
        rule.setDs { PaymentDetailScreen(PaymentDetailUiState.Content(payment), PaymentDetailCallbacks()) }
        rule.onNodeWithTag(PayFlowTestTags.DETAIL_RECEIPT).assertIsDisplayed()
        rule.onNodeWithText("Confirmed by the sandbox payment provider.", substring = true).assertExists()
    }

    /**
     * Design QA: Method says how the payment started, the receipt says who confirmed it. Method used to read "UPI app" under
     * "Confirmed by the sandbox payment provider", which looked like two different answers to "who said this was paid".
     */
    @Test
    fun detailMethodNamesHowThePaymentStartedNotWhoConfirmedIt() {
        val payment = sample.first().copy(pspTxnId = "PSP1")
        rule.setDs { PaymentDetailScreen(PaymentDetailUiState.Content(payment), PaymentDetailCallbacks()) }
        rule.onNodeWithText("Confirmed by the sandbox payment provider.", substring = true).assertExists()
        rule.onNodeWithText("UPI ID").performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("UPI app", substring = true).assertCountEquals(0)
    }

    /** Design QA: "Copy reference" starts on the same gutter as the details above it, not inset by a text button's padding. */
    @Test
    fun copyReferenceSitsOnTheDetailsGutter() {
        val payment = sample.first().copy(pspTxnId = "PSP1")
        rule.setDs { PaymentDetailScreen(PaymentDetailUiState.Content(payment), PaymentDetailCallbacks()) }
        val copy = rule.onNodeWithTag(PayFlowTestTags.DETAIL_COPY_REFERENCE).performScrollTo().getBoundsInRoot()
        val method = rule.onNodeWithText("Method").getBoundsInRoot()
        assertThat(copy.left).isEqualTo(method.left)
        assertThat((copy.right - copy.left).value).isGreaterThan((method.right - method.left).value * 4)
    }

    /** Clipboard exposure: keyboards and the clipboard preview must not show a copied payment reference. */
    @Test
    fun copiedReferencesAreMarkedSensitiveOnTheClipboard() {
        val payment = sample.first().copy(pspTxnId = "PSPX123456")
        rule.setDs { PaymentDetailScreen(PaymentDetailUiState.Content(payment), PaymentDetailCallbacks()) }
        val clipboard = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(android.content.ClipboardManager::class.java)
        rule.onNodeWithText("Copy reference").performScrollTo().performClick()
        rule.waitForIdle()
        var clip = clipboard.primaryClip!!
        assertThat(clip.getItemAt(0).text.toString()).isEqualTo(payment.txnRef)
        assertThat(clip.description.extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE)).isTrue()
        rule.onNodeWithContentDescription("Copy PSP transaction").performScrollTo().performClick()
        rule.waitForIdle()
        clip = clipboard.primaryClip!!
        assertThat(clip.getItemAt(0).text.toString()).isEqualTo("PSPX123456")
        assertThat(clip.description.extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE)).isTrue()
    }

    @Test
    fun draggingTheSheetDownClosesIt() {
        var closed = 0
        rule.setDs { PaymentDetailScreen(PaymentDetailUiState.Content(sample.first()), PaymentDetailCallbacks(onBack = { closed++ })) }
        rule.onNodeWithText("Payment").performTouchInput { swipeDown(startY = centerY, endY = centerY + 1_400f, durationMillis = 200) }
        rule.waitForIdle()
        assertThat(closed).isEqualTo(1)
    }

    @Test
    fun aShortDragSpringsTheSheetBack() {
        var closed = 0
        rule.setDs { PaymentDetailScreen(PaymentDetailUiState.Content(sample.first()), PaymentDetailCallbacks(onBack = { closed++ })) }
        rule.onNodeWithText("Payment").performTouchInput { swipeDown(startY = centerY, endY = centerY + 60f, durationMillis = 800) }
        rule.waitForIdle()
        assertThat(closed).isEqualTo(0)
        rule.onNodeWithContentDescription("Close payment").performClick()
        assertThat(closed).isEqualTo(1)
    }

    private fun hasTestTagPrefix(prefix: String) = androidx.compose.ui.test.SemanticsMatcher("tag starts with $prefix") { node ->
        node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertContentDescriptionContains(text: String) {
        val all = fetchSemanticsNode().let { node ->
            (listOf(node) + node.children).flatMap { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription).orEmpty() } +
                rule.onAllNodes(hasTestTag(node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) ?: ""), useUnmergedTree = true)
                    .fetchSemanticsNodes().flatMap { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription).orEmpty() }
        }
        assertThat(all.joinToString()).contains(text)
    }
}
