package com.darshan.payflow.integration

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.domain.model.CollectRequest
import com.darshan.payflow.domain.model.CollectStatus
import com.darshan.payflow.domain.model.Mandate
import com.darshan.payflow.domain.model.MandateAction
import com.darshan.payflow.domain.model.MandateFrequency
import com.darshan.payflow.domain.model.MandateStatus
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.fixedClock
import com.darshan.payflow.testutil.testPayment
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.collect.CollectCallbacks
import com.darshan.payflow.ui.collect.CollectItem
import com.darshan.payflow.ui.collect.CollectScreen
import com.darshan.payflow.ui.collect.CollectUiState
import com.darshan.payflow.ui.designsystem.captureScreenshot
import com.darshan.payflow.ui.designsystem.setDs
import com.darshan.payflow.ui.mandates.CreateMandateCallbacks
import com.darshan.payflow.ui.mandates.CreateMandateScreen
import com.darshan.payflow.ui.mandates.CreateMandateUiState
import com.darshan.payflow.ui.mandates.MandateDetailCallbacks
import com.darshan.payflow.ui.mandates.MandateDetailScreen
import com.darshan.payflow.ui.mandates.MandateDetailUiState
import com.darshan.payflow.ui.mandates.MandatesCallbacks
import com.darshan.payflow.ui.mandates.MandatesScreen
import com.darshan.payflow.ui.mandates.MandatesUiState
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Requests and AutoPay in their main states, light and dark. Every name is invented. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class MoneyScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val zone = ZoneOffset.UTC
    private val today = FIXED_INSTANT.atZone(zone).toLocalDate()

    private fun request(id: String, name: String, vpa: String, paise: Long, ttl: Long, status: CollectStatus = CollectStatus.PENDING, note: String = "") = CollectRequest(
        id, vpa, name, paise, note, FIXED_INSTANT.minusSeconds(600), FIXED_INSTANT.plusSeconds(ttl), FIXED_INSTANT.minusSeconds(300), status, null,
    )

    private val requests = CollectUiState.Content(
        listOf(
            CollectItem(request("r1", "Tara Book House", "tarabooks@okhdfc", 89_900, 581, note = "Order 7"), false),
            CollectItem(request("r2", "Kiran Mehta", "kiran@oksbi", 25_000, 95, note = "Movie tickets"), false),
            CollectItem(request("r3", "Copper Kettle Cafe", "copperkettle@okbank", 32_000, -60, status = CollectStatus.APPROVED), false),
            CollectItem(request("r4", "Unknown Seller", "deals4u@okaxis", 4_99_900, -600, status = CollectStatus.DECLINED), false),
        ),
        refreshing = false,
    )

    private fun collect(name: String, state: CollectUiState, dark: Boolean = false, fontScale: Float = 1f, reduce: Boolean = true, open: String? = null) {
        rule.setDs(dark = dark, fontScale = fontScale, reduceMotion = reduce) { CollectScreen(state, CollectCallbacks(), clock = fixedClock) }
        rule.waitForIdle()
        open?.let { rule.onNodeWithTag(it).performClick(); rule.waitForIdle() }
        rule.captureScreenshot("money/$name")
    }

    @Test fun collectLight() = collect("collect_light", requests, reduce = false)
    @Test fun collectDark() = collect("collect_dark", requests, dark = true, reduce = false)
    @Test fun collectLargeFont() = collect("collect_large_font", requests, fontScale = 1.3f, reduce = false)
    @Test fun collectReducedMotion() = collect("collect_reduced_motion_dark", requests, dark = true)
    @Test fun collectConfirmLight() = collect("collect_confirm_light", requests, reduce = false, open = PayFlowTestTags.collectPlain("r1"))
    @Test fun collectConfirmDark() = collect("collect_confirm_dark", requests, dark = true, reduce = false, open = PayFlowTestTags.collectPlain("r1"))
    @Test fun collectEmpty() = collect("collect_empty_light", CollectUiState.Empty)
    @Test fun collectLoading() = collect("collect_loading_dark", CollectUiState.Loading, dark = true)

    private fun mandate(id: String, name: String, vpa: String, paise: Long, frequency: MandateFrequency, status: MandateStatus = MandateStatus.ACTIVE) = Mandate(
        id, "UMN${id.uppercase()}20261029", vpa, name, paise, frequency, today.minusMonths(2), today.plusYears(1), status, "Family plan",
        FIXED_INSTANT, FIXED_INSTANT, null, 2,
    )

    private val mandates = listOf(
        mandate("m1", "Streambox", "streambox@okbank", 64_900, MandateFrequency.MONTHLY),
        mandate("m2", "Gym Pass", "gympass@okicici", 1_50_000, MandateFrequency.QUARTERLY, MandateStatus.PAUSED),
        mandate("m3", "Brightline Power", "brightline@oksbi", 3_00_000, MandateFrequency.AS_PRESENTED),
    )

    private fun stack(name: String, state: MandatesUiState, dark: Boolean = false, fontScale: Float = 1f) {
        rule.setDs(dark = dark, fontScale = fontScale, reduceMotion = true) { MandatesScreen(state, MandatesCallbacks(), zone = zone) }
        rule.waitForIdle()
        rule.captureScreenshot("money/$name")
    }

    @Test fun mandatesLight() = stack("mandates_light", MandatesUiState.Content(mandates, today = today))
    @Test fun mandatesDark() = stack("mandates_dark", MandatesUiState.Content(mandates, today = today), dark = true)
    @Test fun mandatesLargeFont() = stack("mandates_large_font", MandatesUiState.Content(mandates, today = today), fontScale = 1.3f)
    @Test fun mandatesPausedFront() = stack("mandates_paused_dark", MandatesUiState.Content(mandates.drop(1), today = today), dark = true)
    @Test fun mandatesEmpty() = stack("mandates_empty_light", MandatesUiState.Empty)

    private fun detail(name: String, dark: Boolean = false, revoke: Boolean = false) {
        val executions = listOf(
            testPayment("d1", status = PaymentStatus.SUCCESS, method = PaymentMethod.MANDATE, payeeName = "Streambox", payeeVpa = "streambox@okbank", amountPaise = 64_900, createdAt = FIXED_INSTANT.minusSeconds(86_400 * 3)),
        )
        rule.setDs(dark = dark, reduceMotion = true) {
            MandateDetailScreen(
                MandateDetailUiState.Content(mandates.first(), executions, listOf(MandateAction.PAUSE, MandateAction.REVOKE), busy = false, debitAmountText = "", today = today),
                MandateDetailCallbacks(),
                zone = zone,
            )
        }
        rule.waitForIdle()
        if (revoke) {
            rule.onNodeWithTag(PayFlowTestTags.mandateAction(MandateAction.REVOKE)).performClick()
            rule.waitForIdle()
        }
        rule.captureScreenshot("money/$name")
    }

    @Test fun mandateDetailLight() = detail("mandate_detail_light")
    @Test fun mandateDetailDark() = detail("mandate_detail_dark", dark = true)
    @Test fun mandateRevokeDark() = detail("mandate_revoke_dark", dark = true, revoke = true)

    @Test fun createMandateLight() {
        rule.setDs(reduceMotion = true) {
            CreateMandateScreen(CreateMandateUiState(vpa = "streambox@okbank", name = "Streambox", amountText = "649"), CreateMandateCallbacks())
        }
        rule.waitForIdle()
        rule.captureScreenshot("money/create_mandate_light")
    }
}
