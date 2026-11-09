package com.darshan.payflow.ui.history

import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentDirection
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.StatusSource
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The demo scratch-card reward. It is a piece of delight, not money: the amount is made up from the payment's ID, nothing is
 * credited, and no balance, total or payment is touched. It is offered only after a payment the provider confirmed.
 */
data class DemoReward(val paymentId: String, val amountPaise: Long) {
    companion object {
        const val MIN_PAISE = 100L
        const val MAX_PAISE = 5_000L

        /** Only a provider-confirmed, outgoing success earns one. A pending, failed, app-reported or user-marked payment never does. */
        fun eligible(payment: Payment): Boolean =
            payment.status == PaymentStatus.SUCCESS &&
                payment.statusSource == StatusSource.PSP &&
                payment.direction == PaymentDirection.OUTGOING &&
                !payment.isUpiAppPayment

        fun forPayment(payment: Payment): DemoReward? {
            if (!eligible(payment)) return null
            // Same card every time for the same payment, so reopening it can't fish for a bigger number.
            val spread = MAX_PAISE / 100 - MIN_PAISE / 100 + 1
            val rupees = MIN_PAISE / 100 + Math.floorMod(payment.id.hashCode(), spread.toInt())
            return DemoReward(payment.id, rupees * 100)
        }
    }
}

/**
 * How much of the card has been scratched, on a coarse grid so a stroke costs a few cell checks and nothing is allocated.
 * Past [REVEAL_AT] the rest of the foil falls away by itself.
 */
class ScratchCoverage(private val cols: Int = COLS, private val rows: Int = ROWS) {
    private val cells = BooleanArray(cols * rows)
    var cleared: Int = 0
        private set

    val fraction: Float get() = cleared.toFloat() / cells.size
    val revealed: Boolean get() = fraction >= REVEAL_AT

    /** Marks the cells within [radius] of ([x], [y]) on a card of [width] by [height]. Returns true if any cell changed. */
    fun scratch(x: Float, y: Float, radius: Float, width: Float, height: Float): Boolean {
        if (width <= 0f || height <= 0f) return false
        val cw = width / cols
        val ch = height / rows
        val c0 = ((x - radius) / cw).toInt().coerceIn(0, cols - 1)
        val c1 = ((x + radius) / cw).toInt().coerceIn(0, cols - 1)
        val r0 = ((y - radius) / ch).toInt().coerceIn(0, rows - 1)
        val r1 = ((y + radius) / ch).toInt().coerceIn(0, rows - 1)
        if (x + radius < 0f || y + radius < 0f || x - radius > width || y - radius > height) return false
        var changed = false
        for (r in r0..r1) {
            for (c in c0..c1) {
                val cx = (c + 0.5f) * cw
                val cy = (r + 0.5f) * ch
                val dx = cx - x
                val dy = cy - y
                if (dx * dx + dy * dy <= (radius + maxOf(cw, ch) / 2) * (radius + maxOf(cw, ch) / 2)) {
                    val i = r * cols + c
                    if (!cells[i]) {
                        cells[i] = true
                        cleared++
                        changed = true
                    }
                }
            }
        }
        return changed
    }

    fun clearAll() {
        cells.fill(true)
        cleared = cells.size
    }

    companion object {
        const val COLS = 24
        const val ROWS = 12
        const val REVEAL_AT = 0.55f
    }
}

/** Which demo cards have been scratched, for this run of the app. It never writes anywhere a balance or payment lives. */
@Singleton
class DemoRewardLedger @Inject constructor() {
    private val scratched = ConcurrentHashMap.newKeySet<String>()

    fun isScratched(paymentId: String): Boolean = paymentId in scratched

    fun markScratched(paymentId: String) {
        scratched += paymentId
    }
}
