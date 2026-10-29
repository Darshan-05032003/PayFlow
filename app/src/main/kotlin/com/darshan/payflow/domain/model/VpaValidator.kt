package com.darshan.payflow.domain.model

enum class VpaError { EMPTY, MISSING_HANDLE, BAD_FORMAT, TOO_LONG }

sealed interface VpaValidation {
    data class Valid(val normalized: String) : VpaValidation
    data class Invalid(val error: VpaError) : VpaValidation
}

/**
 * Format check for a UPI ID (VPA): `<user>@<handle>`. ASCII only (homoglyphs and bidi controls are rejected),
 * user part 2..256 of `[a-z0-9._-]`, handle 2..64 letters/digits, no dots in the handle, lower-cased.
 */
object VpaValidator {
    const val MAX_LENGTH = 256
    private val regex = Regex("^[a-z0-9][a-z0-9._-]{1,255}@[a-z][a-z0-9]{1,63}$")

    fun validate(raw: String): VpaValidation {
        val value = raw.trim().lowercase()
        if (value.isEmpty()) return VpaValidation.Invalid(VpaError.EMPTY)
        if (value.length > MAX_LENGTH) return VpaValidation.Invalid(VpaError.TOO_LONG)
        if (!value.contains('@')) return VpaValidation.Invalid(VpaError.MISSING_HANDLE)
        if (!regex.matches(value)) return VpaValidation.Invalid(VpaError.BAD_FORMAT)
        return VpaValidation.Valid(value)
    }

    fun isValid(raw: String): Boolean = validate(raw) is VpaValidation.Valid
}
