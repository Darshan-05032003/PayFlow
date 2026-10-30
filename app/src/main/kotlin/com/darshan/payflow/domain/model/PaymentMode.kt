package com.darshan.payflow.domain.model

/**
 * Where a payment is sent.
 *
 * [UPI_APPS]: the primary path for a real phone. PayFlow hands `upi://pay` to an installed UPI app (GPay, PhonePe, …) and reads
 * the result the app returns. There is no bank/PSP backend behind PayFlow, so that result is *reported by the app*, never
 * independently verified. [SANDBOX]: every payment is settled by the in-app fake PSP; no real money moves.
 */
enum class PaymentMode {
    UPI_APPS,
    SANDBOX,
    ;

    companion object {
        fun fromNameOrNull(name: String?): PaymentMode? = entries.firstOrNull { it.name == name }
    }
}

/** Who established a payment's current status. Shown in the UI so a UPI-app-reported success is never dressed up as "verified". */
enum class StatusSource {
    /** The sandbox PSP confirmed it (status check matched txnRef, amount and payee). */
    PSP,
    /** Parsed from the `response` extra of the UPI app that handled the payment. Reported, not verified. */
    UPI_APP,
    /** The user marked it by hand from the detail screen after checking in their UPI app. */
    USER,
    ;

    companion object {
        fun fromNameOrDefault(name: String?): StatusSource = entries.firstOrNull { it.name == name } ?: PSP
    }
}
