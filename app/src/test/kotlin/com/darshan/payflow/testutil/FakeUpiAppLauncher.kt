package com.darshan.payflow.testutil

import com.darshan.payflow.data.upi.UpiApp
import com.darshan.payflow.data.upi.UpiAppLauncher
import com.darshan.payflow.data.upi.UpiLaunchResult

class FakeUpiAppLauncher(
    var installed: Boolean = true,
    var apps: List<UpiApp> = listOf(UpiApp("com.example.gpay", "GPay"), UpiApp("com.example.phonepe", "PhonePe")),
) : UpiAppLauncher {
    val launchedUris = mutableListOf<String>()
    /** Package passed to [targetedIntent] (null for the system chooser). */
    val launchedPackages = mutableListOf<String?>()
    override fun isUpiAppInstalled(): Boolean = installed
    override fun installedUpiApps(): List<UpiApp> = if (installed) apps else emptyList()
    override fun chooserIntent(upiUri: String): android.content.Intent {
        launchedUris += upiUri; launchedPackages += null
        return android.content.Intent()
    }
    override fun targetedIntent(upiUri: String, packageName: String): android.content.Intent {
        launchedUris += upiUri; launchedPackages += packageName
        return android.content.Intent().setPackage(packageName)
    }
    override fun parseActivityResult(resultCode: Int, data: android.content.Intent?): UpiLaunchResult = UpiLaunchResult.Cancelled
    override fun appLabel(packageName: String): String? = apps.firstOrNull { it.packageName == packageName }?.label
}
