package com.darshan.payflow.data.upi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
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

    /** Installed UPI handlers, sorted by label, PayFlow's own link handler (`MainActivity`) excluded. */
    fun installedUpiApps(): List<UpiApp>

    /** System chooser over every handler (fallback when [installedUpiApps] is empty but [isUpiAppInstalled] is true). */
    fun chooserIntent(upiUri: String): Intent

    /** A `VIEW` intent pinned to one app, so the app the user picked in PayFlow's own sheet is the one that opens. */
    fun targetedIntent(upiUri: String, packageName: String): Intent

    fun parseActivityResult(resultCode: Int, data: Intent?): UpiLaunchResult

    /** Human-readable name of an installed package (for "Reported by GPay"); null when it is no longer installed. */
    fun appLabel(packageName: String): String?
}

/**
 * Android implementation. PayFlow itself handles `upi://pay` (incoming links land in `MainActivity`), so that one component is
 * filtered out of every query; any other handler, including the debug-only "Sandbox UPI" activity that ships in debug builds,
 * counts as a UPI app.
 */
@Singleton
class AndroidUpiAppLauncher @Inject constructor(@ApplicationContext private val context: Context) : UpiAppLauncher {
    private val probe: Intent get() = Intent(Intent.ACTION_VIEW, "upi://pay".toUri())
    private val ownLinkHandler: String get() = "${context.packageName}.MainActivity"

    /** Every `upi://pay` handler except PayFlow's own link entry point. */
    private fun handlers(intent: Intent = probe): List<ResolveInfo> =
        runCatching { context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY) }.getOrDefault(emptyList())
            .filter { info -> info.activityInfo != null && !(info.activityInfo.packageName == context.packageName && info.activityInfo.name == ownLinkHandler) }

    override fun isUpiAppInstalled(): Boolean = handlers().isNotEmpty()

    override fun installedUpiApps(): List<UpiApp> {
        val pm = context.packageManager
        return handlers()
            .map { info -> info.activityInfo.packageName to info }
            .distinctBy { it.first }
            .map { (pkg, info) ->
                val label = runCatching { info.loadLabel(pm)?.toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
                val icon = runCatching { info.loadIcon(pm) }.getOrNull()
                UpiApp(pkg, label.take(MAX_LABEL_LENGTH), icon)
            }
            .sortedBy { it.label.lowercase() }
    }

    /** System chooser over every handler except PayFlow's own link entry point (picking it would loop the link back into PayFlow). */
    override fun chooserIntent(upiUri: String): Intent {
        val view = Intent(Intent.ACTION_VIEW, upiUri.toUri())
        return Intent.createChooser(view, context.getString(R.string.pay_chooser_title))
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(android.content.ComponentName(context.packageName, ownLinkHandler)))
    }

    /**
     * Pinned to the chosen package and, when it can be resolved, to the exact activity: an app with two `upi://pay` handlers (or
     * PayFlow's own package in debug, which also contains `MainActivity`) would otherwise show a disambiguation dialog.
     */
    override fun targetedIntent(upiUri: String, packageName: String): Intent {
        val intent = Intent(Intent.ACTION_VIEW, upiUri.toUri()).setPackage(packageName)
        val target = handlers(Intent(intent)).firstOrNull()?.activityInfo ?: return intent
        return intent.setClassName(target.packageName, target.name)
    }

    override fun parseActivityResult(resultCode: Int, data: Intent?): UpiLaunchResult {
        val raw = runCatching { data?.getStringExtra(EXTRA_RESPONSE) }.getOrNull()
        return when {
            raw != null -> UpiLaunchResult.Responded(raw.take(MAX_RESPONSE_LENGTH))
            resultCode == Activity.RESULT_CANCELED -> UpiLaunchResult.Cancelled
            else -> UpiLaunchResult.Responded(null)
        }
    }

    /** The UPI handler's own label when the package has one (e.g. "Sandbox UPI (debug)"), otherwise the application label. */
    override fun appLabel(packageName: String): String? = runCatching {
        val pm = context.packageManager
        val handlerLabel = handlers(Intent(probe).setPackage(packageName)).firstOrNull()?.loadLabel(pm)?.toString()
        (handlerLabel?.takeIf { it.isNotBlank() } ?: pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString())
            .takeIf { it.isNotBlank() }?.take(MAX_LABEL_LENGTH)
    }.getOrNull()

    companion object {
        const val EXTRA_RESPONSE = "response"
        const val MAX_RESPONSE_LENGTH = 16 * 1024
        const val MAX_LABEL_LENGTH = 40
    }
}
