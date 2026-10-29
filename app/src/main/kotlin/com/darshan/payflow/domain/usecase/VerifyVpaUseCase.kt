package com.darshan.payflow.domain.usecase

import com.darshan.payflow.data.psp.PspClient
import com.darshan.payflow.data.psp.PspException
import com.darshan.payflow.data.psp.VpaVerification
import com.darshan.payflow.domain.model.VpaError
import com.darshan.payflow.domain.model.VpaValidation
import com.darshan.payflow.domain.model.VpaValidator
import javax.inject.Inject

sealed interface VpaVerifyResult {
    data class InvalidFormat(val error: VpaError) : VpaVerifyResult
    data class Verified(val vpa: String, val payeeName: String) : VpaVerifyResult
    data class NotFound(val vpa: String) : VpaVerifyResult
    /** The PSP could not be reached; the format is fine, the owner is unknown. */
    data class Unavailable(val error: PspException) : VpaVerifyResult
}

/** Format check first (free, offline), then the PSP directory lookup that returns the registered payee name. */
class VerifyVpaUseCase @Inject constructor(private val psp: PspClient) {
    suspend operator fun invoke(raw: String): VpaVerifyResult {
        val vpa = when (val v = VpaValidator.validate(raw)) {
            is VpaValidation.Invalid -> return VpaVerifyResult.InvalidFormat(v.error)
            is VpaValidation.Valid -> v.normalized
        }
        return try {
            when (val result = psp.verifyVpa(vpa)) {
                is VpaVerification.Verified -> VpaVerifyResult.Verified(result.vpa, result.payeeName.take(99))
                is VpaVerification.NotFound -> VpaVerifyResult.NotFound(vpa)
            }
        } catch (e: PspException) {
            VpaVerifyResult.Unavailable(e)
        }
    }
}
