package com.darshan.payflow.data.upi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.core.net.toUri
import com.darshan.payflow.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpiLaunchResult {
    /** The UPI app returned; [raw] is its `response` extra (may be null or garbage). */
    data class Responded(val raw: String?) : UpiLaunchResult
    data object Cancelled : UpiLaunchResult
}

/** An installed app that can handle `upi://pay`. [icon] is null on the JVM. */
class UpiApp(val packageName: String, val label: String, val icon: Drawable? = null) {
    override fun equals(other: Any?): Boolean = other is UpiApp && other.packageName == packageName && other.label == label
    override fun hashCode(): Int = packageName.hashCode()
    override fun toString(): String = "UpiApp($packageName, $label)"
}

/** Opens UPI apps for a `upi://pay` link. Wrapped so ViewModels and tests never touch Intents or the PackageManager. */
interface UpiAppLauncher {
    fun isUpiAppInstalled(): Boolean

    /** Installed UPI handlers, sorted by label, PayFlow itself excluded. */
    fun installedUpiApps(): List<UpiApp>

    /** System chooser over every handler (fallback when [installedUpiApps] is empty but [isUpiAppInstalled] is true). */
    fun chooserIntent(upiUri: String): Intent

    /** A `VIEW` intent pinned to one app, so the app the user picked in PayFlow's own sheet is the one that opens. */
    fun targetedIntent(upiUri: String, packageName: String): Intent

    fun parseActivityResult(resultCode: Int, data: Intent?): UpiLaunchResult

    /** Human-readable name of an installed package (for "Reported by GPay"); null when it is no longer installed. */
    fun appLabel(packageName: String): String?
}

@Singleton
class AndroidUpiAppLauncher @Inject constructor(@ApplicationContext private val context: Context) : UpiAppLauncher {
    private val probe: Intent get() = Intent(Intent.ACTION_VIEW, "upi://pay".toUri())

    override fun isUpiAppInstalled(): Boolean = installedUpiApps().isNotEmpty() ||
        context.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY).any { it.activityInfo?.packageName != context.packageName }

    override fun installedUpiApps(): List<UpiApp> {
        val pm = context.packageManager
        return runCatching { pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY) }.getOrDefault(emptyList())
            .mapNotNull { info -> info.activityInfo?.packageName?.takeIf { it != context.packageName }?.let { it to info } }
            .distinctBy { it.first }
            .map { (pkg, info) ->
                val label = runCatching { info.loadLabel(pm)?.toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
                val icon = runCatching { info.loadIcon(pm) }.getOrNull()
                UpiApp(pkg, label.take(MAX_LABEL_LENGTH), icon)
            }
            .sortedBy { it.label.lowercase() }
    }

    override fun chooserIntent(upiUri: String): Intent {
        val view = Intent(Intent.ACTION_VIEW, upiUri.toUri())
        return Intent.createChooser(view, context.getString(R.string.pay_chooser_title))
    }

    override fun targetedIntent(upiUri: String, packageName: String): Intent =
        Intent(Intent.ACTION_VIEW, upiUri.toUri()).setPackage(packageName)

    override fun parseActivityResult(resultCode: Int, data: Intent?): UpiLaunchResult {
        val raw = runCatching { data?.getStringExtra(EXTRA_RESPONSE) }.getOrNull()
        return when {
            raw != null -> UpiLaunchResult.Responded(raw.take(MAX_RESPONSE_LENGTH))
            resultCode == Activity.RESULT_CANCELED -> UpiLaunchResult.Cancelled
            else -> UpiLaunchResult.Responded(null)
        }
    }

    override fun appLabel(packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString().takeIf { it.isNotBlank() }?.take(MAX_LABEL_LENGTH)
    }.getOrNull()

    companion object {
        const val EXTRA_RESPONSE = "response"
        const val MAX_RESPONSE_LENGTH = 16 * 1024
        const val MAX_LABEL_LENGTH = 40
    }
}
