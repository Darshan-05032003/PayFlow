package com.darshan.payflow.data.security

import android.content.Context
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlin.random.asKotlinRandom
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A stable identifier for *this installation on this device*; the binding is tied to it. */
interface DeviceIdentity {
    fun fingerprint(): String
}

class AndroidDeviceIdentity @Inject constructor(@ApplicationContext private val context: Context) : DeviceIdentity {
    override fun fingerprint(): String = fingerprintOf(
        androidId = runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull() ?: "",
        brand = Build.BRAND, model = Build.MODEL, board = Build.BOARD,
    )

    companion object {
        /** SHA-256 over the identifiers: stable per device + app signing key (ANDROID_ID is per app-signing-key since Android 8). */
        fun fingerprintOf(androidId: String, brand: String, model: String, board: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("$androidId|$brand|$model|$board".toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

data class SentOtp(val phone: String, val code: String)

/** Delivers a one-time code to a phone. The sandbox has no SMS gateway: [FakeOtpSender] shows the code in the app instead. */
interface OtpSender {
    suspend fun send(phone: String, code: String)
}

@Singleton
class FakeOtpSender @Inject constructor() : OtpSender {
    val sent: MutableList<SentOtp> = Collections.synchronizedList(mutableListOf())
    val lastCode: String? get() = sent.lastOrNull()?.code
    override suspend fun send(phone: String, code: String) { sent += SentOtp(phone, code) }
}

sealed interface OtpRequest {
    data object InvalidPhone : OtpRequest
    data class Sent(val maskedPhone: String) : OtpRequest
}

sealed interface OtpVerification {
    data object Bound : OtpVerification
    data class Wrong(val remaining: Int) : OtpVerification
    data object Expired : OtpVerification
    data object NoPending : OtpVerification
}

sealed interface BindingStatus {
    data object Unbound : BindingStatus
    data class OtpPending(val maskedPhone: String) : BindingStatus
    data class Bound(val fingerprint: String, val maskedPhone: String) : BindingStatus
    /** The stored binding belongs to another device (cloned data / restored backup): re-verification is required. */
    data class Mismatch(val boundTo: String, val maskedPhone: String) : BindingStatus
}

@Serializable
private data class BindingRecord(val fingerprint: String, val phone: String, val boundAtMillis: Long)

@Serializable
private data class PendingOtp(val phone: String, val code: String, val expiresAtMillis: Long, val attemptsLeft: Int)

/**
 * Simulated device binding: the user proves control of a phone number with an SMS OTP (sandbox: shown in-app), and the binding
 * record (fingerprint + phone) is stored encrypted. A fingerprint mismatch on a later launch means the data moved devices.
 */
@Singleton
class DeviceBindingManager(
    private val store: SecureStore,
    private val identity: DeviceIdentity,
    private val sender: OtpSender,
    private val clock: Clock,
    private val random: Random = SecureRandom().asKotlinRandom(),
) {
    @Inject constructor(store: SecureStore, identity: DeviceIdentity, sender: OtpSender, clock: Clock) :
        this(store, identity, sender, clock, SecureRandom().asKotlinRandom())

    private val mutex = Mutex()
    private val json = Json

    suspend fun status(): BindingStatus {
        val binding = store.get(KEY_BINDING)?.let { runCatching { json.decodeFromString<BindingRecord>(it) }.getOrNull() }
        if (binding != null) {
            val current = identity.fingerprint()
            return if (binding.fingerprint == current) BindingStatus.Bound(current, mask(binding.phone)) else BindingStatus.Mismatch(binding.fingerprint, mask(binding.phone))
        }
        val pending = loadPending()
        return if (pending != null && pending.attemptsLeft > 0 && pending.expiresAtMillis > clock.millis()) BindingStatus.OtpPending(mask(pending.phone)) else BindingStatus.Unbound
    }

    suspend fun requestOtp(rawPhone: String): OtpRequest = mutex.withLock {
        val phone = normalisePhone(rawPhone) ?: return OtpRequest.InvalidPhone
        val code = String.format(java.util.Locale.ROOT, "%06d", random.nextInt(0, 1_000_000))
        store.put(KEY_PENDING, json.encodeToString(PendingOtp(phone, code, clock.millis() + OTP_TTL_SECONDS * 1_000, OTP_ATTEMPTS)))
        sender.send(phone, code)
        OtpRequest.Sent(mask(phone))
    }

    suspend fun verifyOtp(code: String): OtpVerification = mutex.withLock {
        val pending = loadPending() ?: return OtpVerification.NoPending
        if (pending.expiresAtMillis <= clock.millis() || pending.attemptsLeft <= 0) {
            store.remove(KEY_PENDING)
            return OtpVerification.Expired
        }
        if (!MessageDigest.isEqual(code.trim().toByteArray(), pending.code.toByteArray())) {
            val left = pending.attemptsLeft - 1
            // Burned (0 attempts left): kept so the next attempt is answered "expired", not "nothing pending".
            store.put(KEY_PENDING, json.encodeToString(pending.copy(attemptsLeft = left)))
            return if (left <= 0) OtpVerification.Expired else OtpVerification.Wrong(remaining = left)
        }
        store.put(KEY_BINDING, json.encodeToString(BindingRecord(identity.fingerprint(), pending.phone, clock.millis())))
        store.remove(KEY_PENDING)
        OtpVerification.Bound
    }

    suspend fun unbind() = mutex.withLock { store.remove(KEY_BINDING); store.remove(KEY_PENDING) }

    private suspend fun loadPending(): PendingOtp? = store.get(KEY_PENDING)?.let { runCatching { json.decodeFromString<PendingOtp>(it) }.getOrNull() }

    companion object {
        const val KEY_BINDING = "device_binding"
        const val KEY_PENDING = "device_binding_otp"
        const val OTP_TTL_SECONDS = 300L
        const val OTP_ATTEMPTS = 3

        /** Indian mobile numbers: 10 digits starting 6–9, optionally prefixed with +91 / 91 / 0. */
        fun normalisePhone(raw: String): String? {
            val digits = raw.filter { it.isDigit() }
            val national = when {
                digits.length == 10 -> digits
                digits.length == 12 && digits.startsWith("91") -> digits.substring(2)
                digits.length == 11 && digits.startsWith("0") -> digits.substring(1)
                else -> return null
            }
            if (national.first() !in '6'..'9') return null
            return "+91$national"
        }

        fun mask(phone: String): String = "+91 ****" + phone.takeLast(4)
    }
}
