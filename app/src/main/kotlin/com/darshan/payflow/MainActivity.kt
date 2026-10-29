package com.darshan.payflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.darshan.payflow.data.upi.UpiAppLauncher
import com.darshan.payflow.ui.navigation.PayFlowNavHost
import com.darshan.payflow.ui.theme.PayFlowTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var upiAppLauncher: UpiAppLauncher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PayFlowTheme { PayFlowNavHost(upiAppLauncher) } }
    }
}
