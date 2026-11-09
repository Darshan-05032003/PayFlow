package com.darshan.payflow.ui.scan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import com.darshan.payflow.ui.designsystem.setDs
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

    private fun show(state: ScanUiState, callbacks: ScanCallbacks = ScanCallbacks(), fontScale: Float = 1f, reduceMotion: Boolean = false) {
        holder.value = state
        rule.setDs(fontScale = fontScale, reduceMotion = reduceMotion) {
            val current by holder
            ScanScreen(current, callbacks, camera)
        }
    }

    /** The permission views share their buttons, and a button drops a second click inside its 500ms debounce. */
    private fun pastDebounce() {
        rule.waitForIdle()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(600))
    }

    @Test
    fun loadingAndPermissionStates() {
        var requested = false; var settings = false
        show(ScanUiState.CheckingPermission, ScanCallbacks(onRequestPermission = { requested = true }, onOpenSettings = { settings = true }), reduceMotion = true)
        rule.onNodeWithTag(PayFlowTestTags.LOADING).assertIsDisplayed()
        holder.value = ScanUiState.PermissionRequired(showRationale = true)
        rule.onNodeWithText("PayFlow needs the camera to read UPI QR codes").assertIsDisplayed()
        rule.onNodeWithText("Allow camera").performClick()
        assertThat(requested).isTrue()
        holder.value = ScanUiState.PermissionDenied
        pastDebounce()
        rule.onNodeWithText("Camera permission is turned off").assertIsDisplayed()
        rule.onNodeWithText("Open settings").performClick()
        assertThat(settings).isTrue()
        // No camera, no preview: nothing is bound while permission is missing.
        assertThat(camera.started).isFalse()
    }

    @Test
    fun scanningBindsTheCameraAndShowsHints() {
        var torch = false
        show(ScanUiState.Scanning(torchOn = false, hint = QrError.NOT_UPI), ScanCallbacks(onToggleTorch = { torch = true }), reduceMotion = true)
        assertThat(camera.started).isTrue()
        rule.onNodeWithText("This is not a UPI QR code").assertIsDisplayed()
        rule.onNodeWithContentDescription("Turn torch on").performClick()
        assertThat(torch).isTrue()
        holder.value = ScanUiState.Scanning(torchOn = true, hint = null)
        rule.onNodeWithContentDescription("Turn torch off").assertIsDisplayed()
        assertThat(camera.torchOn).isTrue()
        rule.onNodeWithTag(PayFlowTestTags.SCAN_PREVIEW).assertExists()
    }

    @Test
    fun leavingTheCameraStopsIt() {
        show(ScanUiState.Scanning(torchOn = false, hint = null), reduceMotion = true)
        assertThat(camera.started).isTrue()
        holder.value = ScanUiState.PermissionDenied
        rule.waitForIdle()
        assertThat(camera.started).isFalse()
    }

    @Test
    fun foundShowsPayeeAndOffersPayAndRescan() {
        var paid = false; var rescan = false
        val payload = (UpiQrPayloadParser.parse("upi://pay?pa=shop@okbank&pn=Tulsi%20Tea%20Corner&am=45.00") as QrParseResult.Valid).payload
        show(ScanUiState.Found(payload), ScanCallbacks(onPay = { paid = true }, onRescan = { rescan = true }))
        assertThat(camera.started).isFalse()
        rule.onNodeWithText("Tulsi Tea Corner").assertIsDisplayed()
        rule.onNodeWithText("shop@okbank").assertIsDisplayed()
        rule.onNodeWithText("₹45.00").assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.SCAN_PAY).performClick()
        assertThat(paid).isTrue()
        rule.onNodeWithText("Scan again").performClick()
        assertThat(rescan).isTrue()
    }

    @Test
    fun severalCodesInOneImageAreSaidPlainly() {
        val payload = (UpiQrPayloadParser.parse("upi://pay?pa=shop@okbank&pn=Tulsi%20Tea%20Corner") as QrParseResult.Valid).payload
        show(ScanUiState.Found(payload, codesInImage = 3))
        rule.onNodeWithText("This image has 3 payment QR codes. This is the first one. Check it's who you meant to pay.").assertIsDisplayed()
    }

    @Test
    fun aHostileQrNameIsShownSafely() {
        val payload = (UpiQrPayloadParser.parse("upi://pay?pa=shop@okbank&pn=Refund%E2%80%AEdnuf") as QrParseResult.Valid).payload
        show(ScanUiState.Found(payload))
        rule.onNodeWithText("Refunddnuf").assertIsDisplayed()
    }

    @Test
    fun errorStateRetries() {
        var retried = false
        show(ScanUiState.Error("boom"), ScanCallbacks(onRescan = { retried = true }))
        rule.onNodeWithTag(PayFlowTestTags.ERROR).assertIsDisplayed()
        rule.onNodeWithText("Retry").performClick()
        assertThat(retried).isTrue()
    }

    @Test
    fun galleryActionIsOfferedOnTheCameraAndOnBothPermissionStates() {
        var picks = 0
        show(ScanUiState.Scanning(torchOn = false, hint = null), ScanCallbacks(onPickImage = { picks++ }), reduceMotion = true)
        rule.onNodeWithContentDescription("Upload a QR code image from your gallery").assertIsDisplayed().performClick()
        holder.value = ScanUiState.PermissionRequired(showRationale = true)
        rule.onNodeWithTag(PayFlowTestTags.SCAN_GALLERY).assertIsDisplayed().performClick()
        holder.value = ScanUiState.PermissionDenied
        pastDebounce()
        rule.onNodeWithTag(PayFlowTestTags.SCAN_GALLERY).assertIsDisplayed().performClick()
        assertThat(picks).isEqualTo(3)
    }

    @Test
    fun decodingAndImageErrorStates() {
        var picks = 0; var dismissed = false
        show(ScanUiState.DecodingImage, ScanCallbacks(onPickImage = { picks++ }, onDismissImageError = { dismissed = true }), reduceMotion = true)
        rule.onNodeWithText("Reading the QR code…").assertIsDisplayed()
        holder.value = ScanUiState.ImageFailed(ImageError.NOT_UPI, cameraAvailable = true)
        rule.onNodeWithText("This image has a QR code, but it isn't a UPI payment QR").assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.SCAN_TRY_ANOTHER).performClick()
        assertThat(picks).isEqualTo(1)
        rule.onNodeWithText("Scan with camera").performClick()
        assertThat(dismissed).isTrue()
        holder.value = ScanUiState.ImageFailed(ImageError.NO_QR, cameraAvailable = false)
        rule.onNodeWithText("No QR code found in this image").assertIsDisplayed()
        rule.onNodeWithText("Back").assertIsDisplayed() // no camera to go back to: plain back instead
        holder.value = ScanUiState.ImageFailed(ImageError.UNREADABLE, cameraAvailable = false)
        rule.onNodeWithText("This image could not be read. Try a screenshot or a clearer photo.").assertIsDisplayed()
    }

    @Test
    fun theCameraOverlayFitsAtLargeFont() {
        show(ScanUiState.Scanning(torchOn = false, hint = QrError.UNSUPPORTED), fontScale = 1.3f, reduceMotion = true)
        rule.onNodeWithText("This UPI QR type is not supported").assertIsDisplayed()
        rule.onNodeWithTag(PayFlowTestTags.SCAN_GALLERY).assertIsDisplayed()
    }

    @Test
    fun noStockSpinnerAnywhere() {
        show(ScanUiState.DecodingImage, reduceMotion = true)
        rule.onAllNodesWithTag(PayFlowTestTags.LOADING).assertCountEquals(1)
    }
}
