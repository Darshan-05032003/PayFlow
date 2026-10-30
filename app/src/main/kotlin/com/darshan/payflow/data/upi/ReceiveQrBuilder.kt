package com.darshan.payflow.data.upi

import com.darshan.payflow.domain.model.UpiProfile

/**
 * Builds the `upi://pay?pa=&pn=[&am=]&cu=INR` link encoded into the "receive money" QR. The VPA has already passed
 * [com.darshan.payflow.domain.model.VpaValidator]; the name is percent-encoded so `&`, `=` or unicode can never inject a
 * parameter, and the optional amount is formatted from paise (never a user string).
 */
object ReceiveQrBuilder {
    fun build(profile: UpiProfile, amountPaise: Long? = null): String = buildString {
        append("upi://pay?pa=").append(UpiLinkBuilder.encode(profile.vpa))
        append("&pn=").append(UpiLinkBuilder.encode(profile.name))
        if (amountPaise != null && amountPaise > 0) append("&am=").append(UpiLinkBuilder.formatAmount(amountPaise))
        append("&cu=INR")
    }
}
