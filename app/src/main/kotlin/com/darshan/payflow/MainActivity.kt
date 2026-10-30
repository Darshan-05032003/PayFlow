package com.darshan.payflow

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.darshan.checkoutkit.core.CheckoutKit
import com.darshan.payflow.data.qr.CameraController
import com.darshan.payflow.data.qr.CameraPermission
import com.darshan.payflow.data.security.AppLock
import com.darshan.payflow.data.upi.UpiAppLauncher
import com.darshan.payflow.ui.navigation.AppRoot
import com.darshan.payflow.ui.theme.PayFlowTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/** Single activity. A `FragmentActivity` because `BiometricPrompt` requires one. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var upiAppLauncher: UpiAppLauncher
    @Inject lateinit var cameraController: CameraController
    @Inject lateinit var cameraPermission: CameraPermission
    @Inject lateinit var appLock: AppLock
    @Inject lateinit var checkoutKit: CheckoutKit

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Tapjacking: taps delivered through another app's overlay are dropped everywhere, not only on secure screens.
        window.decorView.filterTouchesWhenObscured = true
        setContent { PayFlowTheme { AppRoot(upiAppLauncher, cameraController, cameraPermission, checkoutKit) } }
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
