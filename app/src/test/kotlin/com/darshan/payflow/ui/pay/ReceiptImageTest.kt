package com.darshan.payflow.ui.pay

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.R
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.StatusSource
import com.darshan.payflow.testutil.testPayment
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ReceiptImageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun payment(status: PaymentStatus = PaymentStatus.SUCCESS, source: StatusSource = StatusSource.PSP, handler: String? = null, name: String = "Rahul Verma"): Payment =
        testPayment("internal-id-1", txnRef = "PF0MV123", status = status, payeeName = name, payeeVpa = "rahul@okaxis", amountPaise = 12_345_67)
            .copy(statusSource = source, handlerPackage = handler, note = "Rent for flat 4B", pspTxnId = "PSPSECRET1", upiTxnId = "UPI777", idempotencyKey = "idem-key-1", failureReason = "raw reason")

    @Test
    fun `the image's words are the record's status and source, and never more than proof of payment`() {
        val receipt = ReceiptContent.from(payment(source = StatusSource.UPI_APP, handler = "com.example.gpay"), "GPay")
        val words = (ReceiptImage.lines(receipt, context).flatMap { listOf(it.label, it.value) } + ReceiptImage.sourceLine(receipt, context)).joinToString(" | ")
        assertThat(words).contains("Reported by GPay. Not verified by PayFlow.")
        assertThat(words).contains("PF0MV123")
        assertThat(words).contains("UPI777")
        for (leak in listOf("internal-id-1", "PSPSECRET1", "idem-key-1", "raw reason", "com.example.gpay", "Rent for flat")) {
            assertThat(words).doesNotContain(leak)
        }
    }

    @Test
    fun `it renders for every status and for the longest hostile name`() {
        for (status in PaymentStatus.entries) {
            val bitmap = ReceiptImage.render(ReceiptContent.from(payment(status), null), context)
            assertThat(bitmap.width).isEqualTo(1080)
            assertThat(bitmap.height).isGreaterThan(600)
        }
        val hostile = "‮" + "مرحبا🎉".repeat(40) + "\nStatus: Success"
        val bitmap = ReceiptImage.render(ReceiptContent.from(payment(status = PaymentStatus.FAILED, name = hostile), null), context)
        assertThat(bitmap.height).isLessThan(2_400) // a name can't stretch the receipt without limit
    }

    @Test
    fun `the file lands only in the private receipts folder, keeps one, and shares read-only`() {
        val bitmap = ReceiptImage.render(ReceiptContent.from(payment(), null), context)
        val first = ReceiptExporter.write(context, bitmap, "one.png")
        val second = ReceiptExporter.write(context, bitmap, "two.png")
        assertThat(first.exists()).isFalse()
        assertThat(second.parentFile).isEqualTo(File(context.cacheDir, "receipts"))
        assertThat(BitmapFactory.decodeFile(second.path).width).isEqualTo(1080)
        val uri = ReceiptExporter.uriOf(context, second)
        assertThat(uri.authority).isEqualTo("${context.packageName}.fileprovider")
        val share = ReceiptExporter.shareIntent(uri)
        assertThat(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        assertThat(share.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isEqualTo(0)
        ReceiptExporter.clear(context)
        assertThat(second.exists()).isFalse()
    }

    @Test
    fun `the file provider exposes only the two cache folders`() {
        val parser = context.resources.getXml(R.xml.file_paths)
        val paths = mutableListOf<Pair<String, String?>>()
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name != "paths") paths += parser.name to parser.getAttributeValue(null, "path")
            event = parser.next()
        }
        assertThat(paths).containsExactly("cache-path" to "qr/", "cache-path" to "receipts/")
    }
}
