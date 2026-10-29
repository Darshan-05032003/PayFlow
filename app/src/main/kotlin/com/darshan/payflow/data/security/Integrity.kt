package com.darshan.payflow.data.security

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface IntegrityResult {
    data class Token(val token: String) : IntegrityResult
    data object NotConfigured : IntegrityResult
    data class Error(val code: String) : IntegrityResult
}

/** Requests a Play Integrity token for a request hash. The token is opaque to the client. */
interface IntegrityChecker {
    val isConfigured: Boolean
    suspend fun requestToken(requestHash: String): IntegrityResult
}

enum class IntegrityVerdict { MEETS_DEVICE_INTEGRITY, FAILED, UNEVALUATED }

/**
 * Decodes and verifies a token. In production this MUST happen on a server (Google's `decodeIntegrityToken` API with the
 * project's service credentials); a client cannot verify its own token. PayFlow has no backend, so the sandbox uses
 * [FakeIntegrityVerifier] and says so.
 */
interface IntegrityVerifier {
    val isSandbox: Boolean
    suspend fun verify(token: String): IntegrityVerdict
}

class FakeIntegrityVerifier(private val verdict: IntegrityVerdict = IntegrityVerdict.MEETS_DEVICE_INTEGRITY) : IntegrityVerifier {
    override val isSandbox: Boolean = true
    override suspend fun verify(token: String): IntegrityVerdict = verdict
}

/** Real request through `StandardIntegrityManager` using the Google Cloud project number from `local.properties`. Device-only. */
@Singleton
class PlayIntegrityChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    @CloudProjectNumber private val cloudProjectNumber: Long,
) : IntegrityChecker {
    override val isConfigured: Boolean get() = cloudProjectNumber > 0
    private val mutex = Mutex()
    private var provider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null

    override suspend fun requestToken(requestHash: String): IntegrityResult {
        if (!isConfigured) return IntegrityResult.NotConfigured
        return try {
            val tokenProvider = mutex.withLock {
                provider ?: IntegrityManagerFactory.createStandard(context)
                    .prepareIntegrityToken(PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(cloudProjectNumber).build())
                    .await().also { provider = it }
            }
            val token = tokenProvider.request(StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build()).await()
            IntegrityResult.Token(token.token())
        } catch (e: Exception) {
            IntegrityResult.Error(e.javaClass.simpleName)
        }
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resumeWith(Result.failure(it)) }
        addOnCanceledListener { cont.cancel() }
    }
}

enum class IntegrityReason { UNEVALUATED, REQUEST_FAILED }

sealed interface IntegrityDecision {
    data class Allow(val verdict: IntegrityVerdict) : IntegrityDecision
    data class AllowWithWarning(val reason: IntegrityReason) : IntegrityDecision
    data class Block(val verdict: IntegrityVerdict) : IntegrityDecision
    data object Skipped : IntegrityDecision
}

/** Ties an integrity token to one payment and turns the verdict into a decision. Unavailable Play services warn, they don't block. */
@Singleton
class IntegrityGate @Inject constructor(private val checker: IntegrityChecker, private val verifier: IntegrityVerifier) {
    suspend fun check(paymentId: String, amountPaise: Long, payeeVpa: String): IntegrityDecision {
        return when (val result = checker.requestToken(requestHash(paymentId, amountPaise, payeeVpa))) {
            IntegrityResult.NotConfigured -> IntegrityDecision.Skipped
            is IntegrityResult.Error -> IntegrityDecision.AllowWithWarning(IntegrityReason.REQUEST_FAILED)
            is IntegrityResult.Token -> when (val verdict = verifier.verify(result.token)) {
                IntegrityVerdict.MEETS_DEVICE_INTEGRITY -> IntegrityDecision.Allow(verdict)
                IntegrityVerdict.FAILED -> IntegrityDecision.Block(verdict)
                IntegrityVerdict.UNEVALUATED -> IntegrityDecision.AllowWithWarning(IntegrityReason.UNEVALUATED)
            }
        }
    }

    companion object {
        /** SHA-256 hex of the payment facts; the token's `requestHash` binds the verdict to this exact payment. */
        fun requestHash(paymentId: String, amountPaise: Long, payeeVpa: String): String =
            MessageDigest.getInstance("SHA-256").digest("$paymentId|$amountPaise|${payeeVpa.lowercase()}".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

@javax.inject.Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class CloudProjectNumber
