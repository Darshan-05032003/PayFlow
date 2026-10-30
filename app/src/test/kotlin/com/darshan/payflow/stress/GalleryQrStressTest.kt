package com.darshan.payflow.stress

import com.darshan.payflow.data.qr.ImageQrResult
import com.darshan.payflow.data.qr.ImageQrResults
import com.darshan.payflow.data.qr.ZxingQrImageDecoder
import com.darshan.payflow.data.upi.QrCodeGenerator
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/** Gallery QR stress: 1,000 decodes of generated images (valid, non-UPI, hostile, noise) through the zxing path + result mapping. */
class GalleryQrStressTest {
    private fun render(content: String, scale: Int): Triple<IntArray, Int, Int> {
        val m = QrCodeGenerator.encode(content)
        val margin = 24
        val side = m.size * scale + margin * 2
        val px = IntArray(side * side) { WHITE }
        for (y in 0 until m.size * scale) for (x in 0 until m.size * scale) if (m[x / scale, y / scale]) px[(margin + y) * side + margin + x] = BLACK
        return Triple(px, side, side)
    }

    @Test
    fun thousandGalleryDecodes() {
        val random = Random(5)
        val counts = HashMap<String, Int>()
        val runtime = Runtime.getRuntime()
        System.gc()
        val before = runtime.totalMemory() - runtime.freeMemory()
        var peak = before
        val ms = measureTimeMillis {
            repeat(1_000) { i ->
                val image = when (i % 5) {
                    0, 1 -> render("upi://pay?pa=shop$i@okbank&pn=Shop%20$i&am=${1 + random.nextInt(99_999)}.00&cu=INR", scale = 2 + random.nextInt(4))
                    2 -> render("https://example.com/menu/$i", scale = 3)
                    3 -> render("upi://pay?pa=javascript:alert($i)@x&url=http://evil&tn=" + "x".repeat(random.nextInt(800)), scale = 2)
                    else -> Triple(IntArray(320 * 320) { if (random.nextInt(8) == 0) BLACK else WHITE }, 320, 320)
                }
                val result = ImageQrResults.fromTexts(ZxingQrImageDecoder.decode(image.first, image.second, image.third))
                val key = result::class.simpleName!!
                counts[key] = (counts[key] ?: 0) + 1
                if (i % 50 == 0) peak = maxOf(peak, runtime.totalMemory() - runtime.freeMemory())
            }
        }
        println("STRESS [gallery-qr] 1,000 image decodes: ${ms} ms (${"%.2f".format(ms / 1_000.0)} ms each), results=$counts, heap peak +${(peak - before) / 1_048_576} MB")
        assertThat(counts["Found"]).isEqualTo(400)
        assertThat(counts["NotUpi"]).isEqualTo(400)
        assertThat(counts["NoQrFound"]).isEqualTo(200)
        assertThat(counts.keys).doesNotContain(ImageQrResult.Unreadable::class.simpleName)
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}
