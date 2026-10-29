package com.darshan.payflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.darshan.payflow.data.qr.CameraController
import com.darshan.payflow.data.qr.CameraPermission
import com.darshan.payflow.data.upi.UpiAppLauncher
import com.darshan.payflow.ui.navigation.PayFlowNavHost
import com.darshan.payflow.ui.theme.PayFlowTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var upiAppLauncher: UpiAppLauncher
    @Inject lateinit var cameraController: CameraController
    @Inject lateinit var cameraPermission: CameraPermission

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PayFlowTheme { PayFlowNavHost(upiAppLauncher, cameraController, cameraPermission) } }
    }
}
