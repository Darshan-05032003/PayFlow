package com.darshan.payflow.ui.scan

import kotlin.math.PI
import kotlin.math.cos

/** The viewfinder's motion as plain functions of a 0..1 phase, so it can be tested without a frame. */
object ViewfinderMotion {
    /** How far the brackets move in while breathing, as a fraction of the frame. */
    const val BREATH_DEPTH = 0.035f

    /** The frame's size once it has locked on to a code. */
    const val LOCKED_SCALE = 0.82f

    /** 0 at rest, [BREATH_DEPTH] at the deepest point, smooth at both ends of the loop. */
    fun breath(phase: Float): Float = BREATH_DEPTH * (1f - cos(phase * 2f * PI.toFloat())) / 2f

    fun lockScale(progress: Float): Float = 1f - (1f - LOCKED_SCALE) * progress.coerceIn(0f, 1f)

    /** The beam's height in the frame: down for the first half of the loop, back up for the second, eased at the turns. */
    fun beam(phase: Float): Float {
        val p = phase - kotlin.math.floor(phase)
        val leg = if (p <= 0.5f) p * 2f else (1f - p) * 2f
        return (1f - cos(leg * PI.toFloat())) / 2f
    }
}
