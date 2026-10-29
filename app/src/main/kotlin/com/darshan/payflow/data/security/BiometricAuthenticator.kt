package com.darshan.payflow.data.security

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

enum class BiometricAvailability { AVAILABLE, NONE_ENROLLED, NO_HARDWARE, UNAVAILABLE }

sealed interface BiometricResult {
    data object Success : BiometricResult
    data class Failed(val message: String) : BiometricResult
    data object Cancelled : BiometricResult
    data object Unavailable : BiometricResult
}

/** BiometricPrompt behind an interface. [host] is the hosting `FragmentActivity` on Android; tests pass anything. */
interface BiometricAuthenticator {
    fun availability(): BiometricAvailability
    suspend fun authenticate(host: Any?, title: String, subtitle: String): BiometricResult
}

/** Biometric (strong or weak) with device-credential fallback: the user can always fall back to the device PIN/pattern. */
@Singleton
class AndroidBiometricAuthenticator @Inject constructor(@ApplicationContext private val context: Context) : BiometricAuthenticator {
    private val allowed: Int
        get() = if (Build.VERSION.SDK_INT >= 30) Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
        else Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL

    override fun availability(): BiometricAvailability = when (BiometricManager.from(context).canAuthenticate(allowed)) {
        BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NONE_ENROLLED
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricAvailability.NO_HARDWARE
        else -> BiometricAvailability.UNAVAILABLE
    }

    override suspend fun authenticate(host: Any?, title: String, subtitle: String): BiometricResult {
        val activity = host as? FragmentActivity ?: return BiometricResult.Unavailable
        if (availability() != BiometricAvailability.AVAILABLE) return BiometricResult.Unavailable
        return suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(
                activity, ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { if (cont.isActive) cont.resume(BiometricResult.Success) }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!cont.isActive) return
                        val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_CANCELED
                        cont.resume(if (cancelled) BiometricResult.Cancelled else BiometricResult.Failed(errString.toString()))
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle).setAllowedAuthenticators(allowed).build()
            prompt.authenticate(info)
            cont.invokeOnCancellation { runCatching { prompt.cancelAuthentication() } }
        }
    }
}
