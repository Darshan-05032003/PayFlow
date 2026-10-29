package com.darshan.payflow.ui.scan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.qr.QrError
import com.darshan.payflow.data.qr.UpiQrPayloadParser
import com.darshan.payflow.data.qr.QrParseResult
import com.darshan.payflow.testutil.FakeCameraController
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ScanScreenTest {
    @get:Rule val rule = createComposeRule()
    private val holder = mutableStateOf<ScanUiState>(ScanUiState.CheckingPermission)
    private val camera = FakeCameraController()

    private fun show(state: ScanUiState, callbacks: ScanCallbacks = ScanCallbacks()) {
        holder.value = state
        rule.setContent {
            val current by holder
            PayFlowTheme { ScanScreen(current, callbacks, camera) }
        }
    }

    @Test
    fun loadingAndPermissionStates() {
        var requested = false; var settings = false
        show(ScanUiState.CheckingPermission, ScanCallbacks(onRequestPermission = { requested = true }, onOpenSettings = { settings = true }))
        rule.onNodeWithTag(PayFlowTestTags.LOADING).assertIsDisplayed()
        holder.value = ScanUiState.PermissionRequired(showRationale = true)
        rule.onNodeWithText("PayFlow needs the camera to read UPI QR codes").assertIsDisplayed()
        rule.onNodeWithText("Allow camera").performClick()
        assertThat(requested).isTrue()
        holder.value = ScanUiState.PermissionDenied
        rule.onNodeWithText("Camera permission is turned off").assertIsDisplayed()
        rule.onNodeWithText("Open settings").performClick()
        assertThat(settings).isTrue()
    }

    @Test
    fun scanningBindsTheCameraAndShowsHints() {
        var torch = false
        show(ScanUiState.Scanning(torchOn = false, hint = QrError.NOT_UPI), ScanCallbacks(onToggleTorch = { torch = true }))
        assertThat(camera.started).isTrue()
        rule.onNodeWithText("This is not a UPI QR code").assertIsDisplayed()
        rule.onNodeWithContentDescription("Turn torch on").performClick()
        assertThat(torch).isTrue()
        holder.value = ScanUiState.Scanning(torchOn = true, hint = null)
        rule.onNodeWithContentDescription("Turn torch off").assertIsDisplayed()
        assertThat(camera.torchOn).isTrue()
    }

    @Test
    fun foundShowsPayeeAndOffersPayAndRescan() {
        var paid = false; var rescan = false
        val payload = (UpiQrPayloadParser.parse("upi://pay?pa=shop@okbank&pn=Chai%20Point&am=45.00") as QrParseResult.Valid).payload
        show(ScanUiState.Found(payload), ScanCallbacks(onPay = { paid = true }, onRescan = { rescan = true }))
        assertThat(camera.started).isFalse()
        rule.onNodeWithText("Chai Point").assertIsDisplayed()
        rule.onNodeWithText("shop@okbank").assertIsDisplayed()
        rule.onNodeWithText("₹45.00").assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.SCAN_PAY).performClick()
        assertThat(paid).isTrue()
        rule.onNodeWithText("Scan again").performClick()
        assertThat(rescan).isTrue()
    }

    @Test
    fun errorStateRetries() {
        var retried = false
        show(ScanUiState.Error("boom"), ScanCallbacks(onRescan = { retried = true }))
        rule.onNodeWithTag(PayFlowTestTags.ERROR).assertIsDisplayed()
        rule.onNodeWithText("Retry").performClick()
        assertThat(retried).isTrue()
    }
}
