package com.darshan.payflow.data.qr

import com.darshan.payflow.data.upi.QrCodeGenerator
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/** Pure-JVM tests for the gallery decoder: zxing round trips on rendered pixels, the result mapping and the downsampling maths. */
class ImageQrDecodingTest {
    /** Renders [contents] as QR codes side by side on a white canvas (ARGB ints), [scale] px per module, with a margin. */
    private fun render(vararg contents: String, scale: Int = 4, margin: Int = 40): Triple<IntArray, Int, Int> {
        val matrices = contents.map { QrCodeGenerator.encode(it) }
        val width = matrices.sumOf { it.size * scale } + margin * (matrices.size + 1)
        val height = matrices.maxOf { it.size * scale } + margin * 2
        val pixels = IntArray(width * height) { WHITE }
        var left = margin
        for (m in matrices) {
            for (y in 0 until m.size * scale) for (x in 0 until m.size * scale) {
                if (m[x / scale, y / scale]) pixels[(margin + y) * width + left + x] = BLACK
            }
            left += m.size * scale + margin
        }
        return Triple(pixels, width, height)
    }

    private fun decode(image: Triple<IntArray, Int, Int>) = ZxingQrImageDecoder.decode(image.first, image.second, image.third)

    @Test
    fun `a rendered upi qr decodes back and maps to the same payload a camera scan would give`() {
        val link = "upi://pay?pa=shop@okbank&pn=Chai%20Point&am=45.00&cu=INR&tn=Table%204"
        val texts = decode(render(link))
        assertThat(texts).containsExactly(link)
        val found = ImageQrResults.fromTexts(texts) as ImageQrResult.Found
        assertThat(found.payload).isEqualTo((UpiQrPayloadParser.parse(link) as QrParseResult.Valid).payload)
        assertThat(found.payload.amountPaise).isEqualTo(4_500)
    }

    @Test
    fun `an image without a qr finds nothing`() {
        val random = Random(3)
        val noise = IntArray(300 * 300) { if (random.nextInt(10) == 0) BLACK else WHITE }
        assertThat(ZxingQrImageDecoder.decode(noise, 300, 300)).isEmpty()
        assertThat(ZxingQrImageDecoder.decode(IntArray(200 * 200) { WHITE }, 200, 200)).isEmpty()
        assertThat(ImageQrResults.fromTexts(emptyList())).isEqualTo(ImageQrResult.NoQrFound)
    }

    @Test
    fun `two qrs in one image are both found and the first valid upi one wins`() {
        val texts = decode(render("https://example.com/menu", "upi://pay?pa=alice@okbank&pn=Alice", "upi://pay?pa=bob@upi&pn=Bob"))
        assertThat(texts).hasSize(3)
        val multiple = ImageQrResults.fromTexts(texts) as ImageQrResult.MultipleFound
        assertThat(multiple.payloads.map { it.payeeVpa }).containsExactly("alice@okbank", "bob@upi").inOrder()
        assertThat(multiple.first.payeeVpa).isEqualTo("alice@okbank")
    }

    @Test
    fun `qrs that are not valid upi payment qrs are reported as such, never as payable`() {
        listOf(
            "https://example.com", "WIFI:S:home;T:WPA;P:secret;;", "upi://mandate?pa=a@okbank", "upi://pay?pa=javascript:alert(1)@x",
            "upi://pay?pa=‮alice@okbank", "upi://pay?pa=alice@okbank&am=-5", "upi://pay?pa=alice@okbank&cu=USD", "upi://pay",
        ).forEach { raw ->
            assertThat(ImageQrResults.fromTexts(listOf(raw))).isInstanceOf(ImageQrResult.NotUpi::class.java)
        }
        // An oversized but valid payload: free text is capped by the parser, extra parameters (url=) are ignored, never followed.
        val big = "upi://pay?pa=alice@okbank&pn=Alice&url=http://evil.example&tn=" + "x".repeat(1_500)
        val found = ImageQrResults.fromTexts(decode(render(big, scale = 2))) as ImageQrResult.Found
        assertThat(found.payload.note.length).isAtMost(UpiQrPayloadParser.MAX_TEXT_LENGTH)
        // Blank and duplicate texts are ignored.
        assertThat(ImageQrResults.fromTexts(listOf(" ", "upi://pay?pa=alice@okbank", "upi://pay?pa=alice@okbank"))).isInstanceOf(ImageQrResult.Found::class.java)
    }

    @Test
    fun `huge images are downsampled to the longest-side cap and exif rotations are mapped`() {
        assertThat(ImageSampling.sampleSize(8_160, 6_120, 2_048)).isEqualTo(4) // 50 MP photo → 2040 x 1530
        assertThat(ImageSampling.sampleSize(2_048, 1_000, 2_048)).isEqualTo(1)
        assertThat(ImageSampling.sampleSize(2_049, 10, 2_048)).isEqualTo(2)
        assertThat(ImageSampling.sampleSize(100_000, 100_000, 2_048)).isEqualTo(64)
        assertThat(ImageSampling.sampleSize(0, -1, 2_048)).isEqualTo(1)
        assertThat(ImageSampling.rotationDegrees(ImageSampling.EXIF_ROTATE_90)).isEqualTo(90)
        assertThat(ImageSampling.rotationDegrees(ImageSampling.EXIF_ROTATE_180)).isEqualTo(180)
        assertThat(ImageSampling.rotationDegrees(ImageSampling.EXIF_ROTATE_270)).isEqualTo(270)
        assertThat(ImageSampling.rotationDegrees(0)).isEqualTo(0)
    }

    @Test
    fun `garbage pixel buffers never throw`() {
        assertThat(ZxingQrImageDecoder.decode(IntArray(0), 0, 0)).isEmpty()
        assertThat(ZxingQrImageDecoder.decode(IntArray(10), 100, 100)).isEmpty() // size mismatch
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}
