package com.darshan.payflow.data.upi

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AndroidUpiAppLauncherTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val launcher = AndroidUpiAppLauncher(context)

    private fun installFakeUpiApp(packageName: String = "com.example.upiapp", label: String = "UPI App") {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName; name = "PayActivity"
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName; nonLocalizedLabel = label }
            }
            nonLocalizedLabel = label
            filter = IntentFilter(Intent.ACTION_VIEW).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("upi") }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("upi://pay")), info)
    }

    @Test
    fun `detects whether any upi app can handle the scheme`() {
        assertThat(launcher.isUpiAppInstalled()).isFalse()
        installFakeUpiApp()
        assertThat(launcher.isUpiAppInstalled()).isTrue()
    }

    @Test
    fun `chooser wraps a view intent for the exact uri`() {
        val chooser = launcher.chooserIntent("upi://pay?pa=a%40b&am=1.00&cu=INR&tr=T1")
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        val target = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertThat(target.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(target.dataString).isEqualTo("upi://pay?pa=a%40b&am=1.00&cu=INR&tr=T1")
        assertThat(target.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isEqualTo(0)
    }

    @Test
    fun `activity results map to launch results`() {
        val ok = Intent().putExtra("response", "Status=SUCCESS&txnRef=T1")
        assertThat(launcher.parseActivityResult(Activity.RESULT_OK, ok)).isEqualTo(UpiLaunchResult.Responded("Status=SUCCESS&txnRef=T1"))
        // Many apps return RESULT_CANCELED together with a FAILURE response: the response wins.
        assertThat(launcher.parseActivityResult(Activity.RESULT_CANCELED, Intent().putExtra("response", "Status=FAILURE")))
            .isEqualTo(UpiLaunchResult.Responded("Status=FAILURE"))
        assertThat(launcher.parseActivityResult(Activity.RESULT_CANCELED, null)).isEqualTo(UpiLaunchResult.Cancelled)
        assertThat(launcher.parseActivityResult(Activity.RESULT_OK, null)).isEqualTo(UpiLaunchResult.Responded(null))
        val huge = Intent().putExtra("response", "x".repeat(1_000_000))
        assertThat((launcher.parseActivityResult(Activity.RESULT_OK, huge) as UpiLaunchResult.Responded).raw!!.length)
            .isAtMost(AndroidUpiAppLauncher.MAX_RESPONSE_LENGTH)
    }

    @Test
    fun `installed upi apps are listed by label, excluding payflow itself, and launched by package`() {
        assertThat(launcher.installedUpiApps()).isEmpty()
        installFakeUpiApp()
        installFakeUpiApp(packageName = "com.example.zpay", label = "Zeta Pay")
        installFakeUpiApp(packageName = context.packageName, label = "PayFlow") // our own upi:// filter must not be offered
        val apps = launcher.installedUpiApps()
        assertThat(apps.map { it.packageName }).containsExactly("com.example.upiapp", "com.example.zpay").inOrder()
        val targeted = launcher.targetedIntent("upi://pay?pa=a%40b&am=1.00&cu=INR&tr=T1", "com.example.zpay")
        assertThat(targeted.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(targeted.`package`).isEqualTo("com.example.zpay")
        assertThat(targeted.dataString).isEqualTo("upi://pay?pa=a%40b&am=1.00&cu=INR&tr=T1")
        assertThat(launcher.appLabel("com.example.nothing")).isNull()
    }
}
