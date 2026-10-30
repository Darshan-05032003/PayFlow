package com.darshan.payflow.ui.scan

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.darshan.payflow.data.qr.QrError
import com.darshan.payflow.testutil.MainDispatcherRule
import com.darshan.payflow.testutil.TestDispatcherProvider
import com.darshan.payflow.data.qr.ImageQrDecoder
import com.darshan.payflow.data.qr.ImageQrResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val mainRule = MainDispatcherRule(dispatcher)

    private val decoder = FakeImageQrDecoder()

    private fun vm(handle: SavedStateHandle = SavedStateHandle()) = ScanViewModel(decoder, TestDispatcherProvider(dispatcher), handle)

    /** Scripted gallery decoder; [gate] lets a test hold the decode to observe the in-progress state. */
    class FakeImageQrDecoder : ImageQrDecoder {
        var result: ImageQrResult = ImageQrResult.NoQrFound
        val decoded = mutableListOf<String>()
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun decode(uri: String): ImageQrResult {
            decoded += uri
            gate?.await()
            return result
        }
    }

    private fun payload(raw: String) = (com.darshan.payflow.data.qr.UpiQrPayloadParser.parse(raw) as com.darshan.payflow.data.qr.QrParseResult.Valid).payload

    @Test
    fun `a picked gallery image is decoded off the main path and flows into the same pay event as a camera scan`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val vm = vm(handle)
        vm.onPermissionResult(granted = true, canAskAgain = true)
        decoder.result = ImageQrResult.Found(payload("upi://pay?pa=shop@okbank&pn=Chai%20Point&am=45.00"))
        decoder.gate = kotlinx.coroutines.CompletableDeferred()
        vm.events.test {
            vm.onImagePicked("content://media/picker/0/42")
            dispatcher.scheduler.runCurrent()
            assertThat(vm.uiState.value).isEqualTo(ScanUiState.DecodingImage)
            decoder.gate!!.complete(Unit)
            dispatcher.scheduler.advanceUntilIdle()
            val event = awaitItem() as ScanEvent.PayloadReady
            assertThat(event.payload.payeeVpa).isEqualTo("shop@okbank")
            assertThat(event.payload.amountPaise).isEqualTo(4_500)
        }
        assertThat(vm.uiState.value).isInstanceOf(ScanUiState.Found::class.java)
        assertThat(decoder.decoded).containsExactly("content://media/picker/0/42")
        // The URI is never persisted; the decoded payload is, exactly like a camera scan, so rotation restores the result.
        assertThat(handle.keys().map { handle.get<Any>(it) }).doesNotContain("content://media/picker/0/42")
        assertThat(handle.get<String>(ScanViewModel.KEY_FOUND_RAW)).isEqualTo("upi://pay?pa=shop@okbank&pn=Chai%20Point&am=45.00")
    }

    @Test
    fun `multiple qrs pick the first valid upi one`() = runTest(dispatcher) {
        val vm = vm()
        decoder.result = ImageQrResult.MultipleFound(listOf(payload("upi://pay?pa=alice@okbank"), payload("upi://pay?pa=bob@upi")))
        vm.onImagePicked("content://x/1"); dispatcher.scheduler.advanceUntilIdle()
        assertThat((vm.uiState.value as ScanUiState.Found).payload.payeeVpa).isEqualTo("alice@okbank")
    }

    @Test
    fun `no qr, a non-upi qr and an unreadable image each get their own error, survive rotation and allow another try`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val vm = vm(handle)
        vm.onPermissionResult(granted = false, canAskAgain = false) // camera refused: the gallery still works
        mapOf(
            ImageQrResult.NoQrFound to ImageError.NO_QR,
            ImageQrResult.NotUpi(QrError.NOT_UPI) to ImageError.NOT_UPI,
            ImageQrResult.Unreadable to ImageError.UNREADABLE,
        ).forEach { (result, error) ->
            decoder.result = result
            vm.onImagePicked("content://x/${error.name}"); dispatcher.scheduler.advanceUntilIdle()
            assertThat(vm.uiState.value).isEqualTo(ScanUiState.ImageFailed(error, cameraAvailable = false))
            assertThat(vm(handle).uiState.value).isEqualTo(ScanUiState.ImageFailed(error, cameraAvailable = false)) // recreated after rotation
        }
        vm.dismissImageError()
        assertThat(vm.uiState.value).isEqualTo(ScanUiState.PermissionDenied) // back to where the user was
        assertThat(handle.get<String>(ScanViewModel.KEY_IMAGE_ERROR)).isNull()
    }

    @Test
    fun `a cancelled picker changes nothing and frames are ignored while an image decodes`() = runTest(dispatcher) {
        val vm = vm()
        vm.onPermissionResult(granted = true, canAskAgain = true)
        vm.onImagePicked(null)
        assertThat(vm.uiState.value).isEqualTo(ScanUiState.Scanning(torchOn = false, hint = null))
        assertThat(decoder.decoded).isEmpty()
        decoder.gate = kotlinx.coroutines.CompletableDeferred()
        vm.onImagePicked("content://x/2"); dispatcher.scheduler.runCurrent()
        vm.onBarcode("upi://pay?pa=mallory@evil") // a stale camera frame must not hijack the gallery result
        vm.onImagePicked("content://x/3") // nor a second pick
        assertThat(vm.uiState.value).isEqualTo(ScanUiState.DecodingImage)
        decoder.gate!!.complete(Unit); dispatcher.scheduler.advanceUntilIdle()
        assertThat(decoder.decoded).containsExactly("content://x/2")
        assertThat(vm.uiState.value).isEqualTo(ScanUiState.ImageFailed(ImageError.NO_QR, cameraAvailable = true))
        vm.dismissImageError()
        assertThat(vm.uiState.value).isEqualTo(ScanUiState.Scanning(torchOn = false, hint = null))
    }

    @Test
    fun `starts by checking permission`() {
        assertThat(vm().uiState.value).isEqualTo(ScanUiState.CheckingPermission)
    }

    @Test
    fun `granted permission starts scanning, denial shows rationale or settings`() {
        val vm = vm()
        vm.onPermissionResult(granted = true, canAskAgain = true)
        assertThat(vm.uiState.value).isEqualTo(ScanUiState.Scanning(torchOn = false, hint = null))
        vm.onPermissionResult(granted = false, canAskAgain = true)
        assertThat(vm.uiState.value).isEqualTo(ScanUiState.PermissionRequired(showRationale = true))
        vm.onPermissionResult(granted = false, canAskAgain = false)
        assertThat(vm.uiState.value).isEqualTo(ScanUiState.PermissionDenied)
    }

    @Test
    fun `a valid qr stops scanning, exposes the payload and emits a pay event`() = runTest(dispatcher) {
        val vm = vm()
        vm.onPermissionResult(granted = true, canAskAgain = true)
        vm.events.test {
            vm.onBarcode("upi://pay?pa=shop@okbank&pn=Chai%20Point&am=45.00&cu=INR")
            val event = awaitItem() as ScanEvent.PayloadReady
            assertThat(event.payload.payeeVpa).isEqualTo("shop@okbank")
        }
        val found = vm.uiState.value as ScanUiState.Found
        assertThat(found.payload.amountPaise).isEqualTo(4_500)
        // further frames are ignored while a result is shown
        vm.onBarcode("upi://pay?pa=mallory@evil&am=1")
        assertThat((vm.uiState.value as ScanUiState.Found).payload.payeeVpa).isEqualTo("shop@okbank")
    }

    @Test
    fun `an invalid qr keeps scanning and shows the reason as a hint`() = runTest(dispatcher) {
        val vm = vm()
        vm.onPermissionResult(granted = true, canAskAgain = true)
        vm.events.test {
            vm.onBarcode("https://example.com")
            expectNoEvents()
        }
        assertThat(vm.uiState.value).isEqualTo(ScanUiState.Scanning(torchOn = false, hint = QrError.NOT_UPI))
        vm.onBarcode("upi://pay?pa=alice@okbank&am=-5")
        assertThat(vm.uiState.value).isEqualTo(ScanUiState.Scanning(torchOn = false, hint = QrError.INVALID_AMOUNT))
    }

    @Test
    fun `frames before permission or while checking are ignored`() {
        val vm = vm()
        vm.onBarcode("upi://pay?pa=alice@okbank")
        assertThat(vm.uiState.value).isEqualTo(ScanUiState.CheckingPermission)
    }

    @Test
    fun `rescan and torch`() {
        val vm = vm()
        vm.onPermissionResult(granted = true, canAskAgain = true)
        vm.toggleTorch()
        assertThat((vm.uiState.value as ScanUiState.Scanning).torchOn).isTrue()
        vm.onBarcode("upi://pay?pa=alice@okbank")
        assertThat(vm.uiState.value).isInstanceOf(ScanUiState.Found::class.java)
        vm.rescan()
        assertThat(vm.uiState.value).isEqualTo(ScanUiState.Scanning(torchOn = true, hint = null))
    }

    @Test
    fun `found payload survives process death through saved state`() {
        val handle = SavedStateHandle()
        val vm1 = vm(handle)
        vm1.onPermissionResult(granted = true, canAskAgain = true)
        vm1.onBarcode("upi://pay?pa=alice@okbank&am=12.50")
        val vm2 = vm(handle)
        vm2.onPermissionResult(granted = true, canAskAgain = true)
        val found = vm2.uiState.value as ScanUiState.Found
        assertThat(found.payload.payeeVpa).isEqualTo("alice@okbank")
        assertThat(found.payload.amountPaise).isEqualTo(1_250)
    }

    @Test
    fun `camera failure is an error state with retry`() {
        val vm = vm()
        vm.onPermissionResult(granted = true, canAskAgain = true)
        vm.onCameraError(IllegalStateException("no camera"))
        assertThat(vm.uiState.value).isInstanceOf(ScanUiState.Error::class.java)
        vm.rescan()
        assertThat(vm.uiState.value).isInstanceOf(ScanUiState.Scanning::class.java)
    }
}
