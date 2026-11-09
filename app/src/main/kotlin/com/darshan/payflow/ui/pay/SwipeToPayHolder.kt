package com.darshan.payflow.ui.pay

import com.darshan.payflow.ui.designsystem.effects.HapticEvent
import kotlin.math.floor

enum class SwipePhase { Idle, Dragging, Armed, SpringingBack, Committed }

/** What a gesture step means. Each has its own haptic, and snapping back has none because nothing happened. */
enum class SwipeSignal(val haptic: HapticEvent?) {
    Tick(HapticEvent.Tick),
    Armed(HapticEvent.Light),
    Committed(HapticEvent.Heavy),
    SnappedBack(null),
    Refused(HapticEvent.Reject),
}

/**
 * The swipe-to-pay rules, with no Compose in them.
 *
 * A payment needs the handle to be at [commitAt] when the finger lifts. Speed doesn't count: a fling that stops short does
 * nothing. A cancelled or interrupted gesture does nothing, wherever the handle is. After a commit it stays committed and
 * ignores every gesture until its owner calls [reopen], so one swipe is one attempt however many follow it.
 */
class SwipeToPayHolder(private val detents: Int = DEFAULT_DETENTS, private val commitAt: Float = COMMIT_FRACTION) {
    var fraction: Float = 0f
        private set
    var phase: SwipePhase = SwipePhase.Idle
        private set
    private var highestDetent = 0

    fun begin(enabled: Boolean): List<SwipeSignal> = when {
        phase == SwipePhase.Committed || phase == SwipePhase.Dragging || phase == SwipePhase.Armed -> emptyList()
        !enabled -> listOf(SwipeSignal.Refused)
        else -> {
            phase = SwipePhase.Dragging
            emptyList()
        }
    }

    fun dragBy(delta: Float): List<SwipeSignal> {
        if (phase != SwipePhase.Dragging && phase != SwipePhase.Armed) return emptyList()
        fraction = (fraction + delta).coerceIn(0f, 1f)
        var signals = emptyList<SwipeSignal>()
        val detent = floor(fraction * detents).toInt()
        if (detent > highestDetent) {
            signals = List(detent - highestDetent) { SwipeSignal.Tick }
            highestDetent = detent
        }
        if (phase == SwipePhase.Dragging && fraction >= commitAt) {
            phase = SwipePhase.Armed
            signals = signals + SwipeSignal.Armed
        } else if (phase == SwipePhase.Armed && fraction < commitAt - DISARM_MARGIN) {
            phase = SwipePhase.Dragging
        }
        return signals
    }

    /** The finger lifted. Only here can a payment start, and only when the handle is at the mark. */
    fun release(): List<SwipeSignal> = when (phase) {
        SwipePhase.Armed -> {
            phase = SwipePhase.Committed
            fraction = 1f
            listOf(SwipeSignal.Committed)
        }
        SwipePhase.Dragging -> springBack()
        else -> emptyList()
    }

    /** The system took the gesture away, or the screen went. Never pays. */
    fun cancel(): List<SwipeSignal> =
        if (phase == SwipePhase.Dragging || phase == SwipePhase.Armed) springBack() else emptyList()

    /** The attempt was refused (a failed check, the app sheet dismissed): the handle goes home and can be used again. */
    fun reopen() {
        if (phase == SwipePhase.Committed) {
            phase = SwipePhase.SpringingBack
            highestDetent = 0
        }
    }

    /** A frame of the spring home. Ignored once a new drag has taken the handle. */
    fun springTo(value: Float) {
        if (phase == SwipePhase.SpringingBack) fraction = value.coerceIn(0f, 1f)
    }

    fun settled() {
        if (phase == SwipePhase.SpringingBack) {
            fraction = 0f
            phase = SwipePhase.Idle
        }
    }

    private fun springBack(): List<SwipeSignal> {
        phase = SwipePhase.SpringingBack
        highestDetent = floor(fraction * detents).toInt().coerceAtLeast(highestDetent)
        return listOf(SwipeSignal.SnappedBack)
    }

    companion object {
        const val DEFAULT_DETENTS = 8
        const val COMMIT_FRACTION = 0.9f
        private const val DISARM_MARGIN = 0.02f
    }
}
