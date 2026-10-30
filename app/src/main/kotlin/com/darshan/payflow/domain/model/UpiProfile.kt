package com.darshan.payflow.domain.model

import kotlinx.coroutines.flow.Flow

/** The user's own UPI identity, used to render the "receive money" QR. Stored encrypted; never sent anywhere. */
data class UpiProfile(val name: String, val vpa: String) {
    companion object {
        const val MAX_NAME_LENGTH = 50

        /** Returns a normalised profile or the field errors. */
        fun validate(name: String, vpa: String): UpiProfileValidation {
            val cleanName = name.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }.trim()
            val nameError = when {
                cleanName.isEmpty() -> ProfileFieldError.REQUIRED
                cleanName.length > MAX_NAME_LENGTH -> ProfileFieldError.TOO_LONG
                else -> null
            }
            val vpaResult = VpaValidator.validate(vpa)
            val vpaError = when (vpaResult) {
                is VpaValidation.Valid -> null
                is VpaValidation.Invalid -> if (vpa.isBlank()) ProfileFieldError.REQUIRED else ProfileFieldError.INVALID_VPA
            }
            if (nameError != null || vpaError != null) return UpiProfileValidation.Invalid(nameError, vpaError)
            return UpiProfileValidation.Valid(UpiProfile(cleanName, (vpaResult as VpaValidation.Valid).normalized))
        }
    }
}

enum class ProfileFieldError { REQUIRED, TOO_LONG, INVALID_VPA }

sealed interface UpiProfileValidation {
    data class Valid(val profile: UpiProfile) : UpiProfileValidation
    data class Invalid(val name: ProfileFieldError?, val vpa: ProfileFieldError?) : UpiProfileValidation
}

interface UpiProfileRepository {
    val profile: Flow<UpiProfile?>
    suspend fun get(): UpiProfile?
    suspend fun save(profile: UpiProfile)
    suspend fun clear()
}
