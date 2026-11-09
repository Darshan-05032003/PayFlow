package com.darshan.payflow.ui.components

import android.content.ClipDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SensitiveClipTest {
    @Test
    fun api33AndUpUsesThePlatformFlag() {
        val clip = SensitiveClip.of("Reference", "PF0ABC", sdkInt = 33)
        assertThat(clip.getItemAt(0).text.toString()).isEqualTo("PF0ABC")
        assertThat(clip.description.label.toString()).isEqualTo("Reference")
        assertThat(clip.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)).isTrue()
    }

    /** Below 33 there is no constant, but Gboard and others read the same string key. */
    @Test
    fun olderPhonesGetTheSameKeyAsAString() {
        val clip = SensitiveClip.of("Reference", "PF0ABC", sdkInt = 30)
        assertThat(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE")).isTrue()
        assertThat(SensitiveClip.EXTRA_IS_SENSITIVE_COMPAT).isEqualTo(ClipDescription.EXTRA_IS_SENSITIVE)
    }
}
