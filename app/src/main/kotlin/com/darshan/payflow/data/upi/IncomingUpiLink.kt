package com.darshan.payflow.data.upi

import com.darshan.payflow.data.qr.QrParseResult
import com.darshan.payflow.data.qr.UpiQrPayload
import com.darshan.payflow.data.qr.UpiQrPayloadParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Hands a `upi://pay` link received by `MainActivity` (VIEW intent from a browser, chat app or another UPI QR) to the
 * navigation layer. The link goes through the same hostile-input-safe [UpiQrPayloadParser] as scanned QRs; anything that
 * does not parse is dropped silently (the system already showed the user *some* app opened). The pending link is consumed
 * exactly once so a configuration change never re-opens the Pay screen.
 */
@Singleton
class IncomingUpiLinkStore @Inject constructor() {
    private val _pending = MutableStateFlow<UpiQrPayload?>(null)
    val pending: StateFlow<UpiQrPayload?> = _pending.asStateFlow()

    /** Returns true when [raw] was a valid `upi://pay` link and is now waiting to be consumed. */
    fun offer(raw: String?): Boolean {
        val payload = parse(raw) ?: return false
        _pending.value = payload
        return true
    }

    fun consume(): UpiQrPayload? = _pending.getAndUpdate { null }

    companion object {
        /** Hard cap before any parsing (the QR parser has its own 4 KB limit; this one just bounds the copy out of the Intent). */
        const val MAX_RAW_LENGTH = 8_192

        fun parse(raw: String?): UpiQrPayload? {
            if (raw.isNullOrBlank() || raw.length > MAX_RAW_LENGTH) return null
            return (UpiQrPayloadParser.parse(raw) as? QrParseResult.Valid)?.payload
        }
    }
}
