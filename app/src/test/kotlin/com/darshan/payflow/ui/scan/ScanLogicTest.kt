package com.darshan.payflow.ui.scan

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.darshan.payflow.data.qr.ImageQrDecoder
import com.darshan.payflow.data.qr.ImageQrResult
import com.darshan.payflow.data.qr.QrParseResult
import com.darshan.payflow.data.qr.UpiQrPayloadParser
import com.darshan.payflow.testutil.MainDispatcherRule
import com.darshan.payflow.testutil.TestDispatcherProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ViewfinderMotionTest {
    @Test
    fun `brackets breathe inside a small range and never cover the code`() {
        val samples = (0..100).map { ViewfinderMotion.breath(it / 100f) }
        assertThat(samples.min()).isAtLeast(0f)
        assertThat(samples.max()).isAtMost(ViewfinderMotion.BREATH_DEPTH)
        assertThat(ViewfinderMotion.breath(0f)).isWithin(1e-4f).of(ViewfinderMotion.breath(1f))
    }

    @Test
    fun `lock-on draws the frame in to the code size and keeps it square`() {
        assertThat(ViewfinderMotion.lockScale(0f)).isEqualTo(1f)
        assertThat(ViewfinderMotion.lockScale(1f)).isEqualTo(ViewfinderMotion.LOCKED_SCALE)
        assertThat(ViewfinderMotion.lockScale(0.5f)).isIn(com.google.common.collect.Range.open(ViewfinderMotion.LOCKED_SCALE, 1f))
    }

    @Test
    fun `the beam sweeps top to bottom and back, staying inside the frame`() {
        val ys = (0..200).map { ViewfinderMotion.beam(it / 200f) }
        assertThat(ys.min()).isAtLeast(0f)
        assertThat(ys.max()).isAtMost(1f)
        assertThat(ViewfinderMotion.beam(0f)).isWithin(1e-4f).of(0f)
        assertThat(ViewfinderMotion.beam(0.5f)).isWithin(1e-4f).of(1f)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScanMultipleCodesTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val mainRule = MainDispatcherRule(dispatcher)

    private fun payload(raw: String) = (UpiQrPayloadParser.parse(raw) as QrParseResult.Valid).payload

    @Test
    fun `an image with several codes waits for the user to confirm which payee, and says how many there were`() = runTest(dispatcher) {
        val decoder = object : ImageQrDecoder {
            override suspend fun decode(uri: String) = ImageQrResult.MultipleFound(listOf(payload("upi://pay?pa=alice@okbank"), payload("upi://pay?pa=bob@upi"), payload("upi://pay?pa=carol@ybl")))
        }
        val vm = ScanViewModel(decoder, TestDispatcherProvider(dispatcher), SavedStateHandle())
        vm.events.test {
            vm.onImagePicked("content://x/1"); dispatcher.scheduler.advanceUntilIdle()
            expectNoEvents()
        }
        val found = vm.uiState.value as ScanUiState.Found
        assertThat(found.payload.payeeVpa).isEqualTo("alice@okbank")
        assertThat(found.codesInImage).isEqualTo(3)
    }

    @Test
    fun `a single code still goes straight on`() = runTest(dispatcher) {
        val decoder = object : ImageQrDecoder {
            override suspend fun decode(uri: String) = ImageQrResult.Found(payload("upi://pay?pa=alice@okbank"))
        }
        val vm = ScanViewModel(decoder, TestDispatcherProvider(dispatcher), SavedStateHandle())
        vm.events.test {
            vm.onImagePicked("content://x/1"); dispatcher.scheduler.advanceUntilIdle()
            assertThat(awaitItem()).isInstanceOf(ScanEvent.PayloadReady::class.java)
        }
        assertThat((vm.uiState.value as ScanUiState.Found).codesInImage).isEqualTo(1)
    }
}
