package com.darshan.payflow.ui.designsystem

import com.darshan.payflow.ui.designsystem.effects.ChimeSynth
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Test

class ChimeSynthTest {
    private val pcm = ChimeSynth.success()

    @Test
    fun `short, quiet and silent at both ends`() {
        assertThat(pcm.size).isEqualTo(ChimeSynth.SAMPLE_RATE * ChimeSynth.DURATION_MS / 1_000)
        val peak = pcm.maxOf { abs(it.toInt()) }
        assertThat(peak).isAtMost((Short.MAX_VALUE * ChimeSynth.PEAK).toInt() + 1)
        assertThat(peak).isGreaterThan(Short.MAX_VALUE / 20) // audible, not a rounding error
        assertThat(abs(pcm.first().toInt())).isLessThan(50) // starts from silence: no click
        assertThat(abs(pcm.last().toInt())).isLessThan(50)
    }

    @Test
    fun `it decays, so the tail is far quieter than the start`() {
        fun loudness(from: Int, to: Int) = (from until to).maxOf { abs(pcm[it].toInt()) }
        val tenth = pcm.size / 10
        assertThat(loudness(pcm.size - 2 * tenth, pcm.size - tenth)).isLessThan(loudness(tenth, 2 * tenth) / 2)
    }

    @Test
    fun `the same every time`() {
        assertThat(ChimeSynth.success().contentEquals(pcm)).isTrue()
    }
}
