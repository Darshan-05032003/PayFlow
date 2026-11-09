package com.darshan.payflow.ui.designsystem.components

import kotlin.math.ceil
import kotlin.math.floor

enum class AmountDecimals { Always, WhenNonZero, Never }

enum class AmountSign {
    /** Only a debit shows a sign. */
    NegativeOnly,

    /** `+` for credits, `−` for debits, nothing for zero. */
    Always,

    /** No sign: the caller shows direction another way. */
    None,
}

enum class RollDirection { Up, Down }

/** One glyph cell of a displayed amount. [key] follows place value, so a column keeps its identity as the amount grows. */
sealed interface OdometerSlot {
    val key: String
    val char: Char

    data class Digit(val value: Int, val place: Int) : OdometerSlot {
        override val key get() = "d$place"
        override val char get() = '0' + value
    }

    data class Separator(override val char: Char, val place: Int) : OdometerSlot {
        override val key get() = "s$place"
    }

    data object Currency : OdometerSlot {
        override val key = "currency"
        override val char = '₹'
    }

    /** Rendered in Geist: Newsreader's minus sits too low. */
    data class Sign(override val char: Char) : OdometerSlot {
        override val key = "sign"
    }

    data class Mask(val index: Int) : OdometerSlot {
        override val key get() = "m$index"
        override val char = '•'
    }
}

/** Pure odometer rules: formatting into slots, roll sequences, positions and fit. No Compose, no clock. */
object OdometerLogic {
    /** ₹99,99,99,999.99 */
    const val MAX_PAISE = 99_99_99_999_99L

    /** Font scale moves in these steps, so the text doesn't jitter as digits of different amounts change. */
    const val SCALE_STEP = 0.05f

    const val MINUS = '−'
    private const val HIDDEN_DOTS = 5

    fun slots(paise: Long, decimals: AmountDecimals, sign: AmountSign): List<OdometerSlot> {
        val clamped = paise.coerceIn(-MAX_PAISE, MAX_PAISE)
        val abs = if (clamped < 0) -clamped else clamped
        val rupees = (abs / 100).toString()
        val cents = (abs % 100).toInt()
        return buildList {
            when {
                clamped < 0 && sign != AmountSign.None -> add(OdometerSlot.Sign(MINUS))
                clamped > 0 && sign == AmountSign.Always -> add(OdometerSlot.Sign('+'))
            }
            add(OdometerSlot.Currency)
            rupees.forEachIndexed { index, digit ->
                val place = rupees.length - 1 - index
                add(OdometerSlot.Digit(digit - '0', place))
                if (place >= 3 && (place - 3) % 2 == 0) add(OdometerSlot.Separator(',', place))
            }
            val showDecimals = decimals == AmountDecimals.Always || (decimals == AmountDecimals.WhenNonZero && cents != 0)
            if (showDecimals) {
                add(OdometerSlot.Separator('.', -1))
                add(OdometerSlot.Digit(cents / 10, -1))
                add(OdometerSlot.Digit(cents % 10, -2))
            }
        }
    }

    /** Hidden amounts show the same dots whatever the value, so even the length gives nothing away. */
    fun hiddenSlots(): List<OdometerSlot> = buildList {
        add(OdometerSlot.Currency)
        repeat(HIDDEN_DOTS) { add(OdometerSlot.Mask(it)) }
    }

    /** The hidden amount as plain text, for rows and lines that don't roll. */
    fun hiddenText(): String = hiddenSlots().joinToString("") { it.char.toString() }

    fun text(paise: Long, decimals: AmountDecimals, sign: AmountSign): String =
        slots(paise, decimals, sign).joinToString("") { it.char.toString() }

    /** For TalkBack. A symbol minus is often skipped by screen readers, so it is spelled out. */
    fun spoken(paise: Long, decimals: AmountDecimals): String {
        val body = text(paise, decimals, AmountSign.None)
        return if (paise < 0) "minus $body" else body
    }

    fun direction(from: Long, to: Long): RollDirection = if (to >= from) RollDirection.Up else RollDirection.Down

    /** The digits a column passes through, ends included. Wraps past 9 (rising) or 0 (falling). */
    fun digitSequence(from: Int, to: Int, direction: RollDirection): List<Int> = buildList {
        var current = from
        add(current)
        while (current != to) {
            current = if (direction == RollDirection.Up) (current + 1) % 10 else (current + 9) % 10
            add(current)
        }
    }

    /** Where a column at [current] should travel to show [target], moving only in [direction]. Works mid-roll. */
    fun nextPosition(current: Float, target: Int, direction: RollDirection): Float =
        if (direction == RollDirection.Up) {
            val base = ceil(current).toInt()
            (base + Math.floorMod(target - base, 10)).toFloat()
        } else {
            val base = floor(current).toInt()
            (base - Math.floorMod(base - target, 10)).toFloat()
        }

    /** The digit whose top is at [position]; the next digit up shows below it while rolling. */
    fun digitAt(position: Float): Int = Math.floorMod(floor(position).toInt(), 10)

    /** Font scale that fits [naturalWidth] into [available], in stable steps and never below [floorScale]. */
    fun fitScale(naturalWidth: Float, available: Float, floorScale: Float): Float {
        if (naturalWidth <= available) return 1f
        if (available <= 0f) return floorScale
        val steps = floor(available / naturalWidth / SCALE_STEP + 1e-4f).toInt()
        return (steps * SCALE_STEP).coerceIn(floorScale, 1f)
    }
}
