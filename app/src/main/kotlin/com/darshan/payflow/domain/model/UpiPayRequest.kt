package com.darshan.payflow.domain.model

/** Validated input for a `upi://pay` payment. Construction fails fast on anything that would make a broken link. */
class UpiPayRequest(
    payeeVpa: String,
    payeeName: String,
    val amountPaise: Long,
    note: String,
    val txnRef: String,
) {
    /** Lower-cased, trimmed VPA as it goes into the link. */
    val payeeVpa: String = payeeVpa.trim().lowercase()
    val payeeName: String = payeeName.trim().take(MAX_NAME_LENGTH)
    val note: String = note.trim().take(MAX_NOTE_LENGTH)

    init {
        require(VpaValidator.isValid(this.payeeVpa)) { "invalid payee VPA" }
        require(amountPaise in 1..Payment.MAX_AMOUNT_PAISE) { "amount out of range" }
        require(this.payeeName.isNotBlank()) { "payee name is blank" }
        require(TXN_REF_REGEX.matches(txnRef)) { "txnRef must be 1..35 alphanumeric characters" }
    }

    fun copy(
        payeeVpa: String = this.payeeVpa,
        payeeName: String = this.payeeName,
        amountPaise: Long = this.amountPaise,
        note: String = this.note,
        txnRef: String = this.txnRef,
    ) = UpiPayRequest(payeeVpa, payeeName, amountPaise, note, txnRef)

    override fun equals(other: Any?): Boolean = other is UpiPayRequest && other.payeeVpa == payeeVpa &&
        other.payeeName == payeeName && other.amountPaise == amountPaise && other.note == note && other.txnRef == txnRef

    override fun hashCode(): Int = listOf(payeeVpa, payeeName, amountPaise, note, txnRef).hashCode()

    override fun toString(): String = "UpiPayRequest(payeeVpa=$payeeVpa, payeeName=$payeeName, amountPaise=$amountPaise, txnRef=$txnRef)"

    companion object {
        const val MAX_NAME_LENGTH = 99
        const val MAX_NOTE_LENGTH = 99
        val TXN_REF_REGEX = Regex("^[A-Za-z0-9]{1,35}$")
    }
}
