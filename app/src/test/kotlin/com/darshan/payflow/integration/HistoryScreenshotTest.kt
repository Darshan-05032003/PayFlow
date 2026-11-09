package com.darshan.payflow.integration

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.domain.model.HistoryFilter
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentDirection
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.StatusSource
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.captureScreenshot
import com.darshan.payflow.ui.designsystem.setDs
import com.darshan.payflow.ui.history.HistoryCallbacks
import com.darshan.payflow.ui.history.HistoryScreen
import com.darshan.payflow.ui.history.HistorySections
import com.darshan.payflow.ui.history.HistoryUiState
import com.darshan.payflow.ui.history.PaymentDetailCallbacks
import com.darshan.payflow.ui.history.PaymentDetailScreen
import com.darshan.payflow.ui.history.PaymentDetailUiState
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** History and the payment sheet in their main states, light and dark. Names are invented. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class HistoryScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val zone = ZoneOffset.UTC
    private val today = FIXED_INSTANT.atZone(zone).toLocalDate()

    private fun at(hoursAgo: Long) = FIXED_INSTANT.minusSeconds(hoursAgo * 3_600)

    private val payments: List<Payment> = listOf(
        testPayment("p1", status = PaymentStatus.SUCCESS, payeeName = "Asha Rao", payeeVpa = "asha@okicici", amountPaise = 1_25_000, createdAt = at(0)),
        testPayment("p2", status = PaymentStatus.PENDING, payeeName = "Kiran Mehta", payeeVpa = "kiran@oksbi", amountPaise = 40_000, createdAt = at(1)),
        testPayment("p3", status = PaymentStatus.SUCCESS, payeeName = "Neel Varma", payeeVpa = "neel@okaxis", amountPaise = 2_50_000, createdAt = at(3), direction = PaymentDirection.INCOMING),
        testPayment("p4", status = PaymentStatus.FAILED, payeeName = "Tara Book House", payeeVpa = "tarabooks@okhdfc", amountPaise = 89_900, createdAt = at(26)),
        testPayment("p5", status = PaymentStatus.REFUNDED, payeeName = "Copper Kettle Cafe", payeeVpa = "copperkettle@okbank", amountPaise = 32_000, createdAt = at(30)),
        testPayment("p6", status = PaymentStatus.SUCCESS, payeeName = "Meera Iyer", payeeVpa = "meera@okicici", amountPaise = 5_000, createdAt = at(24 * 9)),
        testPayment("p7", status = PaymentStatus.SUCCESS, payeeName = "Rohan Das", payeeVpa = "rohan@ybl", amountPaise = 1_80_000, createdAt = at(24 * 9 + 2)),
        testPayment("p8", status = PaymentStatus.SUCCESS, payeeName = "Indigo Laundry", payeeVpa = "indigolaundry@okbank", amountPaise = 21_000, createdAt = at(24 * 12)),
    )

    private val content = HistoryUiState.Content(payments, HistoryFilter.ALL, "", HistorySections.build(payments, today, zone))

    private fun history(name: String, state: HistoryUiState, dark: Boolean = false, fontScale: Float = 1f, hidden: Boolean = false, action: (() -> Unit)? = null) {
        rule.setDs(dark = dark, fontScale = fontScale, reduceMotion = true) { HistoryScreen(state, HistoryCallbacks(onBack = null), amountsHidden = hidden, zone = zone) }
        rule.waitForIdle()
        action?.invoke()
        rule.waitForIdle()
        rule.captureScreenshot("history/$name")
    }

    @Test fun contentLight() = history("content_light", content)
    @Test fun contentDark() = history("content_dark", content, dark = true)
    @Test fun contentLargeFont() = history("content_large_font", content, fontScale = 1.3f)
    @Test fun amountsHiddenDark() = history("hidden_dark", content, dark = true, hidden = true)
    @Test fun loadingLight() = history("loading_light", HistoryUiState.Loading)
    @Test fun emptyDark() = history("empty_dark", HistoryUiState.Empty(HistoryFilter.PENDING, ""), dark = true)
    @Test fun noResultsLight() = history("no_results_light", HistoryUiState.Empty(HistoryFilter.ALL, "zzz"))
    @Test fun errorLight() = history("error_light", HistoryUiState.Error("x"))
    @Test fun searchOpenLight() = history("search_open_light", content) { rule.onNodeWithTag(PayFlowTestTags.HISTORY_SEARCH_OPEN).performClick() }
    @Test fun swipeTrayDark() = history("swipe_tray_dark", content, dark = true) {
        rule.onNodeWithTag(PayFlowTestTags.paymentRow("p2")).performTouchInput { swipeLeft(startX = right - 10f, endX = left + 10f) }
    }

    private fun detail(name: String, payment: Payment, dark: Boolean = false, hidden: Boolean = false, handler: String? = null, fontScale: Float = 1f) {
        rule.setDs(dark = dark, fontScale = fontScale, reduceMotion = true) {
            PaymentDetailScreen(PaymentDetailUiState.Content(payment, handlerLabel = handler), PaymentDetailCallbacks(), amountsHidden = hidden)
        }
        rule.waitForIdle()
        rule.captureScreenshot("history/$name")
    }

    private val paid = payments.first().copy(upiTxnId = "UPI412345678901", pspTxnId = "PSP88812", note = "Dinner")

    @Test fun detailLight() = detail("detail_light", paid)
    @Test fun detailDark() = detail("detail_dark", paid, dark = true)
    @Test fun detailHidden() = detail("detail_hidden_light", paid, hidden = true)
    @Test fun detailLargeFont() = detail("detail_large_font", paid, fontScale = 1.3f)
    @Test fun detailPendingUpiApp() = detail(
        "detail_pending_upi_app_dark",
        payments[1].copy(handlerPackage = "com.example.wallet", statusSource = StatusSource.UPI_APP),
        dark = true,
        handler = "Wallet",
    )
    @Test fun detailFailedLight() = detail("detail_failed_light", payments[3].copy(failureReason = "Declined by the bank"))
}
