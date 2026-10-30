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

    /** Writes [content] as a QR PNG ([scale] px per module) into the cache dir, optionally rotated, and returns its file URI. */
    private fun qrPng(name: String, content: String, scale: Int = 8, rotate: Float = 0f): String {
        val m = com.darshan.payflow.data.upi.QrCodeGenerator.encode(content)
        val side = m.size * scale + 80
        var bitmap = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        for (y in 0 until m.size * scale) for (x in 0 until m.size * scale) if (m[x / scale, y / scale]) bitmap.setPixel(40 + x, 40 + y, android.graphics.Color.BLACK)
        if (rotate != 0f) bitmap = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, side, side, android.graphics.Matrix().apply { postRotate(rotate) }, true)
        val file = File(context.cacheDir, name)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        return android.net.Uri.fromFile(file).toString()
    }

    @Test
    fun `a real qr png is read through the resolver and decoded to the upi payload`() = runBlocking {
        // Regression: the bounds-only decode returns null by design; treating that as "unreadable" rejected every real image on a device.
        val link = "upi://pay?pa=chaipoint@okaxis&pn=Chai%20Point&am=45.00&cu=INR&tn=Table%204"
        val result = decoder.decode(qrPng("upi.png", link)) as ImageQrResult.Found
        assertThat(result.payload.payeeVpa).isEqualTo("chaipoint@okaxis")
        assertThat(result.payload.amountPaise).isEqualTo(4_500)
        // A rotated screenshot still decodes (zxing tries rotations of the binary image), a URL QR is "not UPI".
        assertThat(decoder.decode(qrPng("rotated.png", link, rotate = 90f))).isInstanceOf(ImageQrResult.Found::class.java)
        assertThat(decoder.decode(qrPng("url.png", "https://example.com/menu"))).isInstanceOf(ImageQrResult.NotUpi::class.java)
    }

    @Test
    fun `a large image is downsampled before decoding and still yields the payload`() = runBlocking {
        // 33 px per module → about 3,000 px square: decoded at half size (longest side ≤ 2,048).
        val result = decoder.decode(qrPng("large.png", "upi://pay?pa=alice@okbank&pn=Alice", scale = 100))
        assertThat(result).isInstanceOf(ImageQrResult.Found::class.java)
    }
}
