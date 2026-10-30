package com.darshan.payflow.data.qr

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.testutil.TestDispatcherProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The device decoder's failure paths (ML Kit itself is device-only): corrupt or missing images are `Unreadable`, never a crash. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real BitmapFactory: legacy mode would fabricate a bitmap from any bytes
class AndroidImageQrDecoderTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val decoder = AndroidImageQrDecoder(context, TestDispatcherProvider(Dispatchers.Unconfined))

    @Test
    fun `a corrupt file, a missing file and a non-content uri are unreadable`() = runBlocking {
        val corrupt = File(context.cacheDir, "corrupt.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3) + ByteArray(500) { 7 }) }
        assertThat(decoder.decode(android.net.Uri.fromFile(corrupt).toString())).isEqualTo(ImageQrResult.Unreadable)
        assertThat(decoder.decode(android.net.Uri.fromFile(File(context.cacheDir, "missing.png")).toString())).isEqualTo(ImageQrResult.Unreadable)
        assertThat(decoder.decode("not a uri at all")).isEqualTo(ImageQrResult.Unreadable)
        assertThat(decoder.decode("")).isEqualTo(ImageQrResult.Unreadable)
    }
}
