package com.darshan.payflow.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * Clip data flagged as sensitive, so Android 13+ hides it in the clipboard preview and keyboards don't offer it as a
 * suggestion. Payment references identify a transaction to anyone who sees them.
 */
object SensitiveClip {
    /** The platform's key, spelled out: it has no constant below API 33, and keyboards there read the same string. */
    const val EXTRA_IS_SENSITIVE_COMPAT = "android.content.extra.IS_SENSITIVE"

    fun of(label: CharSequence, text: CharSequence, sdkInt: Int = Build.VERSION.SDK_INT): ClipData =
        ClipData.newPlainText(label, text).apply {
            val key = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE else EXTRA_IS_SENSITIVE_COMPAT
            description.extras = PersistableBundle().apply { putBoolean(key, true) }
        }
}

/** Copies [text] as a sensitive clip. A missing clipboard service is ignored: copying is a convenience. */
fun Context.copySensitive(label: CharSequence, text: CharSequence) {
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(SensitiveClip.of(label, text))
}
