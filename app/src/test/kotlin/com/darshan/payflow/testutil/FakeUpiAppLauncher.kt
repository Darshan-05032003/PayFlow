package com.darshan.payflow.testutil

import com.darshan.payflow.data.upi.UpiAppLauncher
import com.darshan.payflow.data.upi.UpiLaunchResult

class FakeUpiAppLauncher(var installed: Boolean = true) : UpiAppLauncher {
    val launchedUris = mutableListOf<String>()
    override fun isUpiAppInstalled(): Boolean = installed
    override fun chooserIntent(upiUri: String): android.content.Intent {
        launchedUris += upiUri
        return android.content.Intent()
    }
    override fun parseActivityResult(resultCode: Int, data: android.content.Intent?): UpiLaunchResult = UpiLaunchResult.Cancelled
}
