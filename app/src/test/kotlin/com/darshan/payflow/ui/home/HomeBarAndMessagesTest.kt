package com.darshan.payflow.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.ui.designsystem.components.rememberBottomNavState
import com.darshan.payflow.ui.designsystem.setDs
import com.darshan.payflow.ui.designsystem.tokens.AurumDark
import com.darshan.payflow.ui.navigation.ShellBottomBar
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h400dp-xhdpi")
class HomeBarAndMessagesTest {
    @get:Rule val rule = createComposeRule()

    /** Bright content behind the bar: nothing of it may show at the dock's side or below it, only above, where it fades out. */
    @Test
    fun nothingShowsThroughBesideOrBelowTheDock() {
        val page = Color.Red
        rule.setDs(dark = true) {
            Box(Modifier.fillMaxSize().background(page)) {
                ShellBottomBar(rememberBottomNavState(4), {}, Modifier.align(Alignment.BottomCenter))
            }
        }
        rule.waitForIdle()
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val bg = AurumDark.bg.toArgb()
        val left = 2
        val density = rule.density.density
        // Below the dock, and beside it through its whole height.
        assertThat(image.getPixel(image.width / 2, image.height - 2)).isEqualTo(bg)
        assertThat(image.getPixel(left, image.height - (40 * density).toInt())).isEqualTo(bg)
        // Above the fade the page is untouched, and inside the fade it's on its way out.
        assertThat(image.getPixel(left, image.height - (200 * density).toInt())).isEqualTo(page.toArgb())
        val fading = image.getPixel(left, image.height - ((80 + 12) * density).toInt())
        assertThat(fading).isNotEqualTo(page.toArgb())
        assertThat(fading).isNotEqualTo(bg)
    }

    @Test
    fun refreshResultsAreWordedHonestly() {
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        assertThat(refreshMessage(HomeEffect.Refreshed(0, 0), resources)).isEqualTo("Nothing new to check")
        assertThat(refreshMessage(HomeEffect.Refreshed(3, 1), resources)).isEqualTo("1 payment updated")
        assertThat(refreshMessage(HomeEffect.Refreshed(3, 2), resources)).isEqualTo("2 payments updated")
        assertThat(refreshMessage(HomeEffect.Refreshed(1, 0), resources)).isEqualTo("Still waiting on 1 payment")
        assertThat(refreshMessage(HomeEffect.Refreshed(4, 0), resources)).isEqualTo("Still waiting on 4 payments")
        assertThat(refreshMessage(HomeEffect.RefreshFailed, resources)).isEqualTo("Couldn't refresh. Your list is unchanged.")
    }
}
