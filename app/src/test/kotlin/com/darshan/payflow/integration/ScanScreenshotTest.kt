package com.darshan.payflow.integration

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.data.qr.QrError
import com.darshan.payflow.data.qr.QrParseResult
import com.darshan.payflow.data.qr.UpiQrPayloadParser
import com.darshan.payflow.testutil.FakeCameraController
import com.darshan.payflow.ui.designsystem.captureScreenshot
import com.darshan.payflow.ui.designsystem.setDs
import com.darshan.payflow.ui.scan.ImageError
import com.darshan.payflow.ui.scan.ScanCallbacks
import com.darshan.payflow.ui.scan.ScanScreen
import com.darshan.payflow.ui.scan.ScanUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Scan in each state. The camera itself is a black preview on the JVM, which is what the overlay is designed against. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ScanScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val payload = (UpiQrPayloadParser.parse("upi://pay?pa=monsoonroast@ybl&pn=Monsoon%20Roast%20Coffee&am=420.00&tn=Table%206") as QrParseResult.Valid).payload
    private val staticPayload = (UpiQrPayloadParser.parse("upi://pay?pa=asha@okicici&pn=Asha%20Rao") as QrParseResult.Valid).payload

    private fun show(name: String, state: ScanUiState, dark: Boolean = false, fontScale: Float = 1f) {
        rule.setDs(dark = dark, fontScale = fontScale, reduceMotion = true) { ScanScreen(state, ScanCallbacks(), FakeCameraController()) }
        rule.waitForIdle()
        rule.captureScreenshot("scan/$name")
    }

    @Test fun cameraLight() = show("camera_light", ScanUiState.Scanning(torchOn = false, hint = null))
    @Test fun cameraHintLarge() = show("camera_hint_large_font", ScanUiState.Scanning(torchOn = true, hint = QrError.NOT_UPI), fontScale = 1.3f)
    @Test fun permissionLight() = show("permission_light", ScanUiState.PermissionRequired(showRationale = true))
    @Test fun deniedDark() = show("denied_dark", ScanUiState.PermissionDenied, dark = true)
    @Test fun deniedLarge() = show("denied_large_font", ScanUiState.PermissionDenied, fontScale = 1.3f)
    @Test fun foundLight() = show("found_light", ScanUiState.Found(payload))
    @Test fun foundDark() = show("found_dark", ScanUiState.Found(payload), dark = true)
    @Test fun foundMultipleLight() = show("found_multiple_light", ScanUiState.Found(staticPayload, codesInImage = 2))
    @Test fun decodingDark() = show("decoding_dark", ScanUiState.DecodingImage, dark = true)
    @Test fun imageNotUpiLight() = show("image_not_upi_light", ScanUiState.ImageFailed(ImageError.NOT_UPI, cameraAvailable = true))
    @Test fun imageUnreadableDark() = show("image_unreadable_dark", ScanUiState.ImageFailed(ImageError.UNREADABLE, cameraAvailable = false), dark = true)
    @Test fun cameraErrorLight() = show("camera_error_light", ScanUiState.Error("busy"))
}
