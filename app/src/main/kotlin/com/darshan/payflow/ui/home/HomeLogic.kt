package com.darshan.payflow.ui.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.darshan.payflow.domain.model.HistorySummary
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.ui.designsystem.components.OdometerLogic
import com.darshan.payflow.ui.designsystem.components.StatusKind
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Pure rules for the collapsing header. Everything is a plain number read per frame in layer lambdas, so scrolling never
 * recomposes.
 *
 * The header scrolls with the page for [collapsedPx] and then pins: only its bottom part stays on screen. An element that docks
 * is moved by [dockTranslation] on top of that, which takes it in a straight line from where it rests to where it docks.
 */
object HomeHeaderLogic {
    private const val DETAILS_GONE_AT = 0.5f
    private const val SCAN_MIN_SCALE = 0.7f

    fun progress(scrollPx: Int, rangePx: Int): Float =
        if (rangePx <= 0) 0f else (scrollPx.toFloat() / rangePx).coerceIn(0f, 1f)

    /** How far the header has moved up the screen. */
    fun collapsedPx(scrollPx: Int, rangePx: Int): Float = min(scrollPx, max(rangePx, 0)).toFloat()

    /** What holds the header on screen once it has moved up by its whole range. */
    fun pinOffset(scrollPx: Int, rangePx: Int): Float = max(scrollPx - max(rangePx, 0), 0).toFloat()

    fun dockTranslation(progress: Float, restY: Float, dockY: Float, rangePx: Int): Float = progress * (dockY - restY + rangePx)

    fun dockScale(heroFontSp: Float, dockFontSp: Float): Float = dockFontSp / heroFontSp

    fun scaleAt(progress: Float, dockScale: Float): Float = 1f + (dockScale - 1f) * progress

    /** Top of the docked label. The label and the scaled amount are centred together in the bar. */
    fun labelDockY(topPx: Float, barPx: Float, labelPx: Float, amountPx: Float, dockScale: Float): Float =
        topPx + (barPx - labelPx - amountPx * dockScale) / 2f

    fun detailsAlpha(progress: Float): Float = (1f - progress / DETAILS_GONE_AT).coerceIn(0f, 1f)

    fun wordmarkAlpha(progress: Float): Float = detailsAlpha(progress)

    fun scanActionAlpha(progress: Float): Float = (1f - progress).coerceIn(0f, 1f)

    fun scanActionScale(progress: Float): Float = 1f + (SCAN_MIN_SCALE - 1f) * progress.coerceIn(0f, 1f)

    fun isDocked(progress: Float): Boolean = progress >= 1f
}

/**
 * Rules of the hide and show animation. A phase runs 0 → [COVERED] → [DONE]: the content swaps at the peak, where the veil hides
 * it. The scramble is random and as long as the mask, so a frame says nothing about the amount or its size.
 */
object AmountVeilLogic {
    const val FRAMES = 6
    const val COVERED = 1f
    const val DONE = 2f

    fun scrambleFrames(random: Random = Random.Default): List<String> {
        val digits = maskedAmount().length - 1
        return List(FRAMES) { buildString { append('₹'); repeat(digits) { append('0' + random.nextInt(10)) } } }
    }

    fun veil(phase: Float): Float = (1f - abs(phase - COVERED)).coerceIn(0f, 1f)

    fun frameAt(phase: Float): Int = ((phase / DONE).coerceIn(0f, 1f) * (FRAMES - 1)).toInt()

    fun blurLevel(veil: Float, levels: Int): Int = (veil.coerceIn(0f, 1f) * (levels - 1)).toInt()
}

/** How far a pressed tile leans towards the finger: in proportion to how far the finger is from its centre. */
object MagneticLogic {
    fun pull(pointer: Offset, size: Size, maxPx: Float): Offset {
        if (size.width <= 0f || size.height <= 0f) return Offset.Zero
        val x = (pointer.x - size.width / 2f) / (size.width / 2f)
        val y = (pointer.y - size.height / 2f) / (size.height / 2f)
        val length = hypot(x, y)
        val scale = if (length > 1f) maxPx / length else maxPx
        return Offset(x * scale, y * scale)
    }
}

/** Pull-to-refresh: how far the page follows the finger, and how the coin turns. */
object PullLogic {
    /** One and a half turns by the threshold, so the coin lands on its back and spins on from there. */
    const val FLIPS_DEGREES = 540f
    private const val MAX_EXTRA = 0.5f

    fun stretchPx(fraction: Float, thresholdPx: Float): Float = when {
        fraction <= 0f -> 0f
        fraction <= 1f -> fraction * thresholdPx
        else -> thresholdPx + (1f - exp(1f - fraction)) * thresholdPx * MAX_EXTRA
    }

    fun coinDegrees(fraction: Float): Float = fraction.coerceAtLeast(0f) * FLIPS_DEGREES

    fun showsFront(degrees: Float): Boolean {
        val turn = ((degrees % 360f) + 360f) % 360f
        return turn < 90f || turn > 270f
    }

    fun crossedThreshold(previous: Float, current: Float): Boolean = previous < 1f && current >= 1f
}

/** One part of the hero's summary line. */
sealed interface HeroCount {
    data class Paid(val count: Int) : HeroCount
    data class Pending(val count: Int) : HeroCount
    data class Failed(val count: Int) : HeroCount
}

/** Zero counts are left out, so a quiet month doesn't read as a table of zeros. */
fun HistorySummary.heroCounts(): List<HeroCount> = buildList {
    if (success > 0) add(HeroCount.Paid(success))
    if (pending > 0) add(HeroCount.Pending(pending))
    if (failed > 0) add(HeroCount.Failed(failed))
}

/** A settled, successful payment is the normal case and needs no label: only exceptions get a pill. */
fun PaymentStatus.feedPill(): StatusKind? = when (this) {
    PaymentStatus.SUCCESS -> null
    PaymentStatus.CREATED, PaymentStatus.PENDING -> StatusKind.Pending
    PaymentStatus.FAILED -> StatusKind.Failed
    PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED -> StatusKind.Refund
}

/**
 * What Home has already shown during this visit, so coming back from another screen doesn't replay the entrance, and a new
 * amount can roll from the one the user last saw. Held by the ViewModel rather than saved state: amounts don't leave the process.
 */
class HomeEntranceMemory(val scrambleRandom: Random = Random.Default) {
    var sectionsPlayed: Boolean = false
    var lastShownSentPaise: Long? = null
    private val seen = LinkedHashSet<String>()
    val seenCount: Int get() = seen.size

    fun firstSight(paymentId: String): Boolean {
        val first = seen.add(paymentId)
        if (seen.size > MAX_SEEN) seen.remove(seen.first())
        return first
    }

    companion object {
        const val MAX_SEEN = 64
    }
}

internal fun maskedAmount(): String = OdometerLogic.hiddenText()
