package com.darshan.payflow.ui.pay

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.darshan.payflow.R
import com.darshan.payflow.core.Money
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.StatusSource
import com.darshan.payflow.ui.designsystem.tokens.AurumLight
import java.io.File
import java.time.ZoneId

/**
 * One row of the shared image. Its contents come from [ReceiptContent], which is why it can't say more than the text does.
 * Pure, so the "no leak" rule can be tested without drawing.
 */
data class ReceiptImageLine(val label: String?, val value: String)

object ReceiptImage {
    private const val WIDTH = 1080
    private const val MARGIN = 72f
    private const val AMOUNT_SP = 96f
    private const val TITLE_SP = 44f
    private const val BODY_SP = 34f
    private const val LABEL_SP = 28f
    private const val GAP = 24f
    private const val TOOTH = 12f

    /** The words on the receipt. Status and source come from the record; nothing else is added. */
    fun lines(receipt: ReceiptContent, context: Context, zone: ZoneId = ZoneId.systemDefault()): List<ReceiptImageLine> = buildList {
        add(ReceiptImageLine(context.getString(R.string.detail_vpa), receipt.payeeVpa))
        add(ReceiptImageLine(context.getString(R.string.detail_txn_ref), receipt.reference))
        receipt.upiTxnId?.let { add(ReceiptImageLine(context.getString(R.string.detail_upi_txn), it)) }
        receipt.source.appLabel?.let { add(ReceiptImageLine(context.getString(R.string.detail_paid_via), it)) }
        add(ReceiptImageLine(context.getString(R.string.pay_receipt_time), receipt.timeText(zone)))
    }

    /** The same honest sentence the screen shows, including "Not verified by PayFlow." */
    fun sourceLine(receipt: ReceiptContent, context: Context): String {
        val app = receipt.source.appLabel ?: context.getString(R.string.pay_source_app_generic)
        val pending = receipt.status == PaymentStatus.CREATED || receipt.status == PaymentStatus.PENDING
        return when {
            pending && receipt.source.upiAppPayment -> context.getString(R.string.pay_source_unconfirmed, app)
            pending -> context.getString(R.string.pay_source_pending_psp)
            receipt.source.statusSource == StatusSource.UPI_APP -> context.getString(R.string.pay_source_reported_by, app)
            receipt.source.statusSource == StatusSource.USER -> context.getString(R.string.pay_source_marked_by_user)
            receipt.status == PaymentStatus.REFUNDED || receipt.status == PaymentStatus.REFUND_PENDING -> context.getString(R.string.pay_source_refund)
            else -> context.getString(R.string.pay_source_psp)
        }
    }

    /** Draws off screen, so `FLAG_SECURE` doesn't apply: the user asked to share this, and it holds only [lines]. */
    fun render(receipt: ReceiptContent, context: Context, zone: ZoneId = ZoneId.systemDefault()): Bitmap {
        val serif = runCatching { ResourcesCompat.getFont(context, R.font.newsreader_variable) }.getOrNull() ?: Typeface.SERIF
        val sans = runCatching { ResourcesCompat.getFont(context, R.font.geist_variable) }.getOrNull() ?: Typeface.SANS_SERIF
        val colors = AurumLight
        val ink = colors.text.toArgb()
        val muted = colors.textSecondary.toArgb()
        val inner = (WIDTH - MARGIN * 2).toInt()

        fun paint(face: Typeface, sizePx: Float, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            textSize = sizePx
            this.color = color
        }
        fun layout(text: String, paint: TextPaint, width: Int = inner, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, maxLines: Int = 3) =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(align).setMaxLines(maxLines).setEllipsize(android.text.TextUtils.TruncateAt.END).build()

        val blocks = mutableListOf<Pair<StaticLayout, Float>>()
        fun block(text: String, paint: TextPaint, after: Float = GAP, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) {
            blocks += layout(text, paint, align = align) to after
        }
        block(context.getString(R.string.app_name), paint(serif, TITLE_SP, ink), GAP * 2)
        block(Money.formatInr(receipt.amountPaise), paint(serif, AMOUNT_SP, ink))
        block(receipt.payeeName, paint(sans, BODY_SP, ink))
        block(ReceiptContent.statusLabel(receipt.status), paint(sans, BODY_SP, statusColor(receipt.status)), GAP / 2)
        block(sourceLine(receipt, context), paint(sans, LABEL_SP, muted), GAP * 2)
        for (line in lines(receipt, context, zone)) {
            line.label?.let { block(it, paint(sans, LABEL_SP, muted), 4f) }
            block(line.value, paint(sans, BODY_SP, ink), GAP)
        }
        if (!receipt.source.upiAppPayment) block(context.getString(R.string.pay_receipt_sandbox), paint(sans, LABEL_SP, muted))

        val contentHeight = blocks.sumOf { (l, after) -> (l.height + after).toDouble() }.toFloat()
        val height = (MARGIN * 2 + contentHeight + TOOTH * 2).toInt()
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(colors.bg.toArgb())
        val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.surface.toArgb() }
        canvas.drawPath(perforatedPaper(WIDTH.toFloat(), height.toFloat()), paper)
        canvas.translate(MARGIN, MARGIN)
        for ((l, after) in blocks) {
            l.draw(canvas)
            canvas.translate(0f, l.height + after)
        }
        return bitmap
    }

    private fun statusColor(status: PaymentStatus): Int {
        val colors = AurumLight
        return when (status) {
            PaymentStatus.SUCCESS -> colors.success.strong
            PaymentStatus.FAILED -> colors.failed.strong
            PaymentStatus.CREATED, PaymentStatus.PENDING -> colors.pending.strong
            PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED -> colors.refund.strong
        }.toArgb()
    }

    private fun perforatedPaper(width: Float, height: Float): Path = Path().apply {
        moveTo(0f, 0f)
        lineTo(width, 0f)
        lineTo(width, height - TOOTH)
        val teeth = (width / (TOOTH * 4)).toInt().coerceAtLeast(1)
        val step = width / teeth
        var x = width
        repeat(teeth) {
            quadTo(x - step / 2, height + TOOTH, x - step, height - TOOTH)
            x -= step
        }
        close()
    }
}

/** Writes receipts to one private folder the FileProvider exposes, and keeps at most the latest one. */
object ReceiptExporter {
    private const val FOLDER = "receipts"

    fun write(context: Context, bitmap: Bitmap, name: String = "payflow-receipt-${System.currentTimeMillis()}.png"): File {
        val dir = File(context.cacheDir, FOLDER).apply { mkdirs() }
        clear(context)
        val file = File(dir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    fun clear(context: Context) {
        File(context.cacheDir, FOLDER).listFiles()?.forEach { it.delete() }
    }

    fun uriOf(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** The grant is read-only and for this one image. */
    fun shareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
