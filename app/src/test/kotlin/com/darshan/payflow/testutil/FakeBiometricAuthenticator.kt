package com.darshan.payflow.testutil

import com.darshan.payflow.data.security.BiometricAuthenticator
import com.darshan.payflow.data.security.BiometricAvailability
import com.darshan.payflow.data.security.BiometricResult

class FakeBiometricAuthenticator : BiometricAuthenticator {
    var availability: BiometricAvailability = BiometricAvailability.AVAILABLE
    var result: BiometricResult = BiometricResult.Success
    var prompts = 0
    override fun availability(): BiometricAvailability = availability
    override suspend fun authenticate(host: Any?, title: String, subtitle: String): BiometricResult { prompts++; return result }
}
