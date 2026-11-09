package com.darshan.payflow.data.upi

import android.content.Intent
import android.os.Bundle

/**
 * What PayFlow accepts from the intent that opened it: nothing but a `upi://pay` link, which then goes through the QR parser.
 *
 * The launcher activity is exported, so any app can start it with any data and extras. Navigation reads the activity's intent
 * when the graph is created and treats some of it as a deep link (destination ids in extras, route URIs in data), which would
 * reach screens and arguments without passing the parser. PayFlow declares no deep links of its own, so all of that goes.
 */
object IncomingIntentPolicy {
    fun isUpiLink(action: String?, scheme: String?, host: String?): Boolean =
        action == Intent.ACTION_VIEW && scheme.equals("upi", ignoreCase = true) && host.equals("pay", ignoreCase = true)

    fun sanitize(intent: Intent): Intent {
        val clean = Intent(intent)
        clean.replaceExtras(Bundle())
        if (!isUpiLink(intent.action, intent.data?.scheme, intent.data?.host)) clean.data = null
        return clean
    }
}
