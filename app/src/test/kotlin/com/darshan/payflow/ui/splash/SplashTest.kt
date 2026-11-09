package com.darshan.payflow.ui.splash

import android.content.ComponentName
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Looper
import android.view.View
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.payflow.MainActivity
import com.darshan.payflow.R
import com.google.common.truth.Truth.assertThat
import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

class SplashPolicyTest {
    @Test
    fun `the splash holds until both the theme and the lock are known`() {
        assertThat(SplashPolicy.keepOnScreen(themeLoaded = false, lockResolved = false, heldForMs = 0)).isTrue()
        assertThat(SplashPolicy.keepOnScreen(themeLoaded = true, lockResolved = false, heldForMs = 0)).isTrue()
        assertThat(SplashPolicy.keepOnScreen(themeLoaded = false, lockResolved = true, heldForMs = 0)).isTrue()
        assertThat(SplashPolicy.keepOnScreen(themeLoaded = true, lockResolved = true, heldForMs = 0)).isFalse()
    }

    @Test
    fun `a stuck read cannot trap the app on the splash`() {
        assertThat(SplashPolicy.keepOnScreen(false, false, SplashPolicy.MAX_HOLD_MS - 1)).isTrue()
        assertThat(SplashPolicy.keepOnScreen(false, false, SplashPolicy.MAX_HOLD_MS)).isFalse()
    }

    @Test
    fun `the exit waits for a running icon, within limits`() {
        assertThat(SplashPolicy.exitDelayMs(nowMs = 1_100, iconStartMs = 1_000, iconDurationMs = 500)).isEqualTo(SplashPolicy.MAX_ICON_WAIT_MS)
        assertThat(SplashPolicy.exitDelayMs(nowMs = 1_400, iconStartMs = 1_000, iconDurationMs = 500)).isEqualTo(100)
    }

    @Test
    fun `a slow launch gets no extra wait`() {
        assertThat(SplashPolicy.exitDelayMs(nowMs = 2_000, iconStartMs = 1_000, iconDurationMs = 500)).isEqualTo(0)
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SplashResourcesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theLauncherActivityStartsOnTheSplashTheme() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertThat(info.theme).isEqualTo(R.style.Theme_PayFlow_Splash)
    }

    @Test
    fun theSplashHandsOverToTheAppTheme() {
        val theme = context.resources.newTheme().apply { applyStyle(R.style.Theme_PayFlow_Splash, true) }
        val value = TypedValue()
        assertThat(theme.resolveAttribute(androidx.core.splashscreen.R.attr.postSplashScreenTheme, value, true)).isTrue()
        assertThat(value.resourceId).isEqualTo(R.style.Theme_PayFlow)
    }

    @Test
    fun theSplashIsGraphiteInBothThemesSoNoStoredThemeCanFlash() {
        val theme = context.resources.newTheme().apply { applyStyle(R.style.Theme_PayFlow_Splash, true) }
        val value = TypedValue()
        assertThat(theme.resolveAttribute(androidx.core.splashscreen.R.attr.windowSplashScreenBackground, value, true)).isTrue()
        assertThat(value.resourceId).isEqualTo(R.color.ic_launcher_background)
    }

    @Test
    fun theAnimatedIconInflatesAndMatchesTheDesignSystemDuration() {
        val drawable = ContextCompat.getDrawable(context, R.drawable.splash_icon_animated)
        assertThat(drawable).isInstanceOf(AnimatedVectorDrawable::class.java)
        assertThat(context.resources.getInteger(R.integer.splash_icon_duration)).isEqualTo(com.darshan.payflow.ui.designsystem.motion.MotionDurations.ScreenSlow)
    }

    /** The exit a device would play. It has to end by removing the splash, or the app stays covered. */
    @Test
    fun theExitAnimationFadesTheSplashAndThenRemovesIt() {
        val splash = View(context)
        val icon = View(context)
        var removed = 0
        playSplashExit(splash, icon, delayMs = 100, onDone = { removed++ })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertThat(removed).isEqualTo(1)
        assertThat(splash.alpha).isEqualTo(0f)
        assertThat(icon.scaleX).isEqualTo(SplashPolicy.EXIT_ICON_SCALE)
    }

    @Config(sdk = [30])
    @Test
    fun belowApi31TheIconIsStatic() {
        val theme = context.resources.newTheme().apply { applyStyle(R.style.Theme_PayFlow_Splash, true) }
        val value = TypedValue()
        assertThat(theme.resolveAttribute(androidx.core.splashscreen.R.attr.windowSplashScreenAnimatedIcon, value, true)).isTrue()
        assertThat(value.resourceId).isEqualTo(R.drawable.ic_launcher_foreground)
    }
}
