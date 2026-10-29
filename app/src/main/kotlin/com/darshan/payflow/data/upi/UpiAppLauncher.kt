package com.darshan.payflow.data.upi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

/** Opens the system chooser for a `upi://pay` link. Wrapped so ViewModels and tests never touch Intents. */
interface UpiAppLauncher {
    fun isUpiAppInstalled(): Boolean
    fun chooserIntent(upiUri: String): Intent
    fun parseActivityResult(resultCode: Int, data: Intent?): UpiLaunchResult
}

@Singleton
class AndroidUpiAppLauncher @Inject constructor(@ApplicationContext private val context: Context) : UpiAppLauncher {
    override fun isUpiAppInstalled(): Boolean {
        val probe = Intent(Intent.ACTION_VIEW, "upi://pay".toUri())
        return context.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
    }

    override fun chooserIntent(upiUri: String): Intent {
        val view = Intent(Intent.ACTION_VIEW, upiUri.toUri())
        return Intent.createChooser(view, context.getString(R.string.pay_chooser_title))
    }

    override fun parseActivityResult(resultCode: Int, data: Intent?): UpiLaunchResult {
        val raw = runCatching { data?.getStringExtra(EXTRA_RESPONSE) }.getOrNull()
        return when {
            raw != null -> UpiLaunchResult.Responded(raw.take(MAX_RESPONSE_LENGTH))
            resultCode == Activity.RESULT_CANCELED -> UpiLaunchResult.Cancelled
            else -> UpiLaunchResult.Responded(null)
        }
    }

    companion object {
        const val EXTRA_RESPONSE = "response"
        const val MAX_RESPONSE_LENGTH = 16 * 1024
    }
}
