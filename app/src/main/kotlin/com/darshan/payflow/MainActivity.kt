package com.darshan.payflow

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.darshan.checkoutkit.core.CheckoutKit
import com.darshan.payflow.data.qr.CameraController
import com.darshan.payflow.data.qr.CameraPermission
import com.darshan.payflow.data.security.AppLock
import com.darshan.payflow.data.upi.IncomingUpiLinkStore
import com.darshan.payflow.data.upi.UpiAppLauncher
import com.darshan.payflow.ui.designsystem.effects.UiPreferences
import com.darshan.payflow.ui.navigation.AppRoot
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.darshan.payflow.ui.theme.isDark
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Single activity. A `FragmentActivity` because `BiometricPrompt` requires one. `singleTop` so an incoming `upi://pay` link
 * (browser, chat, another QR app) lands in [onNewIntent] while PayFlow is open; the link itself is parsed by the same
 * hostile-input-safe parser as scanned QRs before the Pay screen ever sees it.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var upiAppLauncher: UpiAppLauncher
    @Inject lateinit var cameraController: CameraController
    @Inject lateinit var cameraPermission: CameraPermission
    @Inject lateinit var appLock: AppLock
    @Inject lateinit var checkoutKit: CheckoutKit
    @Inject lateinit var incomingLinks: IncomingUpiLinkStore
    @Inject lateinit var uiPreferences: UiPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Tapjacking: taps delivered through another app's overlay are dropped everywhere, not only on secure screens.
        window.decorView.filterTouchesWhenObscured = true
        // Only a cold start carries the link; after a rotation the saved state already holds the Pay screen.
        if (savedInstanceState == null) offerLink(intent)
        setContent {
            val settings by uiPreferences.settings.collectAsStateWithLifecycle()
            val dark = settings.isDark()
            // The theme can differ from the system's, so the system bar icons follow the theme rather than the system.
            DisposableEffect(dark) {
                val barStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
                onDispose {}
            }
            PayFlowTheme(settings) {
                // No visual effect. Off in release; the benchmark build uses it to find screens by test tag.
                Box(if (BuildConfig.EXPOSE_TEST_TAGS) Modifier.semantics { testTagsAsResourceId = true } else Modifier) {
                    AppRoot(upiAppLauncher, cameraController, cameraPermission, checkoutKit, incomingLinks)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        offerLink(intent)
    }

    private fun offerLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val raw = intent.dataString?.take(IncomingUpiLinkStore.MAX_RAW_LENGTH) ?: return
        incomingLinks.offer(raw)
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { appLock.onForeground() }
    }

    override fun onStop() {
        appLock.onBackground()
        super.onStop()
    }
}
