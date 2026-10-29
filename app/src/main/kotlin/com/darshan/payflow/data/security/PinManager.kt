package com.darshan.payflow.data.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed interface PinRule {
    data object Ok : PinRule
    data object WrongLength : PinRule
    data object NotDigits : PinRule
    data object TooSimple : PinRule
    /** Confirmation did not match (used by the setup flow). */
    data object Mismatch : PinRule
}

sealed interface PinVerification {
    data object Ok : PinVerification
    data object NotSet : PinVerification
    data class Wrong(val remaining: Int) : PinVerification
    data class LockedOut(val untilMillis: Long) : PinVerification
}

/** What is persisted (encrypted): never the PIN, only PBKDF2 parameters, the hash and the lockout bookkeeping. */
@Serializable
data class PinRecord(
    val iterations: Int,
    val salt: ByteArray,
    val hash: ByteArray,
    val failures: Int = 0,
    val lockoutRound: Int = 0,
    val lockoutUntilMillis: Long = 0,
)

/**
 * App PIN hashed with PBKDF2-HmacSHA256 (random 16-byte salt, ≥ 100k iterations, constant-time compare) and a lockout that
 * grows with every round of [MAX_ATTEMPTS] failures (30 s, 60 s, … capped at one hour). Locked-out attempts are refused before
 * any hashing, so a brute force cannot even burn CPU.
 */
@Singleton
class PinManager(
    private val store: SecureStore,
    private val clock: Clock,
    private val iterations: Int = DEFAULT_ITERATIONS,
) {
    @Inject constructor(store: SecureStore, clock: Clock) : this(store, clock, DEFAULT_ITERATIONS)

    private val mutex = Mutex()
    private val random = SecureRandom()
    private val json = Json

    suspend fun isSet(): Boolean = load() != null

    suspend fun set(pin: String) {
        require(validateNewPin(pin) == PinRule.Ok) { "invalid pin" }
        mutex.withLock {
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            save(PinRecord(iterations, salt, hash(pin, salt, iterations)))
        }
    }

    suspend fun verify(pin: String): PinVerification = mutex.withLock {
        val record = load() ?: return PinVerification.NotSet
        val now = clock.millis()
        if (record.lockoutUntilMillis > now) return PinVerification.LockedOut(record.lockoutUntilMillis)
        val candidate = hash(pin, record.salt, record.iterations)
        if (MessageDigest.isEqual(candidate, record.hash)) {
            save(record.copy(failures = 0, lockoutRound = 0, lockoutUntilMillis = 0))
            return PinVerification.Ok
        }
        val failures = record.failures + 1
        if (failures >= MAX_ATTEMPTS) {
            val round = record.lockoutRound + 1
            val until = now + lockoutMillisForRound(round)
            save(record.copy(failures = 0, lockoutRound = round, lockoutUntilMillis = until))
            return PinVerification.LockedOut(until)
        }
        save(record.copy(failures = failures))
        PinVerification.Wrong(remaining = MAX_ATTEMPTS - failures)
    }

    suspend fun change(old: String, new: String): PinVerification {
        val result = verify(old)
        if (result == PinVerification.Ok) set(new)
        return result
    }

    suspend fun clear() = mutex.withLock { store.remove(KEY) }

    suspend fun lockoutRemainingMillis(): Long = (load()?.lockoutUntilMillis ?: 0L).minus(clock.millis()).coerceAtLeast(0)

    suspend fun remainingAttempts(): Int = MAX_ATTEMPTS - (load()?.failures ?: 0)

    /** For tests and diagnostics: the stored parameters (never the PIN). */
    suspend fun debugRecord(): PinRecord? = load()

    private suspend fun load(): PinRecord? = store.get(KEY)?.let { runCatching { json.decodeFromString<PinRecord>(it) }.getOrNull() }
    private suspend fun save(record: PinRecord) = store.put(KEY, json.encodeToString(record))

    private fun hash(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, HASH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val KEY = "app_pin"
        const val PIN_LENGTH = 6
        const val MAX_ATTEMPTS = 5
        const val DEFAULT_ITERATIONS = 150_000
        const val SALT_BYTES = 16
        const val HASH_BITS = 256
        const val BASE_LOCKOUT_MILLIS = 30_000L
        const val MAX_LOCKOUT_MILLIS = 60L * 60 * 1_000

        fun lockoutMillisForRound(round: Int): Long {
            var value = BASE_LOCKOUT_MILLIS
            repeat((round - 1).coerceAtLeast(0)) { value = (value * 2).coerceAtMost(MAX_LOCKOUT_MILLIS); if (value == MAX_LOCKOUT_MILLIS) return value }
            return value
        }

        /** 6 ASCII digits, not all the same, not an ascending/descending run, not a repeated pair/triple. */
        fun validateNewPin(pin: String): PinRule {
            if (pin.length != PIN_LENGTH) return PinRule.WrongLength
            if (!pin.all { it in '0'..'9' }) return PinRule.NotDigits
            val digits = pin.map { it - '0' }
            if (digits.toSet().size == 1) return PinRule.TooSimple
            if (digits.zipWithNext().all { (a, b) -> b - a == 1 } || digits.zipWithNext().all { (a, b) -> a - b == 1 }) return PinRule.TooSimple
            if (pin.substring(0, 2).repeat(3) == pin || pin.substring(0, 3).repeat(2) == pin) return PinRule.TooSimple
            return PinRule.Ok
        }

        @Suppress("unused")
        private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    }
}
